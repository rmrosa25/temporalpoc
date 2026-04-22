package com.example.provisioning.kafka;

/**
 * Message channel for provisioning commands and element confirmations.
 *
 * Current implementation: {@link InProcessHlrBus} (in-memory, single JVM).
 * In production this would be backed by Kafka topics:
 *   provisioning-commands      ← one ProvisioningCommandMessage per NetworkElement
 *   provisioning-confirmations ← one ElementConfirmationMessage per NetworkElement
 */
public interface HlrBus {

    /**
     * Publishes a provisioning command for one network element.
     * Returns an opaque sequence number for logging.
     */
    long publishCommand(ProvisioningCommandMessage message);

    /**
     * Publishes a confirmation from a network element (used by KafkaSimulator).
     */
    void publishConfirmation(ElementConfirmationMessage message);

    /**
     * Reads the next provisioning command. Blocks up to {@code timeoutMs} ms.
     * Returns null on timeout.
     */
    ProvisioningCommandMessage pollCommand(long timeoutMs) throws InterruptedException;

    /**
     * Reads the next element confirmation. Blocks up to {@code timeoutMs} ms.
     * Returns null on timeout.
     */
    ElementConfirmationMessage pollConfirmation(long timeoutMs) throws InterruptedException;

    void close();
}
