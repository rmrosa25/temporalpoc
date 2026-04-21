package com.example.order.workflow;

import com.example.order.model.BatchResult;
import com.example.order.model.Order;
import com.example.order.model.OrderResult;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch workflow implementation.
 *
 * Fans out all orders as parallel child workflows using Async.function(),
 * then waits for all of them with Promise.allOf() before aggregating results.
 *
 * Each child has its own event history and is independently retryable.
 * A single child failure does not cancel the others — all run to completion
 * and the batch result reflects the individual outcomes.
 */
public class BatchOrderWorkflowImpl implements BatchOrderWorkflow {

    private static final Logger log = Workflow.getLogger(BatchOrderWorkflowImpl.class);

    @Override
    public BatchResult processBatch(List<Order> orders) {
        String batchId = Workflow.getInfo().getWorkflowId();
        log.info("[{}] Starting batch of {} orders", batchId, orders.size());

        // Fan out: start all child workflows in parallel
        List<Promise<OrderResult>> promises = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            OrderWorkflow child = Workflow.newChildWorkflowStub(
                    OrderWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(batchId + "/order-" + (i + 1))
                            .build()
            );
            log.info("[{}] Spawning child {}/{}: orderId={}",
                    batchId, i + 1, orders.size(), order.getOrderId());
            // Async.function starts the child without blocking
            promises.add(Async.function(child::processOrder, order));
        }

        // Wait for all children to finish
        Promise.allOf(promises).get();

        // Collect results
        List<OrderResult> results = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        for (Promise<OrderResult> p : promises) {
            OrderResult result = p.get();
            results.add(result);
            if ("COMPLETED".equals(result.getStatus())) {
                completed++;
            } else {
                failed++;
            }
        }

        log.info("[{}] Batch complete — completed={}, failed={}", batchId, completed, failed);
        return new BatchResult(batchId, orders.size(), completed, failed, results);
    }
}
