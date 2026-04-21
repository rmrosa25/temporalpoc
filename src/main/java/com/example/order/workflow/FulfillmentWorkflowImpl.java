package com.example.order.workflow;

import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * Parent workflow implementation.
 *
 * Spawns two child OrderWorkflows sequentially:
 *   1. Primary order  — the main item
 *   2. Secondary order — gift-wrap add-on (only runs if primary succeeds)
 *
 * Each child runs as an independent Temporal workflow with its own event
 * history, retry policy, and visibility in the UI. The parent workflow
 * ID is used as a namespace prefix for child IDs so they are easy to
 * correlate in the Temporal Web UI.
 */
public class FulfillmentWorkflowImpl implements FulfillmentWorkflow {

    private static final Logger log = Workflow.getLogger(FulfillmentWorkflowImpl.class);

    @Override
    public List<OrderResult> fulfill(Order primary, Order secondary) {
        String parentId = Workflow.getInfo().getWorkflowId();
        log.info("[{}] Starting fulfillment: primary={}, secondary={}",
                parentId, primary.getOrderId(), secondary.getOrderId());

        // ── Child 1: primary order ────────────────────────────────────────────
        OrderWorkflow primaryChild = Workflow.newChildWorkflowStub(
                OrderWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(parentId + "/primary")
                        .build()
        );
        log.info("[{}] Spawning primary child workflow", parentId);
        OrderResult primaryResult = primaryChild.processOrder(primary);
        log.info("[{}] Primary child completed: status={}", parentId, primaryResult.getStatus());

        // ── Child 2: secondary order (gift wrap) ──────────────────────────────
        // Only proceed with gift wrap if the primary order succeeded.
        OrderResult secondaryResult;
        if ("COMPLETED".equals(primaryResult.getStatus())) {
            OrderWorkflow secondaryChild = Workflow.newChildWorkflowStub(
                    OrderWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(parentId + "/secondary")
                            .build()
            );
            log.info("[{}] Spawning secondary child workflow (gift wrap)", parentId);
            secondaryResult = secondaryChild.processOrder(secondary);
            log.info("[{}] Secondary child completed: status={}", parentId, secondaryResult.getStatus());
        } else {
            log.warn("[{}] Skipping secondary order — primary failed", parentId);
            secondaryResult = new OrderResult(
                    secondary.getOrderId(), "SKIPPED",
                    "Skipped: primary order did not complete");
        }

        log.info("[{}] Fulfillment complete. primary={}, secondary={}",
                parentId, primaryResult.getStatus(), secondaryResult.getStatus());

        return Arrays.asList(primaryResult, secondaryResult);
    }
}
