package com.example.provisioning.activity;

import com.example.provisioning.kafka.HlrBus;
import com.example.provisioning.kafka.HlrBusFactory;
import com.example.provisioning.kafka.ProvisioningCommandMessage;
import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.NetworkElement;
import com.example.provisioning.model.NetworkElement.ElementType;
import com.example.provisioning.model.ProvisioningPlan;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Activity implementations for the CSP change provisioning pipeline.
 *
 * Sync activities (validate, lockSim, decompose, updateSimInventory, rollbackLock)
 * simulate calls to domain microservice APIs with realistic latency.
 *
 * provisioningCommand publishes one message per NetworkElement to the bus
 * (Kafka in production, InProcessHlrBus in dev/test) and returns immediately.
 *
 * Failure injection via PROVISIONING_FAIL_AT env var:
 *   VALIDATE    → validate throws
 *   LOCK        → lockSim throws
 *   DECOMPOSE   → decompose throws
 *   PROVISION   → provisioningCommand throws (before any publish)
 *   INVENTORY   → updateSimInventory throws
 */
public class CspChangeActivitiesImpl implements CspChangeActivities {

    private static final Logger log = LoggerFactory.getLogger(CspChangeActivitiesImpl.class);

    private static final String FAIL_AT =
            System.getenv().getOrDefault("PROVISIONING_FAIL_AT", "NONE").toUpperCase();

    // ── validate ──────────────────────────────────────────────────────────────

    @Override
    public void validate(CspChangeRequest request) {
        log.info("[{}] → Validation Service: iccid={}, {}→{}",
                request.getCorrelationId(), request.getIccid(),
                request.getCurrentCsp(), request.getTargetCsp());
        sleep(300); // simulate API round-trip

        if ("VALIDATE".equals(FAIL_AT)) {
            throw Activity.wrap(new IllegalArgumentException(
                    "Validation Service rejected: ICCID " + request.getIccid() +
                    " not found or CSP '" + request.getCurrentCsp() + "' mismatch"));
        }

        log.info("[{}] ← Validation Service: OK", request.getCorrelationId());
    }

    // ── lockSim ───────────────────────────────────────────────────────────────

    @Override
    public String lockSim(CspChangeRequest request) {
        log.info("[{}] → SIM Inventory Service: lock iccid={}",
                request.getCorrelationId(), request.getIccid());
        sleep(200);

        if ("LOCK".equals(FAIL_AT)) {
            throw Activity.wrap(new IllegalStateException(
                    "SIM Inventory Service: iccid=" + request.getIccid() +
                    " already locked by another operation"));
        }

        String lockToken = "LOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] ← SIM Inventory Service: locked, token={}", request.getCorrelationId(), lockToken);
        return lockToken;
    }

    // ── decompose ─────────────────────────────────────────────────────────────

    @Override
    public ProvisioningPlan decompose(CspChangeRequest request) {
        log.info("[{}] → Decomposition Service: iccid={}, targetCsp={}",
                request.getCorrelationId(), request.getIccid(), request.getTargetCsp());
        sleep(400); // decomposition may involve routing table lookups

        if ("DECOMPOSE".equals(FAIL_AT)) {
            throw Activity.wrap(new RuntimeException(
                    "Decomposition Service unavailable for iccid=" + request.getIccid()));
        }

        // Stub: a real implementation would call the Decomposition microservice
        // which resolves which HLR, MSC, SGSN nodes serve this ICCID/MSISDN
        // and what profile code each element expects.
        List<NetworkElement> elements = List.of(
                new NetworkElement(ElementType.HLR,  "HLR-EU-01",  request.getNetworkId(), request.getTargetCsp()),
                new NetworkElement(ElementType.MSC,  "MSC-EU-03",  request.getNetworkId(), "MSC-" + request.getTargetCsp()),
                new NetworkElement(ElementType.SGSN, "SGSN-EU-07", request.getNetworkId(), "SGSN-" + request.getTargetCsp())
        );

        ProvisioningPlan plan = new ProvisioningPlan(
                request.getCorrelationId(), request.getIccid(),
                request.getTargetCsp(), elements);

        log.info("[{}] ← Decomposition Service: {} elements — {}",
                request.getCorrelationId(), elements.size(), elements);
        return plan;
    }

    // ── provisioningCommand ───────────────────────────────────────────────────

    @Override
    public void provisioningCommand(ProvisioningPlan plan) {
        log.info("[{}] Publishing {} provisioning commands to bus",
                plan.getCorrelationId(), plan.getElements().size());

        if ("PROVISION".equals(FAIL_AT)) {
            throw Activity.wrap(new RuntimeException(
                    "Bus unavailable: failed to publish provisioning commands for iccid=" + plan.getIccid()));
        }

        ActivityExecutionContext ctx = Activity.getExecutionContext();
        String workflowId = ctx.getInfo().getWorkflowId();

        HlrBus bus = HlrBusFactory.get();
        for (NetworkElement element : plan.getElements()) {
            ProvisioningCommandMessage msg = new ProvisioningCommandMessage(
                    plan.getCorrelationId(),
                    workflowId,
                    plan.getIccid(),
                    element.getElementId(),
                    element.getElementType(),
                    element.getNetworkId(),
                    element.getTargetProfile()
            );
            long offset = bus.publishCommand(msg);
            log.info("[{}] Command published: elementId={}, type={}, offset={}",
                    plan.getCorrelationId(), element.getElementId(), element.getElementType(), offset);
        }
    }

    // ── updateSimInventory ────────────────────────────────────────────────────

    @Override
    public void updateSimInventory(CspChangeRequest request, String appliedCsp) {
        log.info("[{}] → SIM Inventory Service: update iccid={}, csp {}→{} (also releases lock)",
                request.getCorrelationId(), request.getIccid(),
                request.getCurrentCsp(), appliedCsp);
        sleep(300);

        if ("INVENTORY".equals(FAIL_AT)) {
            throw Activity.wrap(new RuntimeException(
                    "SIM Inventory Service write failed for iccid=" + request.getIccid()));
        }

        log.info("[{}] ← SIM Inventory Service: updated and lock released", request.getCorrelationId());
    }

    // ── rollbackLock (saga compensation) ──────────────────────────────────────

    @Override
    public void rollbackLock(String lockToken) {
        log.info("[COMPENSATION] → SIM Inventory Service: rollback lock token={}", lockToken);
        sleep(200);
        log.info("[COMPENSATION] ← SIM Inventory Service: lock rolled back, token={}", lockToken);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
