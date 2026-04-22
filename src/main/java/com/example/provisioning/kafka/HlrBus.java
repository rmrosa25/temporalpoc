package com.example.provisioning.kafka;

/**
 * Abstraction over the provisioning command/confirmation message channel.
 *
 * Two implementations:
 *   - KafkaHlrBus     — real Kafka (used when KAFKA_BOOTSTRAP_SERVERS is set)
 *   - InProcessHlrBus — BlockingQueue (used when no external Kafka is available)
 *
 * Flow:
 *   publishCommand(ProvisioningCommandMessage)   → provisioning-commands topic
 *   pollCommand()                                ← consumed by ProvisioningSimulator
 *   publishConfirmation(ElementConfirmationMessage) → provisioning-confirmations topic
 *   pollConfirmation()                           ← consumed by HlrConfirmationDispatcher
 *                                                   → signals CspChangeWorkflow
 */
public interface HlrBus {

    /**
     * Publishes a provisioning command for one network element.
     * Returns an opaque offset/sequence number for logging.
     */
    long publishCommand(ProvisioningCommandMessage message);

    /**
     * Publishes a confirmation from a network element (used by ProvisioningSimulator).
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
