package com.example.order.model;

public class Order {
    private String orderId;
    private String customerId;
    private String item;
    private int quantity;
    private double totalAmount;

    public Order() {}

    public Order(String orderId, String customerId, String item, int quantity, double totalAmount) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.item = item;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    @Override
    public String toString() {
        return String.format("Order{id='%s', customer='%s', item='%s', qty=%d, amount=%.2f}",
                orderId, customerId, item, quantity, totalAmount);
    }
}
