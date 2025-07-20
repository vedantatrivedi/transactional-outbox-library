package com.outbox.transactional.exception;

/**
 * Exception thrown when there is a failure in creating an outbox message.
 * 
 * <p>This exception is designed to trigger a transaction rollback when
 * outbox message creation fails, ensuring data consistency.</p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
public class OutboxCreationException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a new OutboxCreationException with the specified detail message.
     *
     * @param message the detail message
     */
    public OutboxCreationException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new OutboxCreationException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public OutboxCreationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Constructs a new OutboxCreationException with the specified cause.
     *
     * @param cause the cause
     */
    public OutboxCreationException(Throwable cause) {
        super(cause);
    }
} 