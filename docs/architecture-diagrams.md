# OpenBooking – Architecture Diagrams

Các sơ đồ dưới đây dùng cho mô tả kiến trúc khi phỏng vấn hoặc onboard.

---

## 1. Booking Saga (Orchestration)

Flow đặt phòng: Booking Service làm orchestrator, gọi Inventory → Payment → Confirm. Nếu payment fail thì chạy compensating transaction (release room).

```mermaid
sequenceDiagram
    participant Client
    participant Booking as Booking Service
    participant Inventory as Inventory Service
    participant Payment as Payment Service
    participant Kafka

    Client->>Booking: POST /bookings
    Booking->>Booking: Save booking (PENDING)
    Booking->>Inventory: POST /reserve (ReserveRoomRequest)
    Inventory-->>Booking: ReserveRoomResponse (totalPrice)
    Booking->>Booking: Update booking totalPrice
    Booking->>Payment: POST /payments/process (ProcessPaymentRequest)

    alt Payment SUCCESS
        Payment-->>Booking: ProcessPaymentResponse (SUCCESS)
        Booking->>Booking: Update booking CONFIRMED, paymentId
        Booking->>Kafka: Publish BookingConfirmedEvent
        Booking-->>Client: 200 BookingResponse (CONFIRMED)
    else Payment FAILED
        Payment-->>Booking: ProcessPaymentResponse (FAILED)
        Booking->>Inventory: POST /release (roomId, dates, quantity)
        Note over Booking,Inventory: Compensating transaction
        Inventory-->>Booking: 200
        Booking->>Booking: Update booking FAILED
        Booking-->>Client: 4xx BusinessException
    end
```

---

## 2. Inventory – Distributed Lock + DB Atomic Update

Chiến lược `distributed`: Redis lock theo key `lock:room:{roomId}:{date}`, bên trong critical section chỉ gọi một câu UPDATE xuống DB (atomic), không đọc entity lên Java rồi trừ (tránh race condition).

```mermaid
sequenceDiagram
    participant Client
    participant Inventory as Inventory Service
    participant Redis
    participant DB as PostgreSQL

    Client->>Inventory: POST /reserve (roomId, dates, quantity)
    Inventory->>Redis: tryLock("lock:room:101:2026-02-01")
    Redis-->>Inventory: acquired

    loop For each date in range
        Inventory->>DB: UPDATE room_availability<br/>SET available_count = available_count - :qty<br/>WHERE room_id=? AND date=? AND available_count >= :qty
        alt updated rows = 0
            DB-->>Inventory: 0 rows
            Inventory->>Redis: unlock()
            Inventory-->>Client: 4xx Insufficient availability
        end
        DB-->>Inventory: 1 row
    end

    Inventory->>DB: SELECT (for total price)
    DB-->>Inventory: price data
    Inventory->>Redis: unlock()
    Inventory-->>Client: 200 ReserveRoomResponse (RESERVED)
```

---

## 3. High-level Microservices

Vị trí các service và luồng chính: Client → Gateway → Services → DBs / Kafka.

```mermaid
flowchart LR
    subgraph Client
        Web[Web / Mobile]
    end

    subgraph Gateway
        Kong[Kong]
    end

    subgraph Services
        User[user-service]
        Catalog[catalog-service]
        Inventory[inventory-service]
        Booking[booking-service]
        Payment[payment-service]
        Notif[notification-service]
    end

    subgraph Data
        PG[(PostgreSQL)]
        Mongo[(MongoDB)]
        Redis[(Redis)]
        Kafka[Kafka]
    end

    Web --> Kong
    Kong --> User
    Kong --> Catalog
    Kong --> Inventory
    Kong --> Booking
    Kong --> Payment

    Booking --> Inventory
    Booking --> Payment
    Booking --> Kafka
    Catalog --> Mongo
    Catalog --> Redis
    Inventory --> PG
    Inventory --> Redis
    Notif --> Kafka
    Notif --> Mongo
```

---

## 4. CQRS (Catalog – Write vs Read)

Ghi vào PostgreSQL (write model), đồng bộ sang MongoDB (read model) qua event hoặc batch để search nhanh.

```mermaid
flowchart LR
    subgraph Write
        API_W[Catalog API]
        PG_W[(PostgreSQL)]
        API_W --> PG_W
    end

    subgraph Events
        K[Kafka]
    end

    subgraph Read
        Consumer[Catalog Consumer]
        Mongo[(MongoDB)]
        Search[Search API]
        Consumer --> Mongo
        Mongo --> Search
    end

    PG_W -.->|hotel/room updated| K
    K --> Consumer
```

---

Có thể copy các block Mermaid vào README hoặc dùng công cụ render (GitHub, GitLab, VS Code Mermaid extension) để xem diagram.
