package com.example.order.activity;

import com.example.order.model.Order;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Each method maps to one step in the order pipeline.
 * Compensation methods (release/refund) are called by the saga on failure.
 */
@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    void validateOrder(Order order);

    @ActivityMethod
    String reserveInventory(Order order);

    @ActivityMethod
    void releaseInventory(String reservationId);

    @ActivityMethod
    String chargePayment(Order order);

    @ActivityMethod
    void refundPayment(String chargeId);

    @ActivityMethod
    String shipOrder(Order order);

    @ActivityMethod
    void sendConfirmationEmail(Order order, String trackingId);
}
