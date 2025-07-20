package com.outbox.transactional.integration;

import com.outbox.transactional.entity.OutboxMessage;
import com.outbox.transactional.example.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the transactional outbox system.
 * 
 * <p>These tests verify the complete flow from entity persistence to outbox message creation,
 * including transaction atomicity and proper message structure.</p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@SpringBootTest
@DataJpaTest
@ActiveProfiles("test")
class OutboxIntegrationTest {
    
    @Autowired
    private EntityManager entityManager;
    
    @Test
    @Transactional
    void testUserCreation_CreatesOutboxMessage() {
        // Given
        User user = new User("test@example.com", "John", "Doe");
        
        // When
        entityManager.persist(user);
        entityManager.flush();
        
        // Then
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om WHERE om.aggregateId = :aggregateId", 
            OutboxMessage.class
        );
        query.setParameter("aggregateId", user.getId().toString());
        
        List<OutboxMessage> messages = query.getResultList();
        assertEquals(1, messages.size());
        
        OutboxMessage message = messages.get(0);
        assertEquals(user.getId().toString(), message.getAggregateId());
        assertEquals("User", message.getAggregateType());
        assertEquals("USER_INSERT", message.getEventType());
        assertEquals(OutboxMessage.Status.PENDING, message.getStatus());
        assertNotNull(message.getPayload());
        assertNull(message.getChangedFields());
        assertNotNull(message.getCreatedAt());
    }
    
    @Test
    @Transactional
    void testUserUpdate_CreatesOutboxMessageWithChangedFields() {
        // Given
        User user = new User("test@example.com", "John", "Doe");
        entityManager.persist(user);
        entityManager.flush();
        
        // Clear the first message
        entityManager.createQuery("DELETE FROM OutboxMessage").executeUpdate();
        
        // When
        user.setFirstName("Jane");
        user.setLastName("Smith");
        entityManager.merge(user);
        entityManager.flush();
        
        // Then
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om WHERE om.aggregateId = :aggregateId", 
            OutboxMessage.class
        );
        query.setParameter("aggregateId", user.getId().toString());
        
        List<OutboxMessage> messages = query.getResultList();
        assertEquals(1, messages.size());
        
        OutboxMessage message = messages.get(0);
        assertEquals("USER_UPDATE", message.getEventType());
        assertNotNull(message.getChangedFields());
        assertTrue(message.getChangedFields().contains("firstName"));
        assertTrue(message.getChangedFields().contains("lastName"));
    }
    
    @Test
    @Transactional
    void testTransactionRollback_RemovesOutboxMessage() {
        // Given
        User user = new User("test@example.com", "John", "Doe");
        
        // When & Then
        try {
            entityManager.persist(user);
            entityManager.flush();
            
            // Verify message was created
            TypedQuery<OutboxMessage> query = entityManager.createQuery(
                "SELECT om FROM OutboxMessage om WHERE om.aggregateId = :aggregateId", 
                OutboxMessage.class
            );
            query.setParameter("aggregateId", user.getId().toString());
            
            List<OutboxMessage> messages = query.getResultList();
            assertEquals(1, messages.size());
            
            // Simulate an error that would cause rollback
            throw new RuntimeException("Simulated error");
            
        } catch (RuntimeException e) {
            // Expected exception
        }
        
        // Verify message was rolled back
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om", 
            OutboxMessage.class
        );
        
        List<OutboxMessage> messages = query.getResultList();
        assertEquals(0, messages.size());
    }
    
    @Test
    @Transactional
    void testCustomPayloadMethod_IsUsed() {
        // Given
        User user = new User("test@example.com", "John", "Doe");
        
        // When
        entityManager.persist(user);
        entityManager.flush();
        
        // Then
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om WHERE om.aggregateId = :aggregateId", 
            OutboxMessage.class
        );
        query.setParameter("aggregateId", user.getId().toString());
        
        OutboxMessage message = query.getSingleResult();
        assertNotNull(message.getPayload());
        
        // Verify the payload contains the custom structure from toOutboxPayload()
        assertTrue(message.getPayload().contains("email"));
        assertTrue(message.getPayload().contains("firstName"));
        assertTrue(message.getPayload().contains("lastName"));
        assertTrue(message.getPayload().contains("isActive"));
    }
    
    @Test
    @Transactional
    void testMultipleUsers_CreateMultipleMessages() {
        // Given
        User user1 = new User("user1@example.com", "John", "Doe");
        User user2 = new User("user2@example.com", "Jane", "Smith");
        
        // When
        entityManager.persist(user1);
        entityManager.persist(user2);
        entityManager.flush();
        
        // Then
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om ORDER BY om.createdAt", 
            OutboxMessage.class
        );
        
        List<OutboxMessage> messages = query.getResultList();
        assertEquals(2, messages.size());
        
        assertEquals(user1.getId().toString(), messages.get(0).getAggregateId());
        assertEquals(user2.getId().toString(), messages.get(1).getAggregateId());
    }
    
    @Test
    @Transactional
    void testOutboxMessageStructure_IsCorrect() {
        // Given
        User user = new User("test@example.com", "John", "Doe");
        
        // When
        entityManager.persist(user);
        entityManager.flush();
        
        // Then
        TypedQuery<OutboxMessage> query = entityManager.createQuery(
            "SELECT om FROM OutboxMessage om WHERE om.aggregateId = :aggregateId", 
            OutboxMessage.class
        );
        query.setParameter("aggregateId", user.getId().toString());
        
        OutboxMessage message = query.getSingleResult();
        
        // Verify all required fields are present
        assertNotNull(message.getId());
        assertNotNull(message.getAggregateId());
        assertNotNull(message.getAggregateType());
        assertNotNull(message.getEventType());
        assertNotNull(message.getPayload());
        assertNotNull(message.getStatus());
        assertNotNull(message.getCreatedAt());
        assertNotNull(message.getRetryCount());
        assertNotNull(message.getMaxRetries());
        assertNotNull(message.getVersion());
        
        // Verify default values
        assertEquals(OutboxMessage.Status.PENDING, message.getStatus());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
        assertEquals(0L, message.getVersion());
        assertNull(message.getProcessedAt());
        assertNull(message.getErrorMessage());
        assertNull(message.getWorkerId());
    }
} 