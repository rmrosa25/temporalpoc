package com.example.provisioning.kafka;

/**
 * Abstraction over the HLR command/confirmation message channel.
 *
 * Two implementations:
 *   - KafkaHlrBus      — real Kafka (used when KAFKA_BOOTSTRAP_SERVERS is set)
 *   - InProcessHlrBus  — BlockingQueue (used when no external Kafka is available)
 *
 * The bus carries messages in one direction:
 *   publish(HlrCommandMessage)       → hlr-commands channel
 *   subscribe(HlrConfirmationListener) → hlr-confirmations channel
 *
 * KafkaSimulator also uses the bus to read commands and write confirmations.
 */
public interface HlrBus {

    /**
     * Publishes an HLR command. Blocks until the message is durably enqueued.
     * Returns an opaque offset/sequence number for logging.
     */
    long publishCommand(HlrCommandMessage message);

    /**
     * Publishes an HLR confirmation (used by KafkaSimulator / HLR gateway).
     */
    void publishConfirmation(HlrConfirmationMessage message);

    /**
     * Reads the next HLR command. Blocks up to {@code timeoutMs} milliseconds.
     * Returns null if no message arrives within the timeout.
     */
    HlrCommandMessage pollCommand(long timeoutMs) throws InterruptedException;

    /**
     * Reads the next HLR confirmation. Blocks up to {@code timeoutMs} milliseconds.
     * Returns null if no message arrives within the timeout.
     */
    HlrConfirmationMessage pollConfirmation(long timeoutMs) throws InterruptedException;

    /** Releases resources. */
    void close();
}
