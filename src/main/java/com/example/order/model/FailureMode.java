package com.example.order.model;

/**
 * Controls which activity fails during a test run.
 * Passed via the FAILURE_MODE environment variable on the worker.
 */
public enum FailureMode {
    NONE,
    INVALID_ORDER,    // validation rejects before any mutation
    PAYMENT_FAILURE,  // payment fails → release inventory
    SHIPPING_FAILURE, // shipping fails → refund payment + release inventory
    PARENT_CHILD,     // no failure — exercises parent/child workflow pattern
    BATCH             // no failure — exercises batch fan-out of 10 child workflows
}
