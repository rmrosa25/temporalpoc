package com.example.order.model;

/**
 * Controls which activity fails during a test run.
 * Passed via the FAILURE_MODE environment variable.
 */
public enum FailureMode {
    NONE,
    INVALID_ORDER,    // bad input — validation rejects before any mutation
    PAYMENT_FAILURE,  // payment gateway error → release inventory
    SHIPPING_FAILURE  // shipping error → refund payment + release inventory
}
