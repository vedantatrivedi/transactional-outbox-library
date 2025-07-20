package com.outbox.transactional.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Metrics collection for the transactional outbox system.
 * 
 * <p>This class provides metrics for monitoring the health and performance
 * of the outbox system, including message creation, processing, and failures.</p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Component
public class OutboxMetrics {
    
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> messageCreationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> messageProcessingCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> processingTimers = new ConcurrentHashMap<>();
    
    @Autowired
    public OutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Increments the counter for outbox message creation.
     * 
     * @param entityType the type of entity that triggered the message creation
     * @param eventType the type of event (INSERT, UPDATE, etc.)
     */
    public void incrementOutboxMessageCreated(String entityType, String eventType) {
        String key = entityType + "." + eventType;
        Counter counter = messageCreationCounters.computeIfAbsent(key, k -> 
            Counter.builder("outbox.messages.created")
                .tag("entity.type", entityType)
                .tag("event.type", eventType)
                .description("Number of outbox messages created")
                .register(meterRegistry)
        );
        counter.increment();
    }
    
    /**
     * Increments the counter for outbox message processing.
     * 
     * @param entityType the type of entity
     * @param status the processing status (SENT, FAILED, etc.)
     */
    public void incrementOutboxMessageProcessed(String entityType, String status) {
        String key = entityType + "." + status;
        Counter counter = messageProcessingCounters.computeIfAbsent(key, k -> 
            Counter.builder("outbox.messages.processed")
                .tag("entity.type", entityType)
                .tag("status", status)
                .description("Number of outbox messages processed")
                .register(meterRegistry)
        );
        counter.increment();
    }
    
    /**
     * Increments the counter for outbox creation failures.
     * 
     * @param entityType the type of entity that failed
     */
    public void incrementOutboxCreationFailure(String entityType) {
        Counter counter = failureCounters.computeIfAbsent(entityType, k -> 
            Counter.builder("outbox.creation.failures")
                .tag("entity.type", entityType)
                .description("Number of outbox message creation failures")
                .register(meterRegistry)
        );
        counter.increment();
    }
    
    /**
     * Records the time taken to process an outbox message.
     * 
     * @param entityType the type of entity
     * @param durationMs the processing duration in milliseconds
     */
    public void recordProcessingTime(String entityType, long durationMs) {
        Timer timer = processingTimers.computeIfAbsent(entityType, k -> 
            Timer.builder("outbox.processing.time")
                .tag("entity.type", entityType)
                .description("Time taken to process outbox messages")
                .register(meterRegistry)
        );
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Records the time taken to process an outbox message using a Timer.Sample.
     * 
     * @param entityType the type of entity
     * @param sample the timer sample to stop and record
     */
    public void recordProcessingTime(String entityType, Timer.Sample sample) {
        Timer timer = processingTimers.computeIfAbsent(entityType, k -> 
            Timer.builder("outbox.processing.time")
                .tag("entity.type", entityType)
                .description("Time taken to process outbox messages")
                .register(meterRegistry)
        );
        sample.stop(timer);
    }
    
    /**
     * Creates a timer sample for measuring processing time.
     * 
     * @return a new Timer.Sample
     */
    public Timer.Sample startProcessingTimer() {
        return Timer.start(meterRegistry);
    }
    
    /**
     * Increments the counter for relay service polling.
     */
    public void incrementRelayPolling() {
        Counter.builder("outbox.relay.polling")
            .description("Number of relay service polling cycles")
            .register(meterRegistry)
            .increment();
    }
    
    /**
     * Records the number of pending messages in the outbox.
     * 
     * @param count the number of pending messages
     */
    public void recordPendingMessagesCount(long count) {
        meterRegistry.gauge("outbox.messages.pending", count);
    }
    
    /**
     * Records the number of failed messages in the outbox.
     * 
     * @param count the number of failed messages
     */
    public void recordFailedMessagesCount(long count) {
        meterRegistry.gauge("outbox.messages.failed", count);
    }
    
    /**
     * Records the number of dead letter messages in the outbox.
     * 
     * @param count the number of dead letter messages
     */
    public void recordDeadLetterMessagesCount(long count) {
        meterRegistry.gauge("outbox.messages.dead_letter", count);
    }
} 