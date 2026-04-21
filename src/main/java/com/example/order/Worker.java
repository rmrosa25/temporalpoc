package com.example.order;

import com.example.order.activity.OrderActivitiesImpl;
import com.example.order.workflow.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the workflow and activity implementations with Temporal
 * and starts polling the "order-processing" task queue.
 */
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    public static final String TASK_QUEUE = "order-processing";

    public static void main(String[] args) {
        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "localhost:7233");
        log.info("Connecting to Temporal at {}", temporalHost);

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build()
        );

        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        io.temporal.worker.Worker worker = factory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(new OrderActivitiesImpl());

        log.info("Worker started. Polling task queue: {}", TASK_QUEUE);
        factory.start();
    }
}
