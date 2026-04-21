package com.example.order.workflow;

import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Parent workflow that fulfills an order by spawning two child OrderWorkflows:
 *   - primary:   the main item order
 *   - secondary: a gift-wrap add-on order
 *
 * Both children run sequentially. The parent aggregates their results.
 */
@WorkflowInterface
public interface FulfillmentWorkflow {

    @WorkflowMethod
    List<OrderResult> fulfill(Order primary, Order secondary);
}
