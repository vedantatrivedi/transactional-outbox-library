# Transactional Outbox Library

A production-ready Spring Boot library implementing the transactional outbox pattern for reliable event publishing in distributed systems.

## Overview

The transactional outbox pattern ensures that events are reliably published to external systems (like message brokers) while maintaining data consistency. This library provides a complete implementation with the following features:

- **Automatic outbox message creation** when entities are persisted or updated
- **Custom payload support** via `toOutboxPayload()` method
- **Changed fields tracking** for update operations
- **Reliable message publishing** with retry mechanisms and dead letter queues
- **Comprehensive metrics and observability**
- **Distributed tracing support**
- **Production-ready configuration**

## Quick Start

### 1. Add Dependency

Add the library to your Spring Boot project:

```xml
<dependency>
    <groupId>com.outbox</groupId>
    <artifactId>transactional-outbox-library</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Annotate Your Entities

```java
@Entity
@Table(name = "users")
@TransactionalOutbox(includeChangedFields = true)
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String email;
    private String firstName;
    private String lastName;
    
    // Optional: Custom payload method
    public Map<String, Object> toOutboxPayload() {
        return Map.of(
            "id", this.id,
            "email", this.email,
            "firstName", this.firstName,
            "lastName", this.lastName,
            "eventType", "USER_CREATED"
        );
    }
}
```

### 3. Configure Database

The library automatically creates the `outbox_messages` table via Flyway migrations.

### 4. Configure Kafka

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
```

### 5. Use Your Entities

```java
@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Transactional
    public User createUser(String email, String firstName, String lastName) {
        User user = new User(email, firstName, lastName);
        return userRepository.save(user); // Automatically creates outbox message
    }
    
    @Transactional
    public User updateUser(Long id, String firstName, String lastName) {
        User user = userRepository.findById(id).orElseThrow();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return userRepository.save(user); // Automatically creates outbox message with changed fields
    }
}
```

## Configuration

### Annotation Options

The `@TransactionalOutbox` annotation supports the following options:

```java
@TransactionalOutbox(
    includeChangedFields = true,    // Track changed fields on updates
    eventType = "CUSTOM_EVENT",     // Custom event type
    aggregateType = "CustomUser"    // Custom aggregate type
)
```

### Application Properties

```yaml
outbox:
  relay:
    enabled: true                    # Enable/disable relay service
    batch-size: 100                 # Number of messages to process per batch
    polling-interval: 5000          # Polling interval in milliseconds
    worker-id: ${random.uuid}       # Unique worker ID
    kafka:
      topic-prefix: outbox.events   # Topic prefix for events
      retry-topic: outbox.retry     # Retry topic
      dead-letter-topic: outbox.dead-letter  # Dead letter topic
    cleanup:
      cron: "0 0 2 * * ?"          # Cleanup schedule (daily at 2 AM)
      retention-days: 30           # Message retention period
```

### Environment Variables

The library supports configuration via environment variables:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/myapp
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export OUTBOX_RELAY_ENABLED=true
export OUTBOX_RELAY_BATCH_SIZE=100
```

## Features

### 1. Automatic Message Creation

When an entity annotated with `@TransactionalOutbox` is persisted or updated, an outbox message is automatically created in the same transaction.

### 2. Custom Payloads

Implement a `toOutboxPayload()` method to customize the event payload:

```java
public Map<String, Object> toOutboxPayload() {
    return Map.of(
        "id", this.id,
        "email", this.email,
        "eventType", "USER_CREATED",
        "timestamp", Instant.now().toString()
    );
}
```

### 3. Changed Fields Tracking

Enable changed fields tracking to include information about what fields changed during updates:

```java
@TransactionalOutbox(includeChangedFields = true)
public class User {
    // ...
}
```

The changed fields will be included in the outbox message as JSON:

```json
{
  "firstName": {
    "oldValue": "John",
    "newValue": "Jane"
  },
  "lastName": {
    "oldValue": "Doe",
    "newValue": "Smith"
  }
}
```

### 4. Reliable Message Publishing

The relay service provides:

- **Batch processing** for efficiency
- **Retry mechanism** with exponential backoff
- **Dead letter queue** for permanently failed messages
- **Concurrency control** to prevent duplicate processing
- **Automatic cleanup** of old messages

### 5. Observability

The library provides comprehensive metrics and tracing:

#### Metrics

- `outbox.messages.created` - Number of messages created
- `outbox.messages.processed` - Number of messages processed
- `outbox.processing.time` - Message processing latency
- `outbox.messages.pending` - Current pending message count
- `outbox.messages.failed` - Current failed message count
- `outbox.messages.dead_letter` - Current dead letter message count

#### Health Checks

- Database connectivity
- Kafka connectivity
- Outbox message processing status

#### Distributed Tracing

The library integrates with OpenTelemetry for distributed tracing across the outbox flow.

## Message Structure

Outbox messages are published to Kafka with the following structure:

```json
{
  "id": "uuid",
  "aggregateId": "123",
  "aggregateType": "User",
  "eventType": "USER_CREATED",
  "payload": {
    "id": 123,
    "email": "user@example.com",
    "firstName": "John",
    "lastName": "Doe"
  },
  "changedFields": {
    "firstName": {
      "oldValue": "John",
      "newValue": "Jane"
    }
  },
  "createdAt": "2024-01-01T12:00:00Z",
  "metadata": {
    "workerId": "worker-123",
    "version": 0
  }
}
```

## Database Schema

The library creates the following table structure:

```sql
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    changed_fields TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    error_message TEXT,
    worker_id VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);
```

## Testing

### Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class OutboxEntityListenerTest {
    
    @Test
    void testOnPreInsert_CreatesOutboxMessage() {
        // Test implementation
    }
}
```

### Integration Tests

```java
@SpringBootTest
@DataJpaTest
@ActiveProfiles("test")
class OutboxIntegrationTest {
    
    @Test
    @Transactional
    void testUserCreation_CreatesOutboxMessage() {
        // Test implementation
    }
}
```

## Deployment

### Docker

```dockerfile
FROM openjdk:17-jre-slim
COPY target/transactional-outbox-library-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: outbox-relay
spec:
  replicas: 3
  selector:
    matchLabels:
      app: outbox-relay
  template:
    metadata:
      labels:
        app: outbox-relay
    spec:
      containers:
      - name: outbox-relay
        image: outbox-relay:1.0.0
        env:
        - name: DATABASE_URL
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: url
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka:9092"
```

### Monitoring

Set up monitoring with Prometheus and Grafana:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'outbox-relay'
    static_configs:
      - targets: ['outbox-relay:8080']
    metrics_path: '/actuator/prometheus'
```

## Best Practices

### 1. Idempotency

Ensure your event consumers are idempotent. The outbox pattern guarantees at-least-once delivery, not exactly-once.

### 2. Event Design

- Keep events small and focused
- Use meaningful event types
- Include only necessary data in payloads
- Version your events for backward compatibility

### 3. Performance

- Use appropriate batch sizes
- Monitor database performance
- Consider partitioning for high-volume scenarios
- Use connection pooling

### 4. Monitoring

- Set up alerts for failed message processing
- Monitor pending message counts
- Track processing latency
- Monitor dead letter queue

### 5. Security

- Use secure database connections
- Implement proper Kafka security
- Use environment variables for sensitive configuration
- Enable audit logging

## Troubleshooting

### Common Issues

1. **Messages not being created**
   - Check that entities are annotated with `@TransactionalOutbox`
   - Verify Hibernate event listeners are registered
   - Check transaction boundaries

2. **Messages not being processed**
   - Verify relay service is enabled
   - Check Kafka connectivity
   - Review worker configuration

3. **High pending message count**
   - Check relay service logs
   - Verify Kafka topic configuration
   - Monitor database performance

### Logs

Enable debug logging for troubleshooting:

```yaml
logging:
  level:
    com.outbox.transactional: DEBUG
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support and questions:

- Create an issue on GitHub
- Check the documentation
- Review the examples

## Changelog

### 1.0.0
- Initial release
- Core outbox functionality
- Kafka integration
- Metrics and observability
- Comprehensive testing 