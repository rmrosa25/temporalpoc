package com.example.order.activity;

import com.example.order.model.FailureMode;
import com.example.order.model.Order;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Simulated activity implementations.
 *
 * Failure injection is controlled by the FAILURE_MODE environment variable:
 *   NONE             — all activities succeed (default)
 *   INVALID_ORDER    — validation rejects the order before any mutation
 *   PAYMENT_FAILURE  — payment gateway throws after inventory is reserved
 *   SHIPPING_FAILURE — shipping throws after payment is charged
 */
public class OrderActivitiesImpl implements OrderActivities {

    private static final Logger log = LoggerFactory.getLogger(OrderActivitiesImpl.class);

    private static final FailureMode FAILURE_MODE = FailureMode.valueOf(
            System.getenv().getOrDefault("FAILURE_MODE", "NONE").toUpperCase()
    );

    @Override
    public void validateOrder(Order order) {
        log.info("[{}] Validating order: item={}, qty={}, amount={}",
                order.getOrderId(), order.getItem(), order.getQuantity(), order.getTotalAmount());

        if (order.getQuantity() <= 0) {
            throw Activity.wrap(new IllegalArgumentException(
                    "Quantity must be positive, got: " + order.getQuantity()));
        }
        if (order.getTotalAmount() <= 0) {
            throw Activity.wrap(new IllegalArgumentException(
                    "Amount must be positive, got: " + order.getTotalAmount()));
        }
        if (FAILURE_MODE == FailureMode.INVALID_ORDER) {
            log.warn("[{}] Validation rejected order (simulated)", order.getOrderId());
            throw Activity.wrap(new IllegalArgumentException(
                    "Item '" + order.getItem() + "' is not available in the catalog"));
        }

        sleep(300);
        log.info("[{}] Order validated", order.getOrderId());
    }

    @Override
    public String reserveInventory(Order order) {
        log.info("[{}] Reserving {} units of '{}'",
                order.getOrderId(), order.getQuantity(), order.getItem());
        sleep(500);
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] Inventory reserved: {}", order.getOrderId(), reservationId);
        return reservationId;
    }

    @Override
    public void releaseInventory(String reservationId) {
        log.info("[COMPENSATION] Releasing inventory reservation: {}", reservationId);
        sleep(300);
        log.info("[COMPENSATION] Inventory released: {}", reservationId);
    }

    @Override
    public String chargePayment(Order order) {
        log.info("[{}] Charging ${} for customer '{}'",
                order.getOrderId(), order.getTotalAmount(), order.getCustomerId());
        sleep(700);

        if (FAILURE_MODE == FailureMode.PAYMENT_FAILURE) {
            log.warn("[{}] Payment gateway rejected the charge (simulated)", order.getOrderId());
            throw Activity.wrap(new RuntimeException(
                    "Payment gateway error: card declined for customer " + order.getCustomerId()));
        }

        String chargeId = "CHG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] Payment charged: {}", order.getOrderId(), chargeId);
        return chargeId;
    }

    @Override
    public void refundPayment(String chargeId) {
        log.info("[COMPENSATION] Refunding charge: {}", chargeId);
        sleep(400);
        log.info("[COMPENSATION] Refund issued for charge: {}", chargeId);
    }

    @Override
    public String shipOrder(Order order) {
        log.info("[{}] Shipping order to customer '{}'",
                order.getOrderId(), order.getCustomerId());
        sleep(800);

        if (FAILURE_MODE == FailureMode.SHIPPING_FAILURE) {
            log.warn("[{}] Shipping provider unavailable (simulated)", order.getOrderId());
            throw Activity.wrap(new RuntimeException(
                    "Shipping provider error: no carrier available for region"));
        }

        String trackingId = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[{}] Order shipped: tracking={}", order.getOrderId(), trackingId);
        return trackingId;
    }

    @Override
    public void sendConfirmationEmail(Order order, String trackingId) {
        log.info("[{}] Sending confirmation email to customer '{}', tracking={}",
                order.getOrderId(), order.getCustomerId(), trackingId);
        sleep(200);
        log.info("[{}] Confirmation email sent", order.getOrderId());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
