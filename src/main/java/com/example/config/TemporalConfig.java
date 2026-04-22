package com.example.config;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Wires Temporal into the Spring context.
 *
 * Beans:
 *   WorkflowClient  — injected into controllers to start workflows and send signals
 *   WorkerFactory   — starts polling the task queue on application startup
 *
 * Also starts the HlrConfirmationDispatcher background thread which reads
 * element confirmations from the bus and delivers them as Temporal signals.
 */
@Configuration
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    @Value("${temporal.host}")
    private String temporalHost;

    @Value("${temporal.task-queue}")
    private String taskQueue;

    private WorkerFactory workerFactory;
    private HlrConfirmationDispatcher dispatcher;
    private Thread dispatcherThread;

    @Bean
    public WorkflowClient workflowClient() {
        log.info("Connecting to Temporal at {}", temporalHost);
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build()
        );
        return WorkflowClient.newInstance(service);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        workerFactory = WorkerFactory.newInstance(client);

        io.temporal.worker.Worker worker = workerFactory.newWorker(taskQueue);
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

        // Start HLR confirmation dispatcher
        dispatcher = new HlrConfirmationDispatcher(HlrBusFactory.get(), client);
        dispatcherThread = new Thread(dispatcher, "hlr-confirmation-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        log.info("HlrConfirmationDispatcher started");

        workerFactory.start();
        log.info("Temporal worker started — polling task queue: {}", taskQueue);

        return workerFactory;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Temporal worker...");
        if (dispatcher != null) dispatcher.close();
        if (workerFactory != null) workerFactory.shutdown();
        HlrBusFactory.close();
    }
}
