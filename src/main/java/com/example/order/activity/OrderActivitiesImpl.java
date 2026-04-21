package com.example.order.activity;

import com.example.order.model.Order;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Simulated implementations. In production each method would call an external service.
 * Set FAIL_AT_PAYMENT=true env var to trigger saga compensation.
 */
public class OrderActivitiesImpl implements OrderActivities {

    private static final Logger log = LoggerFactory.getLogger(OrderActivitiesImpl.class);

    // Toggle to simulate a payment failure and trigger saga rollback
    private static final boolean FAIL_AT_PAYMENT =
            Boolean.parseBoolean(System.getenv().getOrDefault("FAIL_AT_PAYMENT", "false"));

    @Override
    public void validateOrder(Order order) {
        log.info("[{}] Validating order: item={}, qty={}, amount={}",
                order.getOrderId(), order.getItem(), order.getQuantity(), order.getTotalAmount());

        if (order.getQuantity() <= 0) {
            throw Activity.wrap(new IllegalArgumentException("Quantity must be positive"));
        }
        if (order.getTotalAmount() <= 0) {
            throw Activity.wrap(new IllegalArgumentException("Amount must be positive"));
        }

        sleep(300);
        log.info("[{}] Order validated", order.getOrderId());
    }

    @Override
    public String reserveInventory(Order order) {
        log.info("[{}] Reserving {} units of '{}'", order.getOrderId(), order.getQuantity(), order.getItem());
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
        log.info("[{}] Charging ${} for customer '{}'", order.getOrderId(), order.getTotalAmount(), order.getCustomerId());
        sleep(700);

        if (FAIL_AT_PAYMENT) {
            log.warn("[{}] Payment gateway rejected the charge (simulated failure)", order.getOrderId());
            throw Activity.wrap(new RuntimeException("Payment gateway error: card declined"));
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
        log.info("[{}] Shipping order to customer '{}'", order.getOrderId(), order.getCustomerId());
        sleep(800);
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
