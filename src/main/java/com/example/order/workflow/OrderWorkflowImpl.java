package com.example.order.workflow;

import com.example.order.activity.OrderActivities;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Order processing pipeline using the Saga pattern for compensation.
 *
 * Steps (in order):
 *   1. Validate order
 *   2. Reserve inventory  → compensate: release inventory
 *   3. Charge payment     → compensate: refund payment
 *   4. Ship order
 *   5. Send confirmation email
 *
 * If any step fails after compensation points are registered, the saga
 * automatically runs compensations in reverse order.
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    private final OrderActivities activities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .build())
                    .build()
    );

    @Override
    public OrderResult processOrder(Order order) {
        log.info("Starting order pipeline for: {}", order);

        // Saga manages compensation stack — parallelCompensation=false ensures LIFO rollback
        Saga saga = new Saga(new Saga.Options.Builder()
                .setParallelCompensation(false)
                .build());

        try {
            // Step 1: Validate (no compensation needed — nothing was mutated yet)
            activities.validateOrder(order);

            // Step 2: Reserve inventory
            String reservationId = activities.reserveInventory(order);
            saga.addCompensation(activities::releaseInventory, reservationId);

            // Step 3: Charge payment
            String chargeId = activities.chargePayment(order);
            saga.addCompensation(activities::refundPayment, chargeId);

            // Step 4: Ship order
            String trackingId = activities.shipOrder(order);

            // Step 5: Confirmation email (best-effort, no compensation)
            activities.sendConfirmationEmail(order, trackingId);

            log.info("Order {} completed successfully. Tracking: {}", order.getOrderId(), trackingId);
            return new OrderResult(order.getOrderId(), "COMPLETED",
                    "Order shipped successfully. Tracking ID: " + trackingId);

        } catch (Exception e) {
            log.error("Order {} failed: {}. Running saga compensation...", order.getOrderId(), e.getMessage());
            saga.compensate();
            return new OrderResult(order.getOrderId(), "FAILED",
                    "Order failed and compensated: " + e.getMessage());
        }
    }
}
