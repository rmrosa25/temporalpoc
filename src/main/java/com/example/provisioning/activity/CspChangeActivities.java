package com.example.provisioning.activity;

import com.example.provisioning.model.CspChangeRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activities for the CSP change provisioning pipeline.
 *
 * Each method represents one integration point. In production:
 *   - validateRequest    → queries SIM Inventory DB + business rules engine
 *   - lockSim            → writes a lock record to SIM Inventory DB
 *   - unlockSim          → removes the lock record (saga compensation)
 *   - publishHlrCommand  → produces a message to a Kafka topic consumed by the HLR gateway
 *   - updateSimInventory → writes the final CSP state to SIM Inventory DB
 *
 * All implementations here are stubs that simulate latency and log intent.
 */
@ActivityInterface
public interface CspChangeActivities {

    /**
     * Validates that the CSP change can be processed:
     *   - SIM exists and is active
     *   - currentCsp matches what is recorded in inventory
     *   - targetCsp is a valid profile code
     *   - no conflicting change is already in progress
     */
    @ActivityMethod
    void validateRequest(CspChangeRequest request);

    /**
     * Marks the SIM as locked for change in SIM Inventory.
     * Prevents concurrent provisioning operations on the same ICCID.
     * Returns a lock token used for unlocking.
     */
    @ActivityMethod
    String lockSim(CspChangeRequest request);

    /**
     * Releases the SIM lock. Called as saga compensation if any
     * subsequent step fails after the lock was acquired.
     */
    @ActivityMethod
    void unlockSim(String lockToken);

    /**
     * Publishes a CSP change command to the HLR Kafka topic.
     * The HLR gateway consumes this message and applies the profile change
     * in the network. Returns the command message ID for correlation.
     */
    @ActivityMethod
    String publishHlrCommand(CspChangeRequest request);

    /**
     * Updates SIM Inventory with the final applied CSP after HLR confirms success.
     * Also clears the lock and records the change audit trail.
     */
    @ActivityMethod
    void updateSimInventory(CspChangeRequest request, String appliedCsp);
}
