package com.example.api;

/**
 * POST /api/orders
 * POST /api/orders/fulfillment (primary + secondary item in one request)
 */
public class StartOrderRequest {

    private String customerId;
    private String item;
    private int quantity;
    private double price;

    // fulfillment only — optional secondary item
    private String secondaryItem;
    private int secondaryQuantity;
    private double secondaryPrice;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String v) { this.customerId = v; }

    public String getItem() { return item; }
    public void setItem(String v) { this.item = v; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }

    public double getPrice() { return price; }
    public void setPrice(double v) { this.price = v; }

    public String getSecondaryItem() { return secondaryItem; }
    public void setSecondaryItem(String v) { this.secondaryItem = v; }

    public int getSecondaryQuantity() { return secondaryQuantity; }
    public void setSecondaryQuantity(int v) { this.secondaryQuantity = v; }

    public double getSecondaryPrice() { return secondaryPrice; }
    public void setSecondaryPrice(double v) { this.secondaryPrice = v; }
}
