package com.example.provisioning.workflow;

import com.example.provisioning.activity.CspChangeActivities;
import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.model.HlrConfirmation;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * CSP change provisioning pipeline.
 *
 * Steps:
 *   1. validateRequest   — verify SIM exists, CSP is valid, no concurrent change
 *   2. lockSim           — mark SIM as change-in-progress in inventory
 *                          → saga compensation: unlockSim
 *   3. publishHlrCommand — send CSP change command to HLR via Kafka
 *                          → saga compensation: unlockSim (lock still held)
 *   4. [WAIT]            — block on hlrConfirmationReceived signal (max HLR_TIMEOUT_SECONDS)
 *                          → on timeout: unlock SIM, return TIMED_OUT
 *                          → on HLR error: unlock SIM, return FAILED
 *   5. updateSimInventory — persist the applied CSP and release the lock
 *
 * The signal (hlrConfirmationReceived) is delivered externally — in production
 * by a Kafka consumer that reads the HLR response topic and calls the Temporal
 * signal API. In tests, the TestRunner sends it directly after workflow start.
 */
public class CspChangeWorkflowImpl implements CspChangeWorkflow {

    private static final Logger log = Workflow.getLogger(CspChangeWorkflowImpl.class);

    // How long to wait for HLR confirmation before timing out
    private static final int HLR_TIMEOUT_SECONDS = 30;

    private final CspChangeActivities activities = Workflow.newActivityStub(
            CspChangeActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .build())
                    .build()
    );

    // Signal state — written by hlrConfirmationReceived, read by the main thread
    private HlrConfirmation hlrConfirmation = null;

    @Override
    public void hlrConfirmationReceived(HlrConfirmation confirmation) {
        log.info("[{}] HLR confirmation signal received: {}",
                confirmation.getCorrelationId(), confirmation);
        this.hlrConfirmation = confirmation;
    }

    @Override
    public CspChangeResult changeCsp(CspChangeRequest request) {
        log.info("[{}] Starting CSP change: {}", request.getCorrelationId(), request);

        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .build());

        try {
            // ── Step 1: Validate ──────────────────────────────────────────────
            activities.validateRequest(request);
            log.info("[{}] Validation passed", request.getCorrelationId());

            // ── Step 2: Lock SIM ──────────────────────────────────────────────
            String lockToken = activities.lockSim(request);
            saga.addCompensation(activities::unlockSim, lockToken);
            log.info("[{}] SIM locked: token={}", request.getCorrelationId(), lockToken);

            // ── Step 3: Publish HLR command to Kafka ──────────────────────────
            String commandId = activities.publishHlrCommand(request);
            log.info("[{}] HLR command published: commandId={}", request.getCorrelationId(), commandId);

            // ── Step 4: Wait for HLR confirmation signal ──────────────────────
            log.info("[{}] Waiting for HLR confirmation (timeout={}s)...",
                    request.getCorrelationId(), HLR_TIMEOUT_SECONDS);

            boolean signalReceived = Workflow.await(
                    Duration.ofSeconds(HLR_TIMEOUT_SECONDS),
                    () -> hlrConfirmation != null
            );

            if (!signalReceived) {
                // No confirmation within the timeout window
                log.warn("[{}] HLR confirmation timed out after {}s — compensating",
                        request.getCorrelationId(), HLR_TIMEOUT_SECONDS);
                saga.compensate();
                return CspChangeResult.timedOut(request.getCorrelationId(), request.getIccid());
            }

            if (!hlrConfirmation.isSuccess()) {
                // HLR returned an explicit error
                log.error("[{}] HLR returned error: code={}, message={}",
                        request.getCorrelationId(),
                        hlrConfirmation.getErrorCode(),
                        hlrConfirmation.getErrorMessage());
                saga.compensate();
                return CspChangeResult.failed(
                        request.getCorrelationId(),
                        request.getIccid(),
                        hlrConfirmation.getErrorCode(),
                        hlrConfirmation.getErrorMessage());
            }

            log.info("[{}] HLR confirmed success: appliedCsp={}",
                    request.getCorrelationId(), hlrConfirmation.getAppliedCsp());

            // ── Step 5: Update SIM Inventory ──────────────────────────────────
            activities.updateSimInventory(request, hlrConfirmation.getAppliedCsp());
            log.info("[{}] SIM Inventory updated. CSP change complete: {}→{}",
                    request.getCorrelationId(), request.getCurrentCsp(),
                    hlrConfirmation.getAppliedCsp());

            return CspChangeResult.completed(
                    request.getCorrelationId(),
                    request.getIccid(),
                    hlrConfirmation.getAppliedCsp());

        } catch (Exception e) {
            log.error("[{}] CSP change failed: {} — compensating",
                    request.getCorrelationId(), e.getMessage());
            saga.compensate();
            return CspChangeResult.failed(
                    request.getCorrelationId(),
                    request.getIccid(),
                    "INTERNAL_ERROR",
                    e.getMessage());
        }
    }
}
