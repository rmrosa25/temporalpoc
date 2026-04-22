package com.example.provisioning.kafka;

import com.example.provisioning.model.ElementConfirmation;
import com.example.provisioning.workflow.CspChangeWorkflow;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the {@link HlrBus} for ElementConfirmationMessages and delivers each
 * as an elementConfirmationReceived signal to the corresponding CspChangeWorkflow.
 *
 * One signal is sent per NetworkElement confirmation. The workflow tracks
 * received confirmations by elementId and unblocks when all are received.
 *
 * Runs in a dedicated background thread started by Worker / ScenarioRunner.
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
                ElementConfirmationMessage message = bus.pollConfirmation(500);
                if (message != null) {
                    dispatch(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error dispatching element confirmation", e);
                }
            }
        }
        log.info("HlrConfirmationDispatcher stopped");
    }

    private void dispatch(ElementConfirmationMessage message) {
        log.info("[{}] Dispatching element confirmation to workflow {}: elementId={}, type={}, success={}",
                message.getCorrelationId(), message.getWorkflowId(),
                message.getElementId(), message.getElementType(), message.isSuccess());

        ElementConfirmation confirmation = message.isSuccess()
                ? ElementConfirmation.ok(
                        message.getCorrelationId(),
                        message.getElementId(),
                        message.getElementType(),
                        message.getAppliedProfile())
                : ElementConfirmation.error(
                        message.getCorrelationId(),
                        message.getElementId(),
                        message.getElementType(),
                        message.getErrorCode(),
                        message.getErrorMessage());

        CspChangeWorkflow stub = temporalClient.newWorkflowStub(
                CspChangeWorkflow.class, message.getWorkflowId());
        stub.elementConfirmationReceived(confirmation);

        log.info("[{}] Signal elementConfirmationReceived delivered: elementId={}",
                message.getCorrelationId(), message.getElementId());
    }

    @Override
    public void close() {
        log.info("Shutting down HlrConfirmationDispatcher...");
        running.set(false);
    }
}
