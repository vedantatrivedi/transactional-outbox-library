# Deployment Guide - Transactional Outbox Library

This guide provides step-by-step instructions for deploying the transactional outbox library in production environments.

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- PostgreSQL 12+
- Apache Kafka 2.8+
- Docker and Docker Compose (optional)

## Quick Start with Docker Compose

### 1. Clone and Build

```bash
git clone <repository-url>
cd transactional-outbox-library
./build.sh
```

### 2. Start Infrastructure

```bash
# Start PostgreSQL and Kafka
docker-compose up -d postgres kafka zookeeper

# Wait for services to be healthy
docker-compose ps
```

### 3. Start Application

```bash
# Start the outbox application
docker-compose up -d outbox-app

# Check logs
docker-compose logs -f outbox-app
```

### 4. Access Services

- **Application**: http://localhost:8081
- **Kafka UI**: http://localhost:8080
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

## Production Deployment

### 1. Database Setup

#### PostgreSQL Configuration

```sql
-- Create database
CREATE DATABASE outbox_db;

-- Create user with limited permissions
CREATE USER outbox_user WITH PASSWORD 'secure_password';
GRANT CONNECT ON DATABASE outbox_db TO outbox_user;
GRANT USAGE ON SCHEMA public TO outbox_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO outbox_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO outbox_user;
```

#### Recommended PostgreSQL Settings

```ini
# postgresql.conf
max_connections = 200
shared_buffers = 256MB
effective_cache_size = 1GB
work_mem = 4MB
maintenance_work_mem = 64MB
checkpoint_completion_target = 0.9
wal_buffers = 16MB
default_statistics_target = 100
random_page_cost = 1.1
effective_io_concurrency = 200
```

### 2. Kafka Setup

#### Topic Configuration

```bash
# Create required topics
kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic outbox.events.user --partitions 3 --replication-factor 2

kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic outbox.retry --partitions 3 --replication-factor 2

kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic outbox.dead-letter --partitions 1 --replication-factor 2
```

#### Recommended Kafka Settings

```properties
# server.properties
num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600
num.partitions=3
num.recovery.threads.per.data.dir=1
offsets.topic.replication.factor=2
transaction.state.log.replication.factor=2
transaction.state.log.min.isr=1
log.retention.hours=168
log.segment.bytes=1073741824
log.retention.check.interval.ms=300000
```

### 3. Application Configuration

#### Environment Variables

```bash
# Database
export DATABASE_URL=jdbc:postgresql://postgres:5432/outbox_db
export DATABASE_USERNAME=outbox_user
export DATABASE_PASSWORD=secure_password

# Kafka
export KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092,kafka3:9092
export KAFKA_CONSUMER_GROUP_ID=outbox-relay-group

# Outbox Configuration
export OUTBOX_RELAY_ENABLED=true
export OUTBOX_RELAY_BATCH_SIZE=100
export OUTBOX_RELAY_POLLING_INTERVAL=5000
export OUTBOX_RELAY_WORKER_ID=worker-$(hostname)
export OUTBOX_KAFKA_TOPIC_PREFIX=outbox.events
export OUTBOX_RETENTION_DAYS=30

# JVM Options
export JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:+UseContainerSupport"
```

#### Application Properties

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
  
  kafka:
    producer:
      acks: all
      retries: 3
      batch-size: 16384
      linger-ms: 1
      buffer-memory: 33554432
      compression-type: snappy

outbox:
  relay:
    batch-size: 100
    polling-interval: 5000
    cleanup:
      cron: "0 0 2 * * ?"
      retention-days: 30
```

### 4. Kubernetes Deployment

#### Namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: outbox-system
```

#### ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: outbox-config
  namespace: outbox-system
data:
  application.yml: |
    spring:
      datasource:
        url: jdbc:postgresql://postgres:5432/outbox_db
      kafka:
        bootstrap-servers: kafka:9092
    outbox:
      relay:
        enabled: true
        batch-size: 100
        polling-interval: 5000
```

#### Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: outbox-secrets
  namespace: outbox-system
type: Opaque
data:
  database-password: <base64-encoded-password>
  kafka-password: <base64-encoded-password>
```

#### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: outbox-relay
  namespace: outbox-system
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
        ports:
        - containerPort: 8080
        env:
        - name: DATABASE_USERNAME
          value: outbox_user
        - name: DATABASE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: outbox-secrets
              key: database-password
        - name: OUTBOX_RELAY_WORKER_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
```

#### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: outbox-relay
  namespace: outbox-system
spec:
  selector:
    app: outbox-relay
  ports:
  - port: 8080
    targetPort: 8080
  type: ClusterIP
```

#### HorizontalPodAutoscaler

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: outbox-relay-hpa
  namespace: outbox-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: outbox-relay
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### 5. Monitoring Setup

#### Prometheus Configuration

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'outbox-relay'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: outbox-relay
        action: keep
      - source_labels: [__address__]
        target_label: __metrics_path__
        replacement: /actuator/prometheus
```

#### Grafana Dashboard

Import the provided dashboard JSON or create custom dashboards for:

- Message processing rates
- Error rates and types
- Processing latency
- Queue depths
- System resource usage

#### Alerting Rules

```yaml
# alerting-rules.yml
groups:
  - name: outbox-alerts
    rules:
      - alert: HighPendingMessages
        expr: outbox_messages_pending > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High number of pending outbox messages"
          
      - alert: HighErrorRate
        expr: rate(outbox_messages_processed_total{status="FAILED"}[5m]) > 0.1
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate in outbox processing"
          
      - alert: DeadLetterMessages
        expr: outbox_messages_dead_letter > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Messages in dead letter queue"
```

### 6. Security Considerations

#### Network Security

- Use TLS for database connections
- Enable Kafka SSL/TLS
- Implement network policies in Kubernetes
- Use service mesh for inter-service communication

#### Authentication & Authorization

- Use strong database passwords
- Implement Kafka SASL authentication
- Use Kubernetes RBAC
- Enable audit logging

#### Data Protection

- Encrypt data at rest
- Use encrypted connections
- Implement data retention policies
- Regular security updates

### 7. Performance Tuning

#### JVM Tuning

```bash
export JAVA_OPTS="
  -Xms2g -Xmx4g
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+UseContainerSupport
  -XX:+UnlockExperimentalVMOptions
  -XX:+UseCGroupMemoryLimitForHeap
  -XX:MaxRAMFraction=2
"
```

#### Database Tuning

- Optimize PostgreSQL settings for your workload
- Use connection pooling
- Monitor query performance
- Regular maintenance

#### Kafka Tuning

- Adjust producer/consumer settings
- Monitor partition distribution
- Optimize topic configurations
- Monitor broker performance

### 8. Backup and Recovery

#### Database Backup

```bash
#!/bin/bash
# backup.sh
pg_dump -h postgres -U outbox_user -d outbox_db > backup_$(date +%Y%m%d_%H%M%S).sql
```

#### Kafka Backup

- Enable log compaction for important topics
- Regular topic replication
- Monitor consumer lag

#### Application Backup

- Configuration backups
- Log archives
- Metrics data retention

### 9. Troubleshooting

#### Common Issues

1. **High Pending Message Count**
   - Check relay service logs
   - Verify Kafka connectivity
   - Monitor database performance

2. **Message Processing Failures**
   - Check Kafka topic configuration
   - Verify message format
   - Review error logs

3. **Performance Issues**
   - Monitor resource usage
   - Check database connection pool
   - Review Kafka producer settings

#### Log Analysis

```bash
# Check application logs
kubectl logs -f deployment/outbox-relay -n outbox-system

# Check database logs
docker logs postgres

# Check Kafka logs
docker logs kafka
```

#### Health Checks

```bash
# Application health
curl http://localhost:8080/actuator/health

# Database connectivity
pg_isready -h postgres -p 5432

# Kafka connectivity
kafka-topics.sh --bootstrap-server kafka:9092 --list
```

## Support

For additional support:

- Check the main README.md
- Review application logs
- Monitor metrics and alerts
- Create issues in the project repository 