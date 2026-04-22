package com.example.provisioning.activity;

import com.example.provisioning.model.CspChangeRequest;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Stub implementations that simulate real integration behaviour.
 *
 * Failure injection via PROVISIONING_FAIL_AT env var:
 *   VALIDATE        → validateRequest throws (SIM not found / invalid CSP)
 *   LOCK            → lockSim throws (concurrent change already in progress)
 *   HLR_PUBLISH     → publishHlrCommand throws (Kafka broker unavailable)
 *   HLR_ERROR       → HLR returns an error in its confirmation (injected via signal)
 *   HLR_TIMEOUT     → no signal arrives within the workflow timeout window
 *   INVENTORY       → updateSimInventory throws (DB write failure)
 */
public class CspChangeActivitiesImpl implements CspChangeActivities {

    private static final Logger log = LoggerFactory.getLogger(CspChangeActivitiesImpl.class);

    private static final String FAIL_AT =
            System.getenv().getOrDefault("PROVISIONING_FAIL_AT", "NONE").toUpperCase();

    @Override
    public void validateRequest(CspChangeRequest request) {
        log.info("[{}] Validating CSP change: iccid={}, {}→{}",
                request.getCorrelationId(), request.getIccid(),
                request.getCurrentCsp(), request.getTargetCsp());
        sleep(300);

        if ("VALIDATE".equals(FAIL_AT)) {
            throw Activity.wrap(new IllegalArgumentException(
                    "Validation failed: ICCID " + request.getIccid() +
                    " not found in SIM Inventory or CSP '" + request.getCurrentCsp() + "' mismatch"));
        }

        log.info("[{}] Validation passed", request.getCorrelationId());
    }

    @Override
    public String lockSim(CspChangeRequest request) {
        log.info("[{}] Locking SIM iccid={} for exclusive change",
                request.getCorrelationId(), request.getIccid());
        sleep(200);

        if ("LOCK".equals(FAIL_AT)) {
            throw Activity.wrap(new IllegalStateException(
                    "Cannot lock SIM " + request.getIccid() +
                    ": another provisioning operation is already in progress"));
        }

        String lockToken = "LOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] SIM locked: token={}", request.getCorrelationId(), lockToken);
        return lockToken;
    }

    @Override
    public void unlockSim(String lockToken) {
        log.info("[COMPENSATION] Releasing SIM lock: token={}", lockToken);
        sleep(200);
        log.info("[COMPENSATION] SIM lock released: token={}", lockToken);
    }

    @Override
    public String publishHlrCommand(CspChangeRequest request) {
        log.info("[{}] Publishing HLR command to Kafka: iccid={}, targetCsp={}",
                request.getCorrelationId(), request.getIccid(), request.getTargetCsp());
        sleep(400);

        if ("HLR_PUBLISH".equals(FAIL_AT)) {
            throw Activity.wrap(new RuntimeException(
                    "Kafka broker unavailable: failed to publish HLR command for " +
                    request.getIccid()));
        }

        String commandId = "HLR-CMD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] HLR command published: commandId={}", request.getCorrelationId(), commandId);
        return commandId;
    }

    @Override
    public void updateSimInventory(CspChangeRequest request, String appliedCsp) {
        log.info("[{}] Updating SIM Inventory: iccid={}, csp {} → {}",
                request.getCorrelationId(), request.getIccid(),
                request.getCurrentCsp(), appliedCsp);
        sleep(300);

        if ("INVENTORY".equals(FAIL_AT)) {
            throw Activity.wrap(new RuntimeException(
                    "SIM Inventory DB write failed for iccid=" + request.getIccid()));
        }

        log.info("[{}] SIM Inventory updated successfully", request.getCorrelationId());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
