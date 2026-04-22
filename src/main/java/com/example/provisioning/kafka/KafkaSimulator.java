package com.example.provisioning.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates network element adapters in test scenarios.
 *
 * Reads ProvisioningCommandMessages from the bus (one per NetworkElement),
 * waits a configurable delay, then publishes an ElementConfirmationMessage
 * for each. The response type is controlled by {@link Mode}:
 *
 *   SUCCESS → all elements confirm success
 *   ERROR   → all elements return an error
 *   TIMEOUT → commands are consumed but no confirmations published
 *             (workflow times out waiting for signals)
 *
 * In production each network element has its own adapter that consumes
 * commands and publishes confirmations independently.
 */
public class KafkaSimulator implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaSimulator.class);

    public enum Mode { SUCCESS, ERROR, TIMEOUT }

    private final Mode mode;
    private final long delayMs;
    private final HlrBus bus;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaSimulator(Mode mode, long delayMs) {
        this.mode = mode;
        this.delayMs = delayMs;
        this.bus = HlrBusFactory.get();
        log.info("ProvisioningSimulator initialized: mode={}, delayMs={}", mode, delayMs);
    }

    @Override
    public void run() {
        log.info("[Simulator] Started in {} mode", mode);
        try {
            while (running.get()) {
                ProvisioningCommandMessage command = bus.pollCommand(500);
                if (command != null) {
                    handleCommand(command);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                log.error("[Simulator] Error in loop", e);
            }
        }
        log.info("[Simulator] Stopped");
    }

    private void handleCommand(ProvisioningCommandMessage command) throws InterruptedException {
        log.info("[Simulator] Received command: correlationId={}, elementId={}, type={}",
                command.getCorrelationId(), command.getElementId(), command.getElementType());

        Thread.sleep(delayMs);

        if (mode == Mode.TIMEOUT) {
            log.warn("[Simulator] TIMEOUT mode — not publishing confirmation for elementId={}",
                    command.getElementId());
            return;
        }

        ElementConfirmationMessage confirmation = (mode == Mode.SUCCESS)
                ? ElementConfirmationMessage.ok(
                        command.getCorrelationId(),
                        command.getWorkflowId(),
                        command.getElementId(),
                        command.getElementType(),
                        command.getTargetProfile())
                : ElementConfirmationMessage.error(
                        command.getCorrelationId(),
                        command.getWorkflowId(),
                        command.getElementId(),
                        command.getElementType(),
                        "ELEMENT_REJECTED",
                        "Element " + command.getElementId() + " rejected profile " + command.getTargetProfile());

        bus.publishConfirmation(confirmation);
        log.info("[Simulator] Published {} confirmation: elementId={}, type={}",
                mode, command.getElementId(), command.getElementType());
    }

    @Override
    public void close() {
        log.info("[Simulator] Closing...");
        running.set(false);
    }
}
