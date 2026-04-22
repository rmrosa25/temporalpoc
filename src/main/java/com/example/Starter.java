package com.example;

import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import com.example.order.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Submits a sample order workflow to Temporal and waits for the result.
 * Run with FAIL_AT_PAYMENT=true to observe saga compensation.
 */
public class Starter {

    private static final Logger log = LoggerFactory.getLogger(Starter.class);

    public static void main(String[] args) {
        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "localhost:7233");
        log.info("Connecting to Temporal at {}", temporalHost);

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build()
        );

        WorkflowClient client = WorkflowClient.newInstance(service);

        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order order = new Order(orderId, "customer-42", "Laptop Pro X", 2, 2499.99);

        log.info("Submitting order: {}", order);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(orderId)
                .setTaskQueue(Worker.TASK_QUEUE)
                .build();

        OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);
        OrderResult result = workflow.processOrder(order);

        log.info("=== Workflow completed ===");
        log.info("Result: {}", result);

        System.exit(0);
    }
}
