package com.example.provisioning.kafka;

import com.example.provisioning.model.HlrConfirmation;
import com.example.provisioning.workflow.CspChangeWorkflow;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the {@link HlrBus} for HLR confirmation messages and delivers each
 * one as a Temporal signal to the corresponding {@link CspChangeWorkflow}.
 *
 * This replaces the former {@link KafkaHlrConfirmationConsumer} and works with
 * both the Kafka-backed and in-process bus implementations.
 *
 * Runs in a dedicated background thread started by Worker.
 */
public class HlrConfirmationDispatcher implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HlrConfirmationDispatcher.class);

    private final HlrBus bus;
    private final WorkflowClient temporalClient;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public HlrConfirmationDispatcher(HlrBus bus, WorkflowClient temporalClient) {
        this.bus = bus;
        this.temporalClient = temporalClient;
    }

    @Override
    public void run() {
        log.info("HlrConfirmationDispatcher started");
        while (running.get()) {
            try {
                HlrConfirmationMessage message = bus.pollConfirmation(500);
                if (message != null) {
                    dispatch(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error dispatching HLR confirmation", e);
                }
            }
        }
        log.info("HlrConfirmationDispatcher stopped");
    }

    private void dispatch(HlrConfirmationMessage message) {
        log.info("[{}] Dispatching HLR confirmation to workflow: {}",
                message.getCorrelationId(), message.getWorkflowId());

        HlrConfirmation confirmation = message.isSuccess()
                ? HlrConfirmation.ok(message.getCorrelationId(), message.getAppliedCsp())
                : HlrConfirmation.error(message.getCorrelationId(),
                        message.getErrorCode(), message.getErrorMessage());

        CspChangeWorkflow stub = temporalClient.newWorkflowStub(
                CspChangeWorkflow.class, message.getWorkflowId());
        stub.hlrConfirmationReceived(confirmation);

        log.info("[{}] Signal delivered", message.getCorrelationId());
    }

    @Override
    public void close() {
        log.info("Shutting down HlrConfirmationDispatcher...");
        running.set(false);
    }
}
