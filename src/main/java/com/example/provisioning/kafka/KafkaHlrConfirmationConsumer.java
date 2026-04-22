package com.example.provisioning.kafka;

import com.example.provisioning.model.HlrConfirmation;
import com.example.provisioning.workflow.CspChangeWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka consumer that reads HLR confirmation messages and delivers them
 * to the corresponding Temporal workflow as a signal.
 *
 * This is the bridge between the async Kafka world and Temporal's durable
 * workflow execution. Each consumed message:
 *   1. Is deserialized from JSON into HlrConfirmationMessage
 *   2. Mapped to an HlrConfirmation (Temporal model)
 *   3. Delivered via WorkflowClient.newWorkflowStub(workflowId).hlrConfirmationReceived()
 *
 * The consumer runs in a dedicated background thread started by Worker.
 * It uses at-least-once delivery — Temporal's signal deduplication handles
 * any duplicates if the consumer crashes after delivery but before commit.
 *
 * In production the HLR gateway publishes to hlr-confirmations after applying
 * the profile change in the network. In tests, KafkaSimulator plays this role.
 */
public class KafkaHlrConfirmationConsumer implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaHlrConfirmationConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaConsumer<String, String> consumer;
    private final WorkflowClient temporalClient;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaHlrConfirmationConsumer(WorkflowClient temporalClient) {
        this(temporalClient, KafkaConfig.bootstrapServers());
    }

    public KafkaHlrConfirmationConsumer(WorkflowClient temporalClient, String bootstrapServers) {
        this.temporalClient = temporalClient;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, KafkaConfig.HLR_CONSUMER_GROUP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Commit only after successful signal delivery
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "hlr-confirmation-consumer");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

        this.consumer = new KafkaConsumer<>(props);
        log.info("KafkaHlrConfirmationConsumer initialized: bootstrapServers={}", bootstrapServers);
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Collections.singletonList(KafkaConfig.HLR_CONFIRMATIONS_TOPIC));
            log.info("Subscribed to topic: {}", KafkaConfig.HLR_CONFIRMATIONS_TOPIC);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record);
                }

                if (!records.isEmpty()) {
                    // Commit after all records in the batch are signalled
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            // Expected on shutdown — ignore
            if (running.get()) {
                log.error("Unexpected WakeupException", e);
            }
        } catch (Exception e) {
            log.error("Fatal error in HLR confirmation consumer", e);
        } finally {
            consumer.close();
            log.info("KafkaHlrConfirmationConsumer stopped");
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        log.info("Consumed HLR confirmation: topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            HlrConfirmationMessage message = MAPPER.readValue(record.value(), HlrConfirmationMessage.class);
            log.info("Parsed confirmation: {}", message);

            // Build the Temporal signal payload
            HlrConfirmation confirmation = message.isSuccess()
                    ? HlrConfirmation.ok(message.getCorrelationId(), message.getAppliedCsp())
                    : HlrConfirmation.error(message.getCorrelationId(),
                            message.getErrorCode(), message.getErrorMessage());

            // Signal the waiting workflow by its workflow ID
            CspChangeWorkflow workflowStub = temporalClient.newWorkflowStub(
                    CspChangeWorkflow.class, message.getWorkflowId());

            log.info("[{}] Sending hlrConfirmationReceived signal to workflow: {}",
                    message.getCorrelationId(), message.getWorkflowId());

            workflowStub.hlrConfirmationReceived(confirmation);

            log.info("[{}] Signal delivered successfully", message.getCorrelationId());

        } catch (Exception e) {
            // Log and continue — do not crash the consumer loop.
            // The offset will not be committed for this batch, so the message
            // will be redelivered on the next poll.
            log.error("Failed to process HLR confirmation record at offset={}: {}",
                    record.offset(), e.getMessage(), e);
            throw new RuntimeException(e); // re-throw to prevent commit
        }
    }

    @Override
    public void close() {
        log.info("Shutting down KafkaHlrConfirmationConsumer...");
        running.set(false);
        consumer.wakeup(); // unblocks poll()
    }
}
