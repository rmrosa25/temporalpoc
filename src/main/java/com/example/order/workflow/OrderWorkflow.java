package com.example.order.workflow;

import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Orchestrates the full order lifecycle:
 * validate → reserve inventory → charge payment → ship → (compensate on failure)
 */
@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(Order order);
}
