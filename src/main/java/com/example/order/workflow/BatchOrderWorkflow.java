package com.example.order.workflow;

import com.example.order.model.BatchResult;
import com.example.order.model.Order;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Spawns up to N child OrderWorkflows in parallel and aggregates results.
 */
@WorkflowInterface
public interface BatchOrderWorkflow {

    @WorkflowMethod
    BatchResult processBatch(List<Order> orders);
}
