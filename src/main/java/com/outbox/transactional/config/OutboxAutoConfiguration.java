package com.outbox.transactional.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.outbox.transactional.listener.OutboxEntityListener;
import com.outbox.transactional.metrics.OutboxMetrics;
import com.outbox.transactional.service.OutboxRelayService;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.internal.SessionFactoryImpl;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Auto-configuration for the transactional outbox system.
 * 
 * <p>This configuration automatically sets up all the necessary components
 * for the outbox system when the library is included in a Spring Boot application.</p>
 * 
 * <p>Features:
 * <ul>
 *   <li>Jackson ObjectMapper configuration for proper JSON serialization</li>
 *   <li>OpenTelemetry tracer setup for distributed tracing</li>
 *   <li>Automatic registration of Hibernate event listeners</li>
 *   <li>Conditional bean creation based on properties</li>
 * </ul></p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Configuration
@EnableScheduling
@Import({OutboxEntityListener.class, OutboxMetrics.class})
public class OutboxAutoConfiguration {
    
    @Autowired
    private OutboxEntityListener outboxEntityListener;
    
    @Autowired
    private EntityManager entityManager;
    
    /**
     * Configures Jackson ObjectMapper for proper JSON serialization.
     * 
     * @return configured ObjectMapper
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    /**
     * Configures OpenTelemetry tracer for distributed tracing.
     * 
     * @return configured Tracer
     */
    @Bean
    @ConditionalOnMissingBean
    public Tracer tracer() {
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
            .build();
        
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(sdkTracerProvider)
            .build();
        
        return openTelemetry.getTracer("outbox-library");
    }
    
    /**
     * Creates the outbox relay service for processing outbox messages.
     * 
     * @return OutboxRelayService
     */
    @Bean
    @ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelayService outboxRelayService() {
        return new OutboxRelayService();
    }
    
    /**
     * Registers the OutboxEntityListener with Hibernate after the EntityManager is created.
     */
    @PostConstruct
    public void registerHibernateListeners() {
        try {
            // Get the Hibernate SessionFactory from the EntityManager
            SessionFactoryImpl sessionFactory = entityManager.unwrap(SessionFactoryImpl.class);
            EventListenerRegistry eventListenerRegistry = sessionFactory.getServiceRegistry()
                .getService(EventListenerRegistry.class);
            
            // Register the outbox listener for insert and update events
            eventListenerRegistry.appendListeners(EventType.PRE_INSERT, outboxEntityListener);
            eventListenerRegistry.appendListeners(EventType.PRE_UPDATE, outboxEntityListener);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to register outbox listeners with Hibernate", e);
        }
    }
} 