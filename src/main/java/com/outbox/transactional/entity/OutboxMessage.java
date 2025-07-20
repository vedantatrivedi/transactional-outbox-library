package com.outbox.transactional.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an outbox message for the transactional outbox pattern.
 * 
 * <p>This entity stores messages that need to be published to external systems
 * (like message brokers) as part of a database transaction. The messages are
 * created when entities annotated with {@link com.outbox.transactional.annotation.TransactionalOutbox}
 * are persisted or updated.</p>
 * 
 * <p>The message lifecycle is:
 * <ol>
 *   <li>PENDING - Message created and waiting to be processed</li>
 *   <li>SENT - Message successfully published to external system</li>
 *   <li>FAILED - Message failed to be published (will be retried)</li>
 *   <li>DEAD_LETTER - Message permanently failed and moved to dead letter queue</li>
 * </ol></p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Entity
@Table(name = "outbox_messages", indexes = {
    @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
    @Index(name = "idx_outbox_aggregate_id", columnList = "aggregate_id"),
    @Index(name = "idx_outbox_event_type", columnList = "event_type")
})
public class OutboxMessage {
    
    /**
     * Message status enumeration
     */
    public enum Status {
        PENDING,
        SENT,
        FAILED,
        DEAD_LETTER
    }
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    
    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;
    
    @Column(name = "aggregate_type", nullable = false, length = 255)
    private String aggregateType;
    
    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;
    
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;
    
    @Column(name = "changed_fields", columnDefinition = "TEXT")
    private String changedFields;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;
    
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;
    
    @Column(name = "processed_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant processedAt;
    
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
    
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "worker_id", length = 255)
    private String workerId;
    
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
    
    // Constructors
    public OutboxMessage() {
        this.createdAt = Instant.now();
    }
    
    public OutboxMessage(String aggregateId, String aggregateType, String eventType, String payload) {
        this();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
    }
    
    public OutboxMessage(String aggregateId, String aggregateType, String eventType, String payload, String changedFields) {
        this(aggregateId, aggregateType, eventType, payload);
        this.changedFields = changedFields;
    }
    
    // Getters and Setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
    }
    
    public String getAggregateId() {
        return aggregateId;
    }
    
    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }
    
    public String getAggregateType() {
        return aggregateType;
    }
    
    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public String getPayload() {
        return payload;
    }
    
    public void setPayload(String payload) {
        this.payload = payload;
    }
    
    public String getChangedFields() {
        return changedFields;
    }
    
    public void setChangedFields(String changedFields) {
        this.changedFields = changedFields;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getProcessedAt() {
        return processedAt;
    }
    
    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
    
    public Integer getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
    
    public Integer getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public String getWorkerId() {
        return workerId;
    }
    
    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    // Business methods
    @JsonIgnore
    public boolean isPending() {
        return Status.PENDING.equals(status);
    }
    
    @JsonIgnore
    public boolean isSent() {
        return Status.SENT.equals(status);
    }
    
    @JsonIgnore
    public boolean isFailed() {
        return Status.FAILED.equals(status);
    }
    
    @JsonIgnore
    public boolean isDeadLetter() {
        return Status.DEAD_LETTER.equals(status);
    }
    
    @JsonIgnore
    public boolean canRetry() {
        return retryCount < maxRetries;
    }
    
    public void markAsSent() {
        this.status = Status.SENT;
        this.processedAt = Instant.now();
        this.errorMessage = null;
    }
    
    public void markAsFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
        
        if (!canRetry()) {
            this.status = Status.DEAD_LETTER;
        }
    }
    
    public void markAsDeadLetter(String errorMessage) {
        this.status = Status.DEAD_LETTER;
        this.errorMessage = errorMessage;
        this.processedAt = Instant.now();
    }
    
    @Override
    public String toString() {
        return "OutboxMessage{" +
                "id=" + id +
                ", aggregateId='" + aggregateId + '\'' +
                ", aggregateType='" + aggregateType + '\'' +
                ", eventType='" + eventType + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", retryCount=" + retryCount +
                '}';
    }
} 