package com.example;

import com.example.order.activity.OrderActivitiesImpl;
import com.example.order.workflow.BatchOrderWorkflowImpl;
import com.example.order.workflow.FulfillmentWorkflowImpl;
import com.example.order.workflow.OrderWorkflowImpl;
import com.example.provisioning.activity.CspChangeActivitiesImpl;
import com.example.provisioning.kafka.HlrBusFactory;
import com.example.provisioning.kafka.HlrConfirmationDispatcher;
import com.example.provisioning.workflow.CspChangeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers all workflow and activity implementations and starts polling
 * the "order-processing" task queue.
 *
 * Also starts the HlrConfirmationDispatcher in a background thread.
 * The dispatcher reads from the HlrBus (Kafka or in-process) and delivers
 * each confirmation as a Temporal signal to the corresponding CspChangeWorkflow.
 *
 * Registered workflows:
 *   - OrderWorkflow        — single order pipeline (saga pattern)
 *   - FulfillmentWorkflow  — parent/child: primary + secondary order
 *   - BatchOrderWorkflow   — fan-out: N parallel child OrderWorkflows
 *   - CspChangeWorkflow    — network provisioning: bus + signal-driven HLR change
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
        worker.registerWorkflowImplementationTypes(
                OrderWorkflowImpl.class,
                FulfillmentWorkflowImpl.class,
                BatchOrderWorkflowImpl.class,
                CspChangeWorkflowImpl.class
        );
        worker.registerActivitiesImplementations(
                new OrderActivitiesImpl(),
                new CspChangeActivitiesImpl()
        );

        // Start HLR confirmation dispatcher in a background daemon thread.
        // It polls the bus and signals the waiting CspChangeWorkflow.
        HlrConfirmationDispatcher dispatcher = new HlrConfirmationDispatcher(
                HlrBusFactory.get(), client);
        Thread dispatcherThread = new Thread(dispatcher, "hlr-confirmation-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        log.info("HlrConfirmationDispatcher started on background thread");

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            dispatcher.close();
            factory.shutdown();
            HlrBusFactory.close();
        }));

        log.info("Worker started. Polling task queue: {}", TASK_QUEUE);
        factory.start();
    }
}
