package com.outbox.transactional.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outbox.transactional.annotation.TransactionalOutbox;
import com.outbox.transactional.entity.OutboxMessage;
import com.outbox.transactional.exception.OutboxCreationException;
import com.outbox.transactional.example.User;
import com.outbox.transactional.metrics.OutboxMetrics;
import io.opentelemetry.api.trace.Tracer;
import jakarta.persistence.EntityManager;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxEntityListener.
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class OutboxEntityListenerTest {
    
    @Mock
    private EntityManager entityManager;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @Mock
    private OutboxMetrics outboxMetrics;
    
    @Mock
    private Tracer tracer;
    
    @Mock
    private EntityPersister entityPersister;
    
    @Mock
    private PreInsertEvent preInsertEvent;
    
    @Mock
    private PreUpdateEvent preUpdateEvent;
    
    private OutboxEntityListener listener;
    private User testUser;
    
    @BeforeEach
    void setUp() throws Exception {
        listener = new OutboxEntityListener();
        
        // Use reflection to inject mocks
        var entityManagerField = OutboxEntityListener.class.getDeclaredField("entityManager");
        entityManagerField.setAccessible(true);
        entityManagerField.set(listener, entityManager);
        
        var objectMapperField = OutboxEntityListener.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(listener, objectMapper);
        
        var metricsField = OutboxEntityListener.class.getDeclaredField("outboxMetrics");
        metricsField.setAccessible(true);
        metricsField.set(listener, outboxMetrics);
        
        var tracerField = OutboxEntityListener.class.getDeclaredField("tracer");
        tracerField.setAccessible(true);
        tracerField.set(listener, tracer);
        
        // Setup test user
        testUser = new User("test@example.com", "John", "Doe");
        testUser.setId(1L);
        
        // Setup mocks
        when(entityPersister.getMappedClass()).thenReturn((Class) User.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"test\":\"value\"}");
        when(tracer.spanBuilder(anyString())).thenReturn(mock(io.opentelemetry.api.trace.SpanBuilder.class));
        when(tracer.spanBuilder(anyString()).setAttribute(anyString(), any())).thenReturn(mock(io.opentelemetry.api.trace.SpanBuilder.class));
        when(tracer.spanBuilder(anyString()).setAttribute(anyString(), any()).startSpan()).thenReturn(mock(io.opentelemetry.api.trace.Span.class));
    }
    
    @Test
    void testOnPreInsert_CreatesOutboxMessage() throws Exception {
        // Given
        when(preInsertEvent.getEntity()).thenReturn(testUser);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getIdentifier(testUser, null)).thenReturn(1L);
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        boolean result = listener.onPreInsert(preInsertEvent);
        
        // Then
        assertFalse(result); // Should continue with insert
        verify(entityManager).persist(messageCaptor.capture());
        
        OutboxMessage capturedMessage = messageCaptor.getValue();
        assertEquals("1", capturedMessage.getAggregateId());
        assertEquals("User", capturedMessage.getAggregateType());
        assertEquals("USER_INSERT", capturedMessage.getEventType());
        assertEquals("{\"test\":\"value\"}", capturedMessage.getPayload());
        assertEquals(OutboxMessage.Status.PENDING, capturedMessage.getStatus());
        assertNull(capturedMessage.getChangedFields());
        
        verify(outboxMetrics).incrementOutboxMessageCreated("User", "USER_INSERT");
    }
    
    @Test
    void testOnPreUpdate_CreatesOutboxMessageWithChangedFields() throws Exception {
        // Given
        when(preUpdateEvent.getEntity()).thenReturn(testUser);
        when(preUpdateEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getIdentifier(testUser, null)).thenReturn(1L);
        when(entityPersister.getPropertyNames()).thenReturn(new String[]{"firstName", "lastName"});
        when(preUpdateEvent.getOldState()).thenReturn(new Object[]{"John", "Doe"});
        when(preUpdateEvent.getState()).thenReturn(new Object[]{"Jane", "Smith"});
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        boolean result = listener.onPreUpdate(preUpdateEvent);
        
        // Then
        assertFalse(result); // Should continue with update
        verify(entityManager).persist(messageCaptor.capture());
        
        OutboxMessage capturedMessage = messageCaptor.getValue();
        assertEquals("1", capturedMessage.getAggregateId());
        assertEquals("User", capturedMessage.getAggregateType());
        assertEquals("USER_UPDATE", capturedMessage.getEventType());
        assertEquals("{\"test\":\"value\"}", capturedMessage.getPayload());
        assertEquals(OutboxMessage.Status.PENDING, capturedMessage.getStatus());
        assertNotNull(capturedMessage.getChangedFields());
        
        verify(outboxMetrics).incrementOutboxMessageCreated("User", "USER_UPDATE");
    }
    
    @Test
    void testOnPreInsert_WithCustomPayload() throws Exception {
        // Given
        when(preInsertEvent.getEntity()).thenReturn(testUser);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getIdentifier(testUser, null)).thenReturn(1L);
        when(objectMapper.writeValueAsString(any(Map.class))).thenReturn("{\"custom\":\"payload\"}");
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        listener.onPreInsert(preInsertEvent);
        
        // Then
        verify(entityManager).persist(messageCaptor.capture());
        assertEquals("{\"custom\":\"payload\"}", messageCaptor.getValue().getPayload());
    }
    
    @Test
    void testOnPreInsert_WithCustomEventType() throws Exception {
        // Given
        @TransactionalOutbox(eventType = "USER_REGISTERED")
        class CustomUser extends User {
            public CustomUser() {
                super();
            }
        }
        
        CustomUser customUser = new CustomUser();
        customUser.setId(2L);
        
        when(preInsertEvent.getEntity()).thenReturn(customUser);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getMappedClass()).thenReturn((Class) CustomUser.class);
        when(entityPersister.getIdentifier(customUser, null)).thenReturn(2L);
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        listener.onPreInsert(preInsertEvent);
        
        // Then
        verify(entityManager).persist(messageCaptor.capture());
        assertEquals("USER_REGISTERED", messageCaptor.getValue().getEventType());
    }
    
    @Test
    void testOnPreInsert_WithCustomAggregateType() throws Exception {
        // Given
        @TransactionalOutbox(aggregateType = "Customer")
        class Customer extends User {
            public Customer() {
                super();
            }
        }
        
        Customer customer = new Customer();
        customer.setId(3L);
        
        when(preInsertEvent.getEntity()).thenReturn(customer);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getMappedClass()).thenReturn((Class) Customer.class);
        when(entityPersister.getIdentifier(customer, null)).thenReturn(3L);
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        listener.onPreInsert(preInsertEvent);
        
        // Then
        verify(entityManager).persist(messageCaptor.capture());
        assertEquals("Customer", messageCaptor.getValue().getAggregateType());
    }
    
    @Test
    void testOnPreInsert_WithoutAnnotation_DoesNothing() {
        // Given
        class NonAnnotatedEntity {
            private Long id = 1L;
            public Long getId() { return id; }
        }
        
        NonAnnotatedEntity entity = new NonAnnotatedEntity();
        when(preInsertEvent.getEntity()).thenReturn(entity);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getMappedClass()).thenReturn((Class) NonAnnotatedEntity.class);
        
        // When
        boolean result = listener.onPreInsert(preInsertEvent);
        
        // Then
        assertFalse(result);
        verify(entityManager, never()).persist(any());
        verify(outboxMetrics, never()).incrementOutboxMessageCreated(anyString(), anyString());
    }
    
    @Test
    void testOnPreInsert_SerializationFailure_ThrowsException() throws Exception {
        // Given
        when(preInsertEvent.getEntity()).thenReturn(testUser);
        when(preInsertEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getIdentifier(testUser, null)).thenReturn(1L);
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Serialization failed"));
        
        // When & Then
        assertThrows(OutboxCreationException.class, () -> {
            listener.onPreInsert(preInsertEvent);
        });
        
        verify(outboxMetrics).incrementOutboxCreationFailure("User");
    }
    
    @Test
    void testOnPreUpdate_WithoutChangedFieldsTracking() throws Exception {
        // Given
        @TransactionalOutbox(includeChangedFields = false)
        class UserWithoutChangedFields extends User {
            public UserWithoutChangedFields() {
                super();
            }
        }
        
        UserWithoutChangedFields user = new UserWithoutChangedFields();
        user.setId(4L);
        
        when(preUpdateEvent.getEntity()).thenReturn(user);
        when(preUpdateEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getMappedClass()).thenReturn((Class) UserWithoutChangedFields.class);
        when(entityPersister.getIdentifier(user, null)).thenReturn(4L);
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        listener.onPreUpdate(preUpdateEvent);
        
        // Then
        verify(entityManager).persist(messageCaptor.capture());
        assertNull(messageCaptor.getValue().getChangedFields());
    }
    
    @Test
    void testOnPreUpdate_ChangedFieldsTracking() throws Exception {
        // Given
        when(preUpdateEvent.getEntity()).thenReturn(testUser);
        when(preUpdateEvent.getPersister()).thenReturn(entityPersister);
        when(entityPersister.getIdentifier(testUser, null)).thenReturn(1L);
        when(entityPersister.getPropertyNames()).thenReturn(new String[]{"firstName", "lastName", "isActive"});
        when(preUpdateEvent.getOldState()).thenReturn(new Object[]{"John", "Doe", true});
        when(preUpdateEvent.getState()).thenReturn(new Object[]{"Jane", "Doe", false});
        when(objectMapper.writeValueAsString(any(Map.class))).thenReturn("{\"changed\":\"fields\"}");
        
        ArgumentCaptor<OutboxMessage> messageCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        
        // When
        listener.onPreUpdate(preUpdateEvent);
        
        // Then
        verify(entityManager).persist(messageCaptor.capture());
        assertEquals("{\"changed\":\"fields\"}", messageCaptor.getValue().getChangedFields());
    }
} 