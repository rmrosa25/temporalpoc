package com.example.order.model;

public class OrderResult {
    private String orderId;
    private String status;
    private String message;

    public OrderResult() {}

    public OrderResult(String orderId, String status, String message) {
        this.orderId = orderId;
        this.status = status;
        this.message = message;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        return String.format("OrderResult{orderId='%s', status='%s', message='%s'}",
                orderId, status, message);
    }
}
