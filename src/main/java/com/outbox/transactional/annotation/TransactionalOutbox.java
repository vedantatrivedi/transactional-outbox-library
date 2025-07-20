package com.outbox.transactional.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark entities that should trigger outbox message creation
 * when they are persisted or updated.
 * 
 * <p>This annotation enables the transactional outbox pattern for the annotated entity.
 * When an entity with this annotation is saved or updated, an outbox message will be
 * automatically created and stored in the outbox_messages table.</p>
 * 
 * <p>The entity can optionally implement a method named {@code toOutboxPayload()} that
 * returns a custom payload for the outbox message. If this method is not present,
 * the entire entity will be serialized as the payload.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * @Entity
 * @TransactionalOutbox(includeChangedFields = true)
 * public class User {
 *     // entity fields...
 *     
 *     public Map<String, Object> toOutboxPayload() {
 *         return Map.of(
 *             "id", this.id,
 *             "email", this.email,
 *             "eventType", "USER_CREATED"
 *         );
 *     }
 * }
 * }</pre>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TransactionalOutbox {
    
    /**
     * Whether to track and include changed fields in the outbox message.
     * This is only applicable for update operations.
     * 
     * <p>When enabled, the outbox message will include a JSON representation
     * of the fields that changed during the update operation.</p>
     * 
     * @return true if changed fields should be tracked, false otherwise
     */
    boolean includeChangedFields() default false;
    
    /**
     * The event type to use for this entity. If not specified, the entity
     * class name will be used as the event type.
     * 
     * @return the event type for outbox messages
     */
    String eventType() default "";
    
    /**
     * The aggregate type to use for this entity. If not specified, the entity
     * class name will be used as the aggregate type.
     * 
     * @return the aggregate type for outbox messages
     */
    String aggregateType() default "";
} 