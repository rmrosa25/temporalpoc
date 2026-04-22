package com.example.api;

import com.example.order.model.BatchResult;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import com.example.order.workflow.BatchOrderWorkflow;
import com.example.order.workflow.FulfillmentWorkflow;
import com.example.order.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST endpoints for order pipeline workflows.
 *
 * All endpoints accept async=true (default) which starts the workflow and
 * returns immediately with the workflowId. Set async=false to block until
 * the workflow completes and return the full result.
 *
 * POST /api/orders                — single order (OrderWorkflow)
 * POST /api/orders/fulfillment    — parent + 2 children (FulfillmentWorkflow)
 * POST /api/orders/batch          — fan-out N children (BatchOrderWorkflow)
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final WorkflowClient temporalClient;
    private final String taskQueue;

    public OrderController(WorkflowClient temporalClient,
                           @org.springframework.beans.factory.annotation.Value("${temporal.task-queue}")
                           String taskQueue) {
        this.temporalClient = temporalClient;
        this.taskQueue = taskQueue;
    }

    // ── POST /api/orders ──────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<WorkflowResponse> startOrder(
            @RequestBody StartOrderRequest req,
            @RequestParam(defaultValue = "true") boolean async) {

        String workflowId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order order = new Order(workflowId, req.getCustomerId(),
                req.getItem(), req.getQuantity(), req.getPrice());

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build();

        OrderWorkflow wf = temporalClient.newWorkflowStub(OrderWorkflow.class, opts);

        if (async) {
            WorkflowClient.start(wf::processOrder, order);
            return ResponseEntity.accepted().body(WorkflowResponse.started(workflowId));
        }

        OrderResult result = wf.processOrder(order);
        return ResponseEntity.ok(WorkflowResponse.completed(workflowId, result));
    }

    // ── POST /api/orders/fulfillment ──────────────────────────────────────────

    @PostMapping("/fulfillment")
    public ResponseEntity<WorkflowResponse> startFulfillment(
            @RequestBody StartOrderRequest req,
            @RequestParam(defaultValue = "true") boolean async) {

        String parentId = "FULFILL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order primary = new Order(
                "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                req.getCustomerId(), req.getItem(), req.getQuantity(), req.getPrice());
        Order secondary = new Order(
                "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                req.getCustomerId(),
                req.getSecondaryItem() != null ? req.getSecondaryItem() : "Gift Wrap",
                req.getSecondaryQuantity() > 0 ? req.getSecondaryQuantity() : 1,
                req.getSecondaryPrice() > 0 ? req.getSecondaryPrice() : 0.0);

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(parentId)
                .setTaskQueue(taskQueue)
                .build();

        FulfillmentWorkflow wf = temporalClient.newWorkflowStub(FulfillmentWorkflow.class, opts);

        if (async) {
            WorkflowClient.start(wf::fulfill, primary, secondary);
            return ResponseEntity.accepted().body(WorkflowResponse.started(parentId));
        }

        List<OrderResult> results = wf.fulfill(primary, secondary);
        return ResponseEntity.ok(WorkflowResponse.completed(parentId, results));
    }

    // ── POST /api/orders/batch ────────────────────────────────────────────────

    @PostMapping("/batch")
    public ResponseEntity<WorkflowResponse> startBatch(
            @RequestBody StartBatchRequest req,
            @RequestParam(defaultValue = "true") boolean async) {

        String batchId = "BATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<Order> orders = req.getOrders().stream()
                .map(o -> new Order(
                        "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                        o.getCustomerId(), o.getItem(), o.getQuantity(), o.getPrice()))
                .collect(Collectors.toList());

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setWorkflowId(batchId)
                .setTaskQueue(taskQueue)
                .build();

        BatchOrderWorkflow wf = temporalClient.newWorkflowStub(BatchOrderWorkflow.class, opts);

        if (async) {
            WorkflowClient.start(wf::processBatch, orders);
            return ResponseEntity.accepted().body(WorkflowResponse.started(batchId));
        }

        BatchResult result = wf.processBatch(orders);
        return ResponseEntity.ok(WorkflowResponse.completed(batchId, result));
    }
}
