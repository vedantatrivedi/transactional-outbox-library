package com.outbox.transactional.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outbox.transactional.annotation.TransactionalOutbox;
import com.outbox.transactional.entity.OutboxMessage;
import com.outbox.transactional.exception.OutboxCreationException;
import com.outbox.transactional.metrics.OutboxMetrics;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hibernate event listener that automatically creates outbox messages when
 * entities annotated with {@link TransactionalOutbox} are persisted or updated.
 * 
 * <p>This listener implements both PreInsertEventListener and PreUpdateEventListener
 * to capture both creation and update events for annotated entities.</p>
 * 
 * <p>Features:
 * <ul>
 *   <li>Automatic outbox message creation for annotated entities</li>
 *   <li>Support for custom payload methods (toOutboxPayload())</li>
 *   <li>Changed fields tracking for update operations</li>
 *   <li>Robust ID extraction for various ID types</li>
 *   <li>Comprehensive error handling and metrics</li>
 *   <li>Distributed tracing support</li>
 * </ul></p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Component
public class OutboxEntityListener implements PreInsertEventListener, PreUpdateEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxEntityListener.class);
    private static final String TO_OUTBOX_PAYLOAD_METHOD = "toOutboxPayload";
    
    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private OutboxMetrics outboxMetrics;
    
    @Autowired(required = false)
    private Tracer tracer;
    
    // Cache for reflection lookups
    private final Map<Class<?>, Method> payloadMethodCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> idMethodCache = new ConcurrentHashMap<>();
    
    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        if (!requires(event)) {
            return false;
        }
        
        try {
            createOutboxMessage(event, "INSERT");
            return false; // Continue with the insert
        } catch (Exception e) {
            logger.error("Failed to create outbox message for insert event", e);
            outboxMetrics.incrementOutboxCreationFailure(event.getEntity().getClass().getSimpleName());
            throw new OutboxCreationException("Failed to create outbox message for insert", e);
        }
    }
    
    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        if (!requires(event)) {
            return false;
        }
        
        try {
            createOutboxMessage(event, "UPDATE");
            return false; // Continue with the update
        } catch (Exception e) {
            logger.error("Failed to create outbox message for update event", e);
            outboxMetrics.incrementOutboxCreationFailure(event.getEntity().getClass().getSimpleName());
            throw new OutboxCreationException("Failed to create outbox message for update", e);
        }
    }
    
    /**
     * Determines if this listener should process the given event.
     */
    private boolean requires(AbstractPreDatabaseOperationEvent event) {
        return event.getPersister().getMappedClass().isAnnotationPresent(TransactionalOutbox.class);
    }
    
    /**
     * Creates an outbox message for the given event.
     */
    private void createOutboxMessage(AbstractPreDatabaseOperationEvent event, String operation) {
        Object entity = event.getEntity();
        Class<?> entityClass = entity.getClass();
        TransactionalOutbox annotation = entityClass.getAnnotation(TransactionalOutbox.class);
        
        Span span = null;
        if (tracer != null) {
            span = tracer.spanBuilder("outbox.create_message")
                    .setAttribute("entity.type", entityClass.getSimpleName())
                    .setAttribute("operation", operation)
                    .startSpan();
        }
        
        try {
            // Extract entity information
            String aggregateId = extractEntityId(entity, event.getPersister());
            String aggregateType = getAggregateType(annotation, entityClass);
            String eventType = getEventType(annotation, entityClass, operation);
            
            // Create payload
            String payload = createPayload(entity, entityClass);
            
            // Handle changed fields for updates
            String changedFields = null;
            if ("UPDATE".equals(operation) && annotation.includeChangedFields() && event instanceof PreUpdateEvent) {
                changedFields = extractChangedFields((PreUpdateEvent) event);
            }
            
            // Create and persist outbox message
            OutboxMessage outboxMessage = new OutboxMessage(aggregateId, aggregateType, eventType, payload, changedFields);
            entityManager.persist(outboxMessage);
            
            // Record metrics
            outboxMetrics.incrementOutboxMessageCreated(entityClass.getSimpleName(), eventType);
            
            logger.debug("Created outbox message: id={}, aggregateId={}, aggregateType={}, eventType={}", 
                    outboxMessage.getId(), aggregateId, aggregateType, eventType);
            
        } catch (Exception e) {
            logger.error("Error creating outbox message for entity {}: {}", entityClass.getSimpleName(), e.getMessage(), e);
            throw e;
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
    
    /**
     * Extracts the entity ID using reflection or Hibernate's ID generation.
     */
    private String extractEntityId(Object entity, EntityPersister persister) {
        try {
            // Try to get the ID using Hibernate's persister
            Object id = persister.getIdentifier(entity, null);
            if (id != null) {
                return id.toString();
            }
            
            // Fallback to reflection if Hibernate doesn't have the ID yet
            return extractIdViaReflection(entity);
            
        } catch (Exception e) {
            logger.warn("Failed to extract entity ID using persister, falling back to reflection", e);
            return extractIdViaReflection(entity);
        }
    }
    
    /**
     * Extracts entity ID using reflection.
     */
    private String extractIdViaReflection(Object entity) {
        Class<?> entityClass = entity.getClass();
        Method idMethod = idMethodCache.computeIfAbsent(entityClass, this::findIdMethod);
        
        if (idMethod != null) {
            try {
                Object id = idMethod.invoke(entity);
                return id != null ? id.toString() : null;
            } catch (Exception e) {
                logger.warn("Failed to extract ID via reflection for entity {}", entityClass.getSimpleName(), e);
            }
        }
        
        // Last resort: try common ID field names
        return tryCommonIdFields(entity);
    }
    
    /**
     * Finds the ID getter method for the entity.
     */
    private Method findIdMethod(Class<?> entityClass) {
        // Try common ID method names
        String[] possibleNames = {"getId", "getEntityId", "getPrimaryKey"};
        
        for (String methodName : possibleNames) {
            try {
                Method method = entityClass.getMethod(methodName);
                if (method.getReturnType() != void.class) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Continue to next method name
            }
        }
        
        return null;
    }
    
    /**
     * Tries to extract ID from common field names.
     */
    private String tryCommonIdFields(Object entity) {
        Class<?> entityClass = entity.getClass();
        String[] possibleFields = {"id", "entityId", "primaryKey"};
        
        for (String fieldName : possibleFields) {
            try {
                java.lang.reflect.Field field = entityClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value != null) {
                    return value.toString();
                }
            } catch (Exception ignored) {
                // Continue to next field
            }
        }
        
        return null;
    }
    
    /**
     * Creates the payload for the outbox message.
     */
    private String createPayload(Object entity, Class<?> entityClass) {
        try {
            // Check if entity has a custom payload method
            Method payloadMethod = payloadMethodCache.computeIfAbsent(entityClass, this::findPayloadMethod);
            
            if (payloadMethod != null) {
                Object customPayload = payloadMethod.invoke(entity);
                return objectMapper.writeValueAsString(customPayload);
            } else {
                // Default: serialize the entire entity
                return objectMapper.writeValueAsString(entity);
            }
        } catch (JsonProcessingException e) {
            throw new OutboxCreationException("Failed to serialize entity payload", e);
        } catch (Exception e) {
            throw new OutboxCreationException("Failed to create payload", e);
        }
    }
    
    /**
     * Finds the custom payload method on the entity.
     */
    private Method findPayloadMethod(Class<?> entityClass) {
        try {
            Method method = entityClass.getMethod(TO_OUTBOX_PAYLOAD_METHOD);
            if (method.getReturnType() != void.class) {
                return method;
            }
        } catch (NoSuchMethodException ignored) {
            // Method doesn't exist, use default serialization
        }
        return null;
    }
    
    /**
     * Extracts changed fields for update operations.
     */
    private String extractChangedFields(PreUpdateEvent event) {
        try {
            Object[] oldState = event.getOldState();
            Object[] newState = event.getState();
            String[] propertyNames = event.getPersister().getPropertyNames();
            
            Map<String, Object> changedFields = new HashMap<>();
            
            for (int i = 0; i < propertyNames.length; i++) {
                Object oldValue = oldState[i];
                Object newValue = newState[i];
                
                if (!Objects.equals(oldValue, newValue)) {
                    changedFields.put(propertyNames[i], Map.of(
                        "oldValue", oldValue,
                        "newValue", newValue
                    ));
                }
            }
            
            return objectMapper.writeValueAsString(changedFields);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize changed fields", e);
            return null;
        }
    }
    
    /**
     * Gets the aggregate type from annotation or defaults to entity class name.
     */
    private String getAggregateType(TransactionalOutbox annotation, Class<?> entityClass) {
        String aggregateType = annotation.aggregateType();
        return aggregateType.isEmpty() ? entityClass.getSimpleName() : aggregateType;
    }
    
    /**
     * Gets the event type from annotation or defaults to entity class name + operation.
     */
    private String getEventType(TransactionalOutbox annotation, Class<?> entityClass, String operation) {
        String eventType = annotation.eventType();
        if (eventType.isEmpty()) {
            return entityClass.getSimpleName().toUpperCase() + "_" + operation;
        }
        return eventType;
    }
} 