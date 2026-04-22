package com.example.provisioning.activity;

import com.example.provisioning.model.CspChangeRequest;
import com.example.provisioning.model.ProvisioningPlan;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activities for the CSP change provisioning pipeline.
 *
 * Sync activities call domain microservice APIs (HTTP/gRPC).
 * The async activity publishes to Kafka and returns immediately —
 * the workflow waits for confirmation signals.
 *
 * Pipeline:
 *   validate            → Validation Service API (sync)
 *   lockSim             → SIM Inventory Service API (sync)
 *   decompose           → Decomposition Service API (sync)
 *                         returns ProvisioningPlan: which HLR/MSC/SGSN/… to hit
 *   provisioningCommand → Kafka publish per NetworkElement (async)
 *                         workflow awaits one ElementConfirmation signal per element
 *   updateSimInventory  → SIM Inventory Service API (sync, also clears the lock)
 *
 * Saga compensation:
 *   rollbackLock → SIM Inventory Service API, called if any step after lockSim fails
 */
@ActivityInterface
public interface CspChangeActivities {

    /**
     * Calls the Validation Service to verify:
     *   - SIM exists and is active in inventory
     *   - currentCsp matches the recorded profile
     *   - targetCsp is a valid, reachable profile code
     *   - no concurrent change is already in progress for this ICCID
     *
     * Throws on any validation failure. No compensation needed (no state written).
     */
    @ActivityMethod
    void validate(CspChangeRequest request);

    /**
     * Calls the SIM Inventory Service to mark the SIM as change-in-progress.
     * Prevents concurrent provisioning on the same ICCID.
     * Returns a lock token used for rollback.
     */
    @ActivityMethod
    String lockSim(CspChangeRequest request);

    /**
     * Calls the Decomposition Service to determine which network elements
     * (HLR, MSC, SGSN, GGSN, HSS) require a provisioning command for this
     * CSP change, and which profile to apply on each.
     *
     * Returns a ProvisioningPlan with one NetworkElement per target system.
     */
    @ActivityMethod
    ProvisioningPlan decompose(CspChangeRequest request);

    /**
     * Publishes one provisioning command to Kafka per NetworkElement in the plan.
     * Each message is keyed by ICCID+elementId for per-element ordering.
     *
     * Returns immediately after publishing — does NOT wait for network responses.
     * The workflow awaits one ElementConfirmation signal per element.
     */
    @ActivityMethod
    void provisioningCommand(ProvisioningPlan plan);

    /**
     * Calls the SIM Inventory Service to persist the final applied CSP,
     * record the audit trail, and release the lock.
     * Only called after all element confirmations succeed.
     */
    @ActivityMethod
    void updateSimInventory(CspChangeRequest request, String appliedCsp);

    /**
     * Calls the SIM Inventory Service to roll back the lock acquired by lockSim.
     * Saga compensation — called if any step after lockSim fails.
     */
    @ActivityMethod
    void rollbackLock(String lockToken);
}
