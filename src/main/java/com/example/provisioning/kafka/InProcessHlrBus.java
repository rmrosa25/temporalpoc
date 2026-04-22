package com.example.provisioning.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process HLR/provisioning message bus backed by two {@link LinkedBlockingQueue}s.
 *
 * Used when no external Kafka broker is available (e.g. Gitpod / CI without Docker).
 * Thread-safe. Multiple producers and consumers are supported.
 */
public class InProcessHlrBus implements HlrBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessHlrBus.class);

    private final LinkedBlockingQueue<ProvisioningCommandMessage>  commandQueue      = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<ElementConfirmationMessage>  confirmationQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public long publishCommand(ProvisioningCommandMessage message) {
        commandQueue.add(message);
        long seq = sequence.incrementAndGet();
        log.info("[Bus] Command enqueued: correlationId={}, elementId={}, type={}, seq={}",
                message.getCorrelationId(), message.getElementId(), message.getElementType(), seq);
        return seq;
    }

    @Override
    public void publishConfirmation(ElementConfirmationMessage message) {
        confirmationQueue.add(message);
        log.info("[Bus] Confirmation enqueued: correlationId={}, elementId={}, success={}",
                message.getCorrelationId(), message.getElementId(), message.isSuccess());
    }

    @Override
    public ProvisioningCommandMessage pollCommand(long timeoutMs) throws InterruptedException {
        return commandQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public ElementConfirmationMessage pollConfirmation(long timeoutMs) throws InterruptedException {
        return confirmationQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        log.info("[Bus] InProcessHlrBus closed (commands remaining={}, confirmations remaining={})",
                commandQueue.size(), confirmationQueue.size());
    }
}
