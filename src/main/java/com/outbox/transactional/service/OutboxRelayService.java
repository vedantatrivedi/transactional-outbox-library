package com.outbox.transactional.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outbox.transactional.entity.OutboxMessage;
import com.outbox.transactional.metrics.OutboxMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service responsible for polling the outbox_messages table and publishing
 * messages to external systems (e.g., Kafka).
 * 
 * <p>This service runs as a scheduled task that periodically checks for
 * PENDING messages and attempts to publish them to the configured message broker.</p>
 * 
 * <p>Features:
 * <ul>
 *   <li>Scheduled polling of outbox messages</li>
 *   <li>Batch processing for efficiency</li>
 *   <li>Concurrency control to prevent duplicate processing</li>
 *   <li>Retry mechanism with exponential backoff</li>
 *   <li>Dead letter queue for permanently failed messages</li>
 *   <li>Comprehensive metrics and tracing</li>
 * </ul></p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Service
public class OutboxRelayService {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxRelayService.class);
    
    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private OutboxMetrics outboxMetrics;
    
    @Autowired(required = false)
    private Tracer tracer;
    
    @Value("${outbox.relay.batch-size:100}")
    private int batchSize;
    
    @Value("${outbox.relay.polling-interval:5000}")
    private long pollingIntervalMs;
    
    @Value("${outbox.relay.worker-id:${random.uuid}}")
    private String workerId;
    
    @Value("${outbox.relay.kafka.topic-prefix:outbox.events}")
    private String topicPrefix;
    
    @Value("${outbox.relay.kafka.retry-topic:outbox.retry}")
    private String retryTopic;
    
    @Value("${outbox.relay.kafka.dead-letter-topic:outbox.dead-letter}")
    private String deadLetterTopic;
    
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    
    /**
     * Scheduled task that polls for pending outbox messages and processes them.
     */
    @Scheduled(fixedDelayString = "${outbox.relay.polling-interval:5000}")
    @Transactional
    public void processPendingMessages() {
        Span span = null;
        if (tracer != null) {
            span = tracer.spanBuilder("outbox.relay.process")
                    .setAttribute("worker.id", workerId)
                    .startSpan();
        }
        
        try {
            outboxMetrics.incrementRelayPolling();
            
            // Fetch pending messages in batches
            List<OutboxMessage> pendingMessages = fetchPendingMessages();
            
            if (pendingMessages.isEmpty()) {
                logger.debug("No pending messages to process");
                return;
            }
            
            logger.info("Processing {} pending outbox messages", pendingMessages.size());
            
            // Process each message
            for (OutboxMessage message : pendingMessages) {
                processMessage(message);
            }
            
            // Update metrics
            updateMetrics();
            
        } catch (Exception e) {
            logger.error("Error processing pending messages", e);
            failedCount.incrementAndGet();
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Fetches pending outbox messages that can be processed by this worker.
     */
    private List<OutboxMessage> fetchPendingMessages() {
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om " +
            "WHERE om.status = :status " +
            "AND (om.workerId IS NULL OR om.workerId = :workerId) " +
            "ORDER BY om.createdAt ASC",
            OutboxMessage.class
        );
        
        query.setParameter("status", OutboxMessage.Status.PENDING);
        query.setParameter("workerId", workerId);
        query.setMaxResults(batchSize);
        
        return query.getResultList();
    }
    
    /**
     * Processes a single outbox message.
     */
    private void processMessage(OutboxMessage message) {
        Span span = null;
        if (tracer != null) {
            span = tracer.spanBuilder("outbox.relay.process_message")
                    .setAttribute("message.id", message.getId().toString())
                    .setAttribute("aggregate.type", message.getAggregateType())
                    .setAttribute("event.type", message.getEventType())
                    .startSpan();
        }
        
        try {
            // Mark message as being processed by this worker
            message.setWorkerId(workerId);
            entityManager.merge(message);
            entityManager.flush();
            
            // Publish to Kafka
            publishToKafka(message);
            
            // Mark as sent
            message.markAsSent();
            entityManager.merge(message);
            
            processedCount.incrementAndGet();
            outboxMetrics.incrementOutboxMessageProcessed(message.getAggregateType(), "SENT");
            
            logger.debug("Successfully processed outbox message: {}", message.getId());
            
        } catch (Exception e) {
            logger.error("Failed to process outbox message {}: {}", message.getId(), e.getMessage(), e);
            
            // Mark as failed
            message.markAsFailed(e.getMessage());
            entityManager.merge(message);
            
            failedCount.incrementAndGet();
            outboxMetrics.incrementOutboxMessageProcessed(message.getAggregateType(), "FAILED");
            
            // If max retries exceeded, move to dead letter
            if (message.isDeadLetter()) {
                publishToDeadLetter(message);
            }
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Publishes a message to Kafka.
     */
    private void publishToKafka(OutboxMessage message) {
        try {
            String topic = buildTopicName(message.getAggregateType());
            String key = message.getAggregateId();
            String payload = buildKafkaPayload(message);
            
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);
            SendResult<String, String> result = future.get(); // Wait for completion
            
            logger.debug("Published message to Kafka: topic={}, key={}, partition={}, offset={}", 
                    topic, key, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish message to Kafka", e);
        }
    }
    
    /**
     * Publishes a message to the dead letter topic.
     */
    private void publishToDeadLetter(OutboxMessage message) {
        try {
            String payload = buildKafkaPayload(message);
            kafkaTemplate.send(deadLetterTopic, message.getId().toString(), payload);
            
            logger.warn("Published message to dead letter topic: {}", message.getId());
            
        } catch (Exception e) {
            logger.error("Failed to publish message to dead letter topic: {}", message.getId(), e);
        }
    }
    
    /**
     * Builds the Kafka topic name for the given aggregate type.
     */
    private String buildTopicName(String aggregateType) {
        return topicPrefix + "." + aggregateType.toLowerCase();
    }
    
    /**
     * Builds the Kafka message payload.
     */
    private String buildKafkaPayload(OutboxMessage message) {
        try {
            // Create a structured event payload
            var eventPayload = Map.of(
                "id", message.getId().toString(),
                "aggregateId", message.getAggregateId(),
                "aggregateType", message.getAggregateType(),
                "eventType", message.getEventType(),
                "payload", objectMapper.readTree(message.getPayload()),
                "changedFields", message.getChangedFields() != null ? 
                    objectMapper.readTree(message.getChangedFields()) : null,
                "createdAt", message.getCreatedAt().toString(),
                "metadata", Map.of(
                    "workerId", workerId,
                    "version", message.getVersion()
                )
            );
            
            return objectMapper.writeValueAsString(eventPayload);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Kafka payload", e);
        }
    }
    
    /**
     * Updates metrics with current processing statistics.
     */
    private void updateMetrics() {
        // Update pending messages count
        Long pendingCount = entityManager.createQuery(
            "SELECT COUNT(om) FROM OutboxMessage om WHERE om.status = :status", Long.class)
            .setParameter("status", OutboxMessage.Status.PENDING)
            .getSingleResult();
        
        outboxMetrics.recordPendingMessagesCount(pendingCount);
        
        // Update failed messages count
        Long failedCount = entityManager.createQuery(
            "SELECT COUNT(om) FROM OutboxMessage om WHERE om.status = :status", Long.class)
            .setParameter("status", OutboxMessage.Status.FAILED)
            .getSingleResult();
        
        outboxMetrics.recordFailedMessagesCount(failedCount);
        
        // Update dead letter messages count
        Long deadLetterCount = entityManager.createQuery(
            "SELECT COUNT(om) FROM OutboxMessage om WHERE om.status = :status", Long.class)
            .setParameter("status", OutboxMessage.Status.DEAD_LETTER)
            .getSingleResult();
        
        outboxMetrics.recordDeadLetterMessagesCount(deadLetterCount);
    }
    
    /**
     * Cleanup task to remove old processed messages.
     */
    @Scheduled(cron = "${outbox.relay.cleanup.cron:0 0 2 * * ?}") // Default: daily at 2 AM
    @Transactional
    public void cleanupOldMessages() {
        try {
            // Delete messages older than retention period (default: 30 days)
            int retentionDays = 30; // TODO: Make configurable
            
            Instant cutoffDate = Instant.now().minusSeconds(retentionDays * 24 * 60 * 60);
            
            int deletedCount = entityManager.createQuery(
                "DELETE FROM OutboxMessage om WHERE om.status = :status AND om.processedAt < :cutoffDate")
                .setParameter("status", OutboxMessage.Status.SENT)
                .setParameter("cutoffDate", cutoffDate)
                .executeUpdate();
            
            if (deletedCount > 0) {
                logger.info("Cleaned up {} old processed messages", deletedCount);
            }
            
        } catch (Exception e) {
            logger.error("Error during cleanup of old messages", e);
        }
    }
    
    // Getters for metrics
    public int getProcessedCount() {
        return processedCount.get();
    }
    
    public int getFailedCount() {
        return failedCount.get();
    }
    
    public String getWorkerId() {
        return workerId;
    }
} 