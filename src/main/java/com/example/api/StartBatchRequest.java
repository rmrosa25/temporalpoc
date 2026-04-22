package com.example.api;

import java.util.List;

/**
 * POST /api/orders/batch
 *
 * Each item in the list becomes one child OrderWorkflow.
 */
public class StartBatchRequest {

    private List<StartOrderRequest> orders;

    public List<StartOrderRequest> getOrders() { return orders; }
    public void setOrders(List<StartOrderRequest> v) { this.orders = v; }
}
