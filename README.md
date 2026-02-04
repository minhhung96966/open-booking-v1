# OpenBooking - Microservices Hotel Booking System

A production-ready microservices architecture for hotel booking system (similar to Agoda/Booking.com), built with Java 17, Spring Boot 3.x, and Gradle. This project demonstrates advanced distributed systems concepts including concurrency control, Saga pattern, CQRS, and event-driven architecture.

## 🎯 Project Overview

OpenBooking is a comprehensive hotel booking platform that showcases:

- **System Design**: Microservices architecture with proper service boundaries (DDD)
- **Scalability**: Horizontal scaling, distributed caching, async processing
- **Performance**: Concurrency patterns, connection pooling, query optimization
- **Distributed Systems**: Saga pattern, distributed transactions, event-driven architecture
- **Security**: JWT authentication, OAuth2, rate limiting, input validation
- **Modern Tech Stack**: Latest Spring Boot 3.x, Gradle, Kong Gateway, Consul, Kafka

## 🏗️ Architecture

### Service Breakdown

1. **user-service** (Port 8081): User profile management, Keycloak integration
2. **catalog-service** (Port 8082): Hotel/Room catalog with CQRS (PostgreSQL write + MongoDB read)
3. **inventory-service** (Port 8083): Room availability management with distributed locking
4. **booking-service** (Port 8084): Booking orchestration with Saga pattern
5. **payment-service** (Port 8085): Mock payment processing
6. **notification-service** (Port 8086): Async email/SMS notifications via Kafka

### Tech Stack

- **Language**: Java 17 (structured for Java 21 Virtual Threads migration)
- **Build Tool**: Gradle (multi-module)
- **Framework**: Spring Boot 3.x
- **Databases**: 
  - PostgreSQL (write models - ACID)
  - MongoDB (read models - fast search)
  - Redis (caching & distributed locks)
- **Messaging**: Apache Kafka (event-driven architecture)
- **Gateway**: Kong (rate limiting, routing, auth)
- **Service Discovery**: Consul
- **Resilience**: Resilience4j (Circuit Breaker, Retry, Bulkhead)
- **Communication**: Feign (REST), note for future gRPC
- **Auth**: Keycloak (OAuth2/OIDC)
- **Observability**: 
  - Micrometer + Prometheus (metrics)
  - Jaeger (distributed tracing)
  - Grafana (visualization)
- **Libraries**: Lombok, MapStruct, Redisson, OpenAPI3

## 🔑 Key Technical Implementations

### 1. Concurrency & Race Condition Handling

**Problem**: 1000 users simultaneously booking the last available room.

**Solutions Implemented**:

- **Distributed Lock (Redis/Redisson)**: `lock:room:{roomId}:{date}`
  - Primary mechanism for distributed systems
  - See: `inventory-service/.../service/RoomReservationService.java`

- **Pessimistic Locking (SELECT FOR UPDATE)**: Database-level exclusive lock
  - Use when you need guaranteed exclusive access
  - Can cause deadlocks in high concurrency

- **Optimistic Locking (@Version)**: Version-based conflict detection with retry
  - Lightweight, no deadlocks
  - Retries on `OptimisticLockingFailureException`

```java
// Distributed Lock Example
RLock lock = redissonClient.getLock("lock:room:101:2024-01-01");
lock.tryLock(5, 30, TimeUnit.SECONDS);
try {
    // Reserve room
} finally {
    lock.unlock();
}
```

### 2. Saga Pattern (Hybrid Approach)

**Problem**: Distributed transaction across services (Booking → Inventory → Payment).

**Implementation**:

- **Orchestration** (Manual): Booking Service orchestrates flow
  - Step 1: Reserve room (Inventory Service)
  - Step 2: Process payment (Payment Service)
  - Step 3: Confirm booking
  - Compensating transaction: Release room if payment fails
  - See: `booking-service/.../saga/BookingOrchestrator.java`

- **Choreography** (Kafka Events): Async event-driven flow
  - Booking confirmed → Kafka event → Notification service sends email
  - Loose coupling, scalable
  - See: `booking-service/.../events/BookingEventPublisher.java`

**Alternative**: Camunda workflow engine (noted for interview knowledge)

### 3. CQRS & Event Sourcing (Light Version)

**Write Model** (PostgreSQL): Source of truth for hotel/room data
- Normalized, ACID transactions
- Used for create/update operations

**Read Model** (MongoDB): Optimized for search
- Denormalized, indexed
- Synced from PostgreSQL via Kafka events
- See: `catalog-service/.../readmodel/HotelReadModel.java`

**Benefits**:
- Search operations don't impact write performance
- Read model optimized for specific queries
- Independent scaling of read/write operations

### 4. Design Patterns

- **Strategy Pattern**: Dynamic pricing (`PricingStrategy` interface)
- **Factory Pattern**: Strategy selection at runtime
- **Repository Pattern**: Data access abstraction

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Docker & Docker Compose
- Gradle 8.5+

### Local Development Setup

1. **Start Infrastructure**:
```bash
docker-compose up -d
```

This starts:
- PostgreSQL (5 instances for each service)
- MongoDB
- Redis
- Kafka + Zookeeper
- Kong Gateway
- Consul
- Keycloak
- Jaeger
- Prometheus
- Grafana

2. **Build Project**:
```bash
./gradlew build
```

3. **Run Services**:
```bash
# Terminal 1: User Service
./gradlew :user-service:bootRun

# Terminal 2: Catalog Service
./gradlew :catalog-service:bootRun

# Terminal 3: Inventory Service
./gradlew :inventory-service:bootRun

# Terminal 4: Booking Service
./gradlew :booking-service:bootRun

# Terminal 5: Payment Service
./gradlew :payment-service:bootRun

# Terminal 6: Notification Service
./gradlew :notification-service:bootRun
```

4. **Access Services**:
- User Service: http://localhost:8081
- Catalog Service: http://localhost:8082
- Inventory Service: http://localhost:8083
- Booking Service: http://localhost:8084
- Payment Service: http://localhost:8085
- Notification Service: http://localhost:8086

5. **Access Infrastructure**:
- Kong Admin: http://localhost:8001
- Consul: http://localhost:8500
- Keycloak: http://localhost:8080
- Jaeger UI: http://localhost:16686
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

### API Documentation

Swagger/OpenAPI documentation available at:
- Each service: `http://localhost:{port}/swagger-ui.html`
- Example: http://localhost:8084/swagger-ui.html (Booking Service)

## 📁 Project Structure

```
open-booking-v1/
├── settings.gradle              # Multi-module configuration
├── build.gradle                 # Root build configuration
├── docker-compose.yml           # Local infrastructure
├── common/                      # Shared DTOs, exceptions, utilities
├── user-service/                # User profile management
├── catalog-service/             # Hotel/Room catalog (CQRS)
├── inventory-service/           # Room availability (Concurrency)
├── booking-service/             # Booking orchestration (Saga)
├── payment-service/             # Mock payment processing
└── notification-service/        # Async notifications (Kafka)
```

## 🧪 Testing

### Unit Tests
```bash
./gradlew test
```

### Integration Tests
```bash
./gradlew integrationTest
```

## 🔮 Future Improvements

### Java 21 Migration
- Replace `ThreadPoolExecutor` with Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`)
- Migration points are marked in code with `TODO (Java 21 Migration)` comments
- See: `inventory-service/.../service/RoomReservationService.java`
- See: `notification-service/.../config/AsyncConfig.java`

### Communication
- **gRPC** for internal service-to-service communication (high performance, type-safe)
- REST will remain for external API (Frontend)

### Workflow Orchestration
- **Camunda** for complex state machines (interview knowledge)
- Alternative to manual Saga Orchestration

### Configuration
- **Spring Cloud Config** for centralized configuration management
- Consul KV Store integration

### Deployment
- **Kubernetes** deployment configs
- Helm charts for easy deployment

## 📚 Additional Resources

- **System Design**: Microservices with proper service boundaries (DDD)
- **Scalability**: Horizontal scaling, caching strategies
- **Performance**: Connection pooling, query optimization, async processing
- **Distributed Systems**: Saga pattern, CQRS, event-driven architecture
- **Security**: JWT, OAuth2, input validation, rate limiting

## 🤝 Contributing

This is a personal project for learning and demonstrating technical skills.

## 📝 License

MIT License - Feel free to use this project for learning purposes.

---

**Built with ❤️ by a Senior Java Developer demonstrating modern microservices architecture**
