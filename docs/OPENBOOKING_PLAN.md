---
name: OpenBooking - Microservices Hotel Booking System
overview: ""
todos: []
---

# OpenBooking - Microservices Hotel Booking System

## Overview

Xây dựng hệ thống đặt phòng khách sạn (giống Agoda/Booking.com) với kiến trúc microservices hiện đại, sử dụng Java 17, Spring Boot 3.x, Gradle. Project tập trung vào các thách thức kỹ thuật về Concurrency, Distributed Systems, Saga Pattern, và CQRS.

## Architecture

### Service Breakdown

1. **user-service**: Quản lý users, profiles (wrapper quanh Keycloak hoặc custom logic)
2. **catalog-service**: Hotel info, Room types (Read Heavy) - Elasticsearch/MongoDB
3. **inventory-service**: Room Stock, Availability (Write Heavy/Concurrency) - PostgreSQL với locks
4. **booking-service**: Booking logic + Saga Orchestrator - PostgreSQL
5. **payment-service**: Mock payment gateway - PostgreSQL  
6. **notification-service**: Async email/SMS via Kafka - MongoDB
7. **common**: Shared DTOs, Utilities, Exception Handlers

### Tech Stack

- **Language**: Java 17 (structured for future Java 21 upgrade)
- **Build**: Gradle (multi-module)
- **Framework**: Spring Boot 3.x
- **Database**: PostgreSQL (write) + MongoDB (read) + Redis (cache/locks)
- **Messaging**: Apache Kafka
- **Gateway**: Kong
- **Service Discovery**: Consul
- **Resilience**: Resilience4j
- **Communication**: Feign (REST), note for future gRPC
- **Auth**: Keycloak
- **Observability**: Micrometer Tracing + Jaeger + Prometheus + Grafana
- **Migration**: Flyway
- **ORM**: JPA/Hibernate
- **Libraries**: Lombok, MapStruct, Redisson, OpenAPI3

## Project Structure

```
open-booking-v1/
├── settings.gradle
├── build.gradle
├── gradle/
├── docker-compose.yml
├── common/
│   ├── build.gradle
│   └── src/main/java/.../common/
│       ├── dto/
│       ├── exception/
│       └── util/
├── user-service/
│   ├── build.gradle
│   └── src/main/java/.../user/
├── catalog-service/
│   ├── build.gradle
│   └── src/main/java/.../catalog/
├── inventory-service/
│   ├── build.gradle
│   └── src/main/java/.../inventory/
├── booking-service/
│   ├── build.gradle
│   └── src/main/java/.../booking/
├── payment-service/
│   ├── build.gradle
│   └── src/main/java/.../payment/
└── notification-service/
    ├── build.gradle
    └── src/main/java/.../notification/
```

## Key Technical Implementations

### 1. Concurrency & Race Condition Handling

**Problem**: 1000 users cùng đặt 1 phòng cuối cùng

**Solution**:

- **Distributed Lock** (Redis/Redisson): `lock:room:{roomId}:{date}`
- **Database Locking**: Pessimistic Lock (`SELECT ... FOR UPDATE`) và Optimistic Lock (`@Version`)
- **Thread Pool**: `ThreadPoolExecutor` + `CompletableFuture` (Java 17), structured for Java 21 Virtual Threads

**Files**: `inventory-service/.../service/RoomReservationService.java`

### 2. Saga Pattern (Hybrid)

- **Orchestration** (booking flow chính): Booking Service orchestrates Inventory → Payment → Confirm
- **Choreography** (async events): Kafka events cho email, analytics, notifications

**Files**:

- `booking-service/.../saga/BookingOrchestrator.java` (Manual Orchestration)
- `booking-service/.../events/BookingEventPublisher.java` (Kafka events)
- `notification-service/.../consumer/BookingEventConsumer.java`

### 3. CQRS & Event Sourcing (Light Version)

- **Write Model**: PostgreSQL (inventory-service, booking-service)
- **Read Model**: MongoDB (catalog-service for search)
- **Event Sourcing**: Kafka events sync write → read model

**Files**: `catalog-service/.../event/CatalogEventConsumer.java`

### 4. Design Patterns

- **Strategy Pattern**: Dynamic Pricing (`PricingStrategy` interface)
- **Factory Pattern**: Strategy selection runtime
- **Repository Pattern**: Data access abstraction

**Files**: `inventory-service/.../pricing/PricingStrategy.java`, `PricingStrategyFactory.java`

## Implementation Phases

### Phase 1: Project Foundation

1. Setup Gradle multi-module structure
2. Configure common module (DTOs, exceptions, utilities)
3. Setup Docker Compose (PostgreSQL, MongoDB, Redis, Kafka, Kong, Consul, Keycloak, Jaeger)
4. Configure build.gradle files for all modules
5. Setup basic Spring Boot 3.x configuration

### Phase 2: Core Services (Base CRUD)

1. **user-service**: Basic user profile management (later integrate Keycloak)
2. **catalog-service**: Hotel/Room CRUD, basic search (PostgreSQL initially)
3. **inventory-service**: Room availability management
4. **payment-service**: Mock payment processing
5. **booking-service**: Basic booking creation
6. **notification-service**: Email service (SMTP mock)

### Phase 3: Advanced Concurrency

1. Implement Distributed Lock (Redisson) in inventory-service
2. Add Pessimistic/Optimistic Locking in JPA
3. Thread pool configuration for concurrent requests
4. Add comments for Java 21 Virtual Thread migration

### Phase 4: Saga Pattern (Orchestration)

1. Implement Booking Orchestrator (manual state machine)
2. Feign clients for inter-service communication
3. Compensating transactions (release room if payment fails)
4. Resilience4j circuit breakers, retries

### Phase 5: Event-Driven Architecture (Choreography)

1. Kafka event producers (booking events)
2. Kafka event consumers (notification service)
3. Event-driven catalog updates (CQRS read model)

### Phase 6: CQRS Implementation

1. Separate write (PostgreSQL) and read (MongoDB) models
2. Kafka events sync write → read
3. Catalog service search optimization with MongoDB

### Phase 7: Gateway & Service Discovery

1. Kong Gateway configuration (rate limiting, routing)
2. Consul service discovery
3. Service registration

### Phase 8: Observability & Documentation

1. Micrometer metrics, Prometheus
2. Distributed tracing (Jaeger)
3. OpenAPI3/Swagger documentation
4. README với architecture diagrams

### Phase 9: Testing & Documentation

1. Unit tests (JUnit 5 + Mockito)
2. Integration tests
3. Documentation updates

## Key Files to Create

### Root Level

- `settings.gradle` - Multi-module configuration
- `build.gradle` - Root build configuration
- `docker-compose.yml` - Local infrastructure
- `README.md` - Project documentation

### Common Module

- `common/src/main/java/.../common/dto/BaseResponse.java`
- `common/src/main/java/.../common/exception/GlobalExceptionHandler.java`
- `common/src/main/java/.../common/exception/BusinessException.java`

### Inventory Service (Core Concurrency Logic)

- `inventory-service/.../service/RoomReservationService.java` - Distributed lock logic
- `inventory-service/.../repository/RoomAvailabilityRepository.java` - Locking queries
- `inventory-service/.../pricing/PricingStrategy.java` - Strategy pattern
- `inventory-service/.../config/RedisConfig.java` - Redisson setup

### Booking Service (Saga Orchestrator)

- `booking-service/.../saga/BookingOrchestrator.java` - Manual orchestration
- `booking-service/.../service/BookingService.java` - Main booking logic
- `booking-service/.../events/BookingEventPublisher.java` - Kafka producer
- `booking-service/.../client/InventoryClient.java` - Feign client
- `booking-service/.../client/PaymentClient.java` - Feign client

### Catalog Service (CQRS Read Model)

- `catalog-service/.../event/CatalogEventConsumer.java` - Kafka consumer
- `catalog-service/.../service/SearchService.java` - MongoDB search

## Coding Standards

- Constructor Injection với `@RequiredArgsConstructor`
- No logic in Controllers - use Service/Facade
- Interface-based programming
- Java Records cho DTOs
- JUnit 5 + Mockito cho unit tests
- Javadoc cho complex concurrency logic
- Comments cho Java 21 Virtual Thread migration points

## Future Improvements (Documented)

- Java 21 Virtual Threads migration
- gRPC internal communication
- Camunda for workflow orchestration (interview knowledge)
- Centralized Configuration (Spring Cloud Config)
- Kubernetes deployment configs

