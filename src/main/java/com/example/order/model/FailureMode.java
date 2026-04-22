package com.example.order.model;

/**
 * Controls which activity fails during a test run.
 * Passed via the FAILURE_MODE environment variable on the worker.
 */
public enum FailureMode {
    // Order pipeline scenarios
    NONE,
    INVALID_ORDER,
    PAYMENT_FAILURE,
    SHIPPING_FAILURE,

    // Workflow pattern scenarios (no failure injection)
    PARENT_CHILD,
    BATCH,

    // CSP change provisioning scenarios
    CSP_HAPPY_PATH,       // full success: HLR confirms OK
    CSP_VALIDATE_FAIL,    // validation rejects the request
    CSP_HLR_ERROR,        // HLR returns explicit error via signal
    CSP_HLR_TIMEOUT       // no HLR signal arrives within timeout
}
