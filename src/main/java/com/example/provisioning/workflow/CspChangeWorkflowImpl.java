package com.example.provisioning.workflow;

import com.example.provisioning.activity.CspChangeActivities;
import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.CspChangeResult;
import com.example.provisioning.model.ElementConfirmation;
import com.example.provisioning.model.NetworkElement;
import com.example.provisioning.model.ProvisioningPlan;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * CSP change provisioning pipeline.
 *
 * Steps:
 *   1. validate            — Validation Service API (sync)
 *   2. lockSim             — SIM Inventory Service API (sync)
 *                            → saga compensation: rollbackLock
 *   3. decompose           — Decomposition Service API (sync)
 *                            → returns ProvisioningPlan (list of NetworkElements)
 *   4. provisioningCommand — publishes one Kafka message per NetworkElement (async)
 *   5. [WAIT]              — Workflow.await until all elementConfirmationReceived
 *                            signals arrive (one per element) or timeout
 *                            → any failure or timeout: rollbackLock, return FAILED/TIMED_OUT
 *   6. updateSimInventory  — SIM Inventory Service API (sync, also releases lock)
 *
 * Signal tracking:
 *   confirmations is a Map<elementId, ElementConfirmation> populated by
 *   elementConfirmationReceived signals. The await condition checks that
 *   all expected elementIds are present.
 */
public class CspChangeWorkflowImpl implements CspChangeWorkflow {

    private static final Logger log = Workflow.getLogger(CspChangeWorkflowImpl.class);

    private static final int PROVISIONING_TIMEOUT_SECONDS = 60;

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

    // Written by elementConfirmationReceived signals, read by the main thread via Workflow.await
    private final Map<String, ElementConfirmation> confirmations = new HashMap<>();

    @Override
    public void elementConfirmationReceived(ElementConfirmation confirmation) {
        log.info("[{}] Element confirmation signal received: elementId={}, type={}, success={}",
                confirmation.getCorrelationId(),
                confirmation.getElementId(),
                confirmation.getElementType(),
                confirmation.isSuccess());
        confirmations.put(confirmation.getElementId(), confirmation);
    }

    @Override
    public CspChangeResult changeCsp(CspChangeRequest request) {
        log.info("[{}] Starting CSP change: {}", request.getCorrelationId(), request);

        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .build());

        try {
            // ── Step 1: Validate ──────────────────────────────────────────────
            activities.validate(request);
            log.info("[{}] Validation passed", request.getCorrelationId());

            // ── Step 2: Lock SIM ──────────────────────────────────────────────
            String lockToken = activities.lockSim(request);
            saga.addCompensation(activities::rollbackLock, lockToken);
            log.info("[{}] SIM locked: token={}", request.getCorrelationId(), lockToken);

            // ── Step 3: Decompose ─────────────────────────────────────────────
            ProvisioningPlan plan = activities.decompose(request);
            log.info("[{}] Provisioning plan: {} elements — {}",
                    request.getCorrelationId(), plan.getElements().size(), plan.getElements());

            // ── Step 4: Publish provisioning commands (one per element) ───────
            activities.provisioningCommand(plan);
            log.info("[{}] Provisioning commands published for {} elements",
                    request.getCorrelationId(), plan.getElements().size());

            // ── Step 5: Wait for all element confirmations ────────────────────
            log.info("[{}] Waiting for {} element confirmations (timeout={}s)...",
                    request.getCorrelationId(), plan.getElements().size(), PROVISIONING_TIMEOUT_SECONDS);

            boolean allReceived = Workflow.await(
                    Duration.ofSeconds(PROVISIONING_TIMEOUT_SECONDS),
                    () -> plan.getElements().stream()
                            .map(NetworkElement::getElementId)
                            .allMatch(confirmations::containsKey)
            );

            if (!allReceived) {
                long received = plan.getElements().stream()
                        .map(NetworkElement::getElementId)
                        .filter(confirmations::containsKey)
                        .count();
                log.warn("[{}] Provisioning timed out after {}s — received {}/{} confirmations",
                        request.getCorrelationId(), PROVISIONING_TIMEOUT_SECONDS,
                        received, plan.getElements().size());
                saga.compensate();
                return CspChangeResult.timedOut(request.getCorrelationId(), request.getIccid());
            }

            // Check all confirmations for errors
            for (NetworkElement element : plan.getElements()) {
                ElementConfirmation conf = confirmations.get(element.getElementId());
                if (!conf.isSuccess()) {
                    log.error("[{}] Element {} ({}) returned error: code={}, message={}",
                            request.getCorrelationId(),
                            conf.getElementId(), conf.getElementType(),
                            conf.getErrorCode(), conf.getErrorMessage());
                    saga.compensate();
                    return CspChangeResult.failed(
                            request.getCorrelationId(),
                            request.getIccid(),
                            conf.getErrorCode(),
                            "Element " + conf.getElementId() + ": " + conf.getErrorMessage());
                }
                log.info("[{}] Element {} ({}) confirmed: appliedProfile={}",
                        request.getCorrelationId(),
                        conf.getElementId(), conf.getElementType(), conf.getAppliedProfile());
            }

            // ── Step 6: Update SIM Inventory ──────────────────────────────────
            // Use the HLR's applied profile as the canonical CSP (first element is HLR by convention)
            String appliedCsp = confirmations.get(
                    plan.getElements().get(0).getElementId()).getAppliedProfile();

            activities.updateSimInventory(request, appliedCsp);
            log.info("[{}] CSP change complete: {}→{} across {} elements",
                    request.getCorrelationId(), request.getCurrentCsp(),
                    appliedCsp, plan.getElements().size());

            return CspChangeResult.completed(
                    request.getCorrelationId(), request.getIccid(), appliedCsp);

        } catch (Exception e) {
            log.error("[{}] CSP change failed: {} — compensating",
                    request.getCorrelationId(), e.getMessage());
            saga.compensate();
            return CspChangeResult.failed(
                    request.getCorrelationId(), request.getIccid(),
                    "INTERNAL_ERROR", e.getMessage());
        }
    }
}
