package com.example.provisioning.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates the HLR gateway in test scenarios.
 *
 * Reads HLR commands from the bus, waits a configurable delay, then publishes
 * a response. The response type is controlled by {@link Mode}:
 *
 *   SUCCESS (default) → publishes a success confirmation
 *   ERROR             → publishes an error confirmation
 *   TIMEOUT           → consumes the command but publishes nothing
 *
 * Works with both {@link InProcessHlrBus} and {@link KafkaHlrBus}.
 * In production this role is played by the real HLR gateway system.
 */
public class KafkaSimulator implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaSimulator.class);

    public enum Mode { SUCCESS, ERROR, TIMEOUT }

    private final Mode mode;
    private final long delayMs;
    private final HlrBus bus;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Uses the shared bus from {@link HlrBusFactory}. */
    public KafkaSimulator(Mode mode, long delayMs) {
        this.mode = mode;
        this.delayMs = delayMs;
        this.bus = HlrBusFactory.get();
        log.info("KafkaSimulator initialized: mode={}, delayMs={}", mode, delayMs);
    }

    @Override
    public void run() {
        log.info("[Simulator] Started in {} mode", mode);
        try {
            while (running.get()) {
                HlrCommandMessage command = bus.pollCommand(500);
                if (command != null) {
                    handleCommand(command);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                log.error("[Simulator] Error in simulator loop", e);
            }
        }
        log.info("[Simulator] Stopped");
    }

    private void handleCommand(HlrCommandMessage command) throws InterruptedException {
        log.info("[Simulator] Received HLR command: correlationId={}, iccid={}",
                command.getCorrelationId(), command.getIccid());

        Thread.sleep(delayMs);

        if (mode == Mode.TIMEOUT) {
            log.warn("[Simulator] TIMEOUT mode — not publishing confirmation for correlationId={}",
                    command.getCorrelationId());
            return;
        }

        HlrConfirmationMessage confirmation = (mode == Mode.SUCCESS)
                ? HlrConfirmationMessage.ok(
                        command.getCorrelationId(),
                        command.getWorkflowId(),
                        command.getTargetCsp())
                : HlrConfirmationMessage.error(
                        command.getCorrelationId(),
                        command.getWorkflowId(),
                        "HLR_PROFILE_REJECTED",
                        "Target CSP " + command.getTargetCsp() + " not provisioned for this IMSI");

        bus.publishConfirmation(confirmation);
        log.info("[Simulator] Published {} confirmation for correlationId={}",
                mode, command.getCorrelationId());
    }

    @Override
    public void close() {
        log.info("[Simulator] Closing...");
        running.set(false);
    }
}
