package com.example.provisioning.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process HLR message bus backed by two {@link LinkedBlockingQueue}s.
 *
 * Used when no external Kafka broker is available (e.g. Gitpod / CI without Docker).
 * Provides the same publish/poll contract as {@link KafkaHlrBus} but entirely
 * in-memory within a single JVM.
 *
 * Thread-safe. Multiple producers and consumers are supported.
 */
public class InProcessHlrBus implements HlrBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessHlrBus.class);

    private final LinkedBlockingQueue<HlrCommandMessage>      commandQueue      = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<HlrConfirmationMessage> confirmationQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public long publishCommand(HlrCommandMessage message) {
        commandQueue.add(message);
        long seq = sequence.incrementAndGet();
        log.info("[Bus] Command enqueued: correlationId={}, iccid={}, seq={}",
                message.getCorrelationId(), message.getIccid(), seq);
        return seq;
    }

    @Override
    public void publishConfirmation(HlrConfirmationMessage message) {
        confirmationQueue.add(message);
        log.info("[Bus] Confirmation enqueued: correlationId={}, success={}",
                message.getCorrelationId(), message.isSuccess());
    }

    @Override
    public HlrCommandMessage pollCommand(long timeoutMs) throws InterruptedException {
        return commandQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public HlrConfirmationMessage pollConfirmation(long timeoutMs) throws InterruptedException {
        return confirmationQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        log.info("[Bus] InProcessHlrBus closed (commands remaining={}, confirmations remaining={})",
                commandQueue.size(), confirmationQueue.size());
    }
}
