# Ghi chú triển khai Saga Resilience (dễ đọc)

Tài liệu này tóm tắt **đã implement** trong code: Idempotency, Reserve TTL, Saga state + Recovery. Đọc kèm với `saga-resilience-solutions.md` để hiểu lý thuyết.

---

## Tổng quan flow

```
User tạo booking
    → Booking Service lưu booking (có ID), gửi idempotency key = "booking-{id}"
    → Bước 1: Reserve phòng (Inventory) – có cache idempotency
    → Bước 2: Thanh toán (Payment) – có cache idempotency
    → Bước 3: Confirm (xóa hold) + cập nhật CONFIRMED
```

- **Lỗi rõ ràng** (vd payment declined) → release phòng + FAILED → API trả 4xx/5xx.
- **Lỗi unclear** (503, timeout) → không release, không set FAILED → API trả **202 Accepted** + booking, message "Booking is being processed. Check status shortly." Recovery retry sau; khi xong thì CONFIRMED hoặc FAILED và notify. **API và trạng thái cuối nhất quán** (không còn "API báo failed nhưng 30 phút sau recovery success").

Mỗi bước đều ghi **saga_step** vào DB. Job định kỳ tìm booking “kẹt” (RESERVE_SENT / PAYMENT_SENT quá lâu) và **retry** với cùng idempotency key.

---

## 1. Idempotency (Chống gọi trùng)

**Mục đích:** Retry (timeout, lỗi mạng) không được khiến reserve hoặc payment chạy hai lần.

**Cách làm:** Mỗi request gắn **một key** (ví dụ `"booking-123"`). Lần đầu service xử lý và **lưu kết quả** theo key; lần sau cùng key thì **chỉ trả lại kết quả đã lưu**, không thực thi lại.

### 1.1 Inventory Service

| Thứ | Nội dung |
|-----|----------|
| **DTO** | `ReserveRoomRequest` có thêm field `idempotencyKey` (tùy chọn). |
| **Lưu trữ** | Bảng `reserve_idempotency` (idempotency_key, response_json, created_at). **Cùng transaction** với reserve: check/ghi trong cùng `@Transactional` với `strategy.reserve()`. **Redis (tùy chọn):** cache đọc nhanh – đọc Redis trước, miss/error thì đọc DB; sau khi ghi DB thì warm Redis (best-effort). Config: `inventory.reservation.idempotency-redis-cache`. |
| **File** | `RoomReservationService.reserveRoom()` @Transactional: getCachedResponse (Redis → DB) → nếu có trả về; không thì reserve → ghi idempotency vào DB (cùng tx) → save holds → warm Redis. |

**Luồng:**

1. Request tới với `idempotencyKey = "booking-123"`.
2. Đọc cache: Redis trước (nếu bật); miss/error thì đọc DB. Nếu có → trả response đã lưu (không gọi strategy).
3. Nếu chưa có → gọi strategy reserve → ghi response vào `reserve_idempotency` (cùng tx) → save holds. Nếu ghi idempotency lỗi → **rollback** cả reserve (không double reserve). Sau đó best-effort warm Redis.

### 1.2 Payment Service

| Thứ | Nội dung |
|-----|----------|
| **DTO** | `ProcessPaymentRequest` có thêm field `idempotencyKey` (tùy chọn). |
| **Lưu trữ** | Bảng `idempotency_store` (idempotency_key, response_json, created_at). Migration: `V2__create_idempotency_store.sql`. **Redis (tùy chọn):** cache – đọc Redis trước, miss/error thì đọc DB; sau khi lưu DB thì warm Redis (best-effort). Config: `payment.idempotency-redis-cache`. Dependency: `spring-boot-starter-data-redis`. |
| **File** | `PaymentService.processPayment()` – getCachedResponse(Redis → DB); nếu có key thì trả response đã lưu; cuối method: lưu vào `idempotency_store` rồi warm Redis. |

**Luồng:** Giống Inventory: DB là source of truth; Redis chỉ cache. Lần đầu xử lý và lưu DB; lần sau cùng key đọc cache (Redis rồi DB) và trả lại. Đọc idempotency (DB) lỗi → 503, không xử lý lại.

### 1.3 Booking Service (client)

| Thứ | Nội dung |
|-----|----------|
| **Tạo key** | Booking được **lưu trước** (có `id`) để dùng làm key: `idempotencyKey = "booking-" + booking.getId()`. |
| **Gửi key** | Gửi trong body của `ReserveRoomRequest` và `ProcessPaymentRequest` khi gọi Inventory và Payment. |

**Lưu ý:** `BookingService.createBooking()` đã gọi `bookingRepository.save(booking)` trước khi gọi orchestrator, nên khi vào orchestrator đã có `booking.getId()`. Trường `totalPrice` ban đầu set `BigDecimal.ZERO`, sau bước reserve mới cập nhật giá thật.

---

## 2. Reserve TTL (Hold có thời hạn – tránh phòng treo)

**Mục đích:** Nếu reserve xong mà Booking crash (chưa kịp payment/release) thì sau một thời gian (vd 15 phút) **tự động trả phòng**, không giữ chỗ mãi.

**Cách làm:** Mỗi lần reserve thành công, Inventory ghi bản ghi “hold” có **expires_at**. Job chạy định kỳ tìm hold hết hạn → tăng lại `available_count` và xóa hold. Khi booking **confirm** (sau payment thành công) thì xóa hết hold của booking đó để job không release nhầm.

### 2.1 Bảng và entity

| Thứ | Nội dung |
|-----|----------|
| **Bảng** | `reservation_holds`: booking_id, room_id, availability_date, quantity, **expires_at**, created_at. Migration: `V2__create_reservation_holds_table.sql`. |
| **Entity** | `ReservationHold.java`. |
| **Repository** | `ReservationHoldRepository`: `findByBookingId`, `findExpiredBefore(expiresAt)`, `deleteByBookingId`. |

### 2.2 Khi nào tạo hold?

- Trong `RoomReservationService.reserveRoom()`: sau khi strategy reserve thành công, gọi `saveReservationHoldsIfApplicable(request)`.
- Chỉ lưu hold nếu `idempotencyKey` có dạng `"booking-{số}"` (parse ra `bookingId`). Với mỗi ngày trong khoảng check-in → check-out, tạo một dòng hold với `expires_at = now + hold-ttl-minutes` (mặc định 15 phút).

### 2.3 Confirm (xóa hold)

- **Endpoint:** `POST /api/v1/inventory/reservations/confirm?bookingId=...`
- **Logic:** Xóa tất cả hold có `booking_id = bookingId`. Gọi **sau khi payment thành công** để job expiry không release phòng của booking đã confirm.

### 2.4 Release (compensate)

- Khi Booking gọi release (payment fail hoặc lỗi), gửi thêm **bookingId**.
- Inventory: vừa **tăng lại available_count** (như cũ) vừa **xóa hold** theo bookingId, tránh job expiry trả phòng trùng.

### 2.5 Job hết hạn (expiry job)

- **Chạy:** Mỗi `expiry-job-interval-ms` (mặc định 60 giây).
- **Việc làm:** Lấy danh sách hold có `expires_at < NOW()`. Với mỗi hold: tăng lại `available_count` cho (room_id, availability_date) tương ứng, rồi xóa bản ghi hold.

**Cấu hình (inventory `application.yml`):**

- `inventory.reservation.hold-ttl-minutes: 15`
- `inventory.reservation.expiry-job-interval-ms: 60000`

---

## 3. Saga state + Recovery job

**Mục đích:** Biết booking đang ở bước nào (đã gửi reserve chưa, đã gửi payment chưa). Sau crash hoặc timeout, **job recovery** tìm booking “kẹt” và retry với cùng idempotency key.

### 3.1 Cột saga_step trong bảng bookings

- **Migration:** `V2__add_saga_step_to_bookings.sql` – thêm cột `saga_step` (VARCHAR).
- **Giá trị:**  
  `RESERVE_SENT` → `RESERVE_OK` → `PAYMENT_SENT` → (sau payment) `CONFIRMED` hoặc `FAILED`.

Orchestrator cập nhật **trước và sau** mỗi lần gọi ngoại vi:

- Trước gọi reserve: `saga_step = RESERVE_SENT`, save.
- Sau reserve OK: `saga_step = RESERVE_OK`, save.
- Trước gọi payment: `saga_step = PAYMENT_SENT`, save.
- Sau payment OK + confirm: `saga_step = CONFIRMED`, save.
- Bất kỳ lỗi/compensate nào: `saga_step = FAILED`, save.

### 3.2 Recovery job

| Thứ | Nội dung |
|-----|----------|
| **Class** | `SagaRecoveryJob.java` (booking-service). |
| **Chạy** | Mỗi `recovery-interval-ms` (mặc định 5 phút). |
| **Điều kiện “kẹt”** | `saga_step IN ('RESERVE_SENT', 'PAYMENT_SENT')` và `updated_at` cũ hơn `recovery-threshold-minutes` (mặc định 10 phút). |
| **Hành động** | Với mỗi booking kẹt: gọi `BookingOrchestrator.advanceStuckBooking(booking)`. |

### 3.3 advanceStuckBooking làm gì?

- Load lại booking trong transaction mới (tránh detached entity).
- Nếu `saga_step == RESERVE_SENT`: retry reserve (cùng idempotency key) → nếu OK thì set RESERVE_OK, rồi tiếp tục gửi payment trong cùng lần chạy.
- Nếu `saga_step == PAYMENT_SENT`: retry payment (cùng idempotency key) → nếu OK thì confirm + set CONFIRMED.
- Nếu retry vẫn lỗi **và lỗi rõ ràng** (vd payment declined): release room và set FAILED.
- Nếu lỗi **unclear** (503, timeout): không release, không set FAILED → job lần sau retry lại.

Nhờ idempotency, retry không gây double reserve / double charge.

### 3.4 Give-up (stuck quá lâu) – an toàn hơn

- Booking kẹt **lâu hơn** `recovery-give-up-minutes` (mặc định 24h) → gọi `giveUpStuckBooking`.
- **RESERVE_SENT:** Chắc chắn chưa reserve OK → **release phòng** + set FAILED. An toàn.
- **PAYMENT_SENT:** **Không biết** payment đã charge hay chưa → **không release phòng**, chỉ set FAILED. Nếu release khi user đã bị trừ tiền → "mất tiền, mất phòng". Cần **reconciliation thủ công**: kiểm tra Payment service theo bookingId, nếu đã charge thì confirm booking (và confirm hold), nếu chưa thì có thể release. Job log cảnh báo: "Booking X stuck at PAYMENT_SENT: manual reconciliation required".

**Cấu hình (booking `application.yml`):**

- `booking.saga.recovery-threshold-minutes: 10`
- `booking.saga.recovery-give-up-minutes: 1440` (24h)
- `booking.saga.recovery-interval-ms: 300000`

### 3.5 Rủi ro recovery và cách giảm

| Rủi ro | Cách đã làm |
|--------|-------------|
| Recovery confirm khi user đã book chỗ khác | Event `recoveryConfirmed: true` → notification nhắc user kiểm tra / hủy duplicate. |
| Give-up nhưng payment đã thành công | Give-up **không** release khi step = PAYMENT_SENT; chỉ set FAILED, reconciliation thủ công. |
| Retry gây double charge | Idempotency payment (DB source of truth, optional Redis cache); đọc idempotency lỗi → 503, không xử lý lại. |
| Retry gây double reserve | Idempotency reserve (DB source of truth, optional Redis cache); đọc idempotency lỗi → 503, không xử lý lại. |

---

## 4. File / config cần nhớ

| Tính năng | Service | File / config chính |
|-----------|---------|---------------------|
| Idempotency reserve | inventory | `RoomReservationService.reserveRoom()` @Transactional, bảng `reserve_idempotency`, cùng tx với reserve; optional Redis cache (`idempotency-redis-cache`) |
| Idempotency payment | payment | `PaymentService.processPayment()`, bảng `idempotency_store`, migration V2; optional Redis cache (`payment.idempotency-redis-cache`), `spring-boot-starter-data-redis` |
| Idempotency client | booking | `BookingOrchestrator` (tạo key `"booking-"+id`), `ReserveRoomRequest` / `ProcessPaymentRequest` có `idempotencyKey` |
| Hold + TTL | inventory | `ReservationHold`, `ReservationHoldRepository`, `RoomReservationService.saveReservationHoldsIfApplicable`, `confirmReservation`, `releaseExpiredHolds` (scheduled), migration V2 |
| Confirm / release với bookingId | inventory | `InventoryController`: `/reservations/confirm`, `/release?bookingId=...`; `InventoryClient` (booking) gọi confirm sau payment, truyền bookingId khi release |
| Saga step | booking | Cột `bookings.saga_step`, `BookingOrchestrator` set step trước/sau mỗi bước |
| Recovery | booking | `SagaRecoveryJob`, `BookingOrchestrator.advanceStuckBooking()`, `BookingRepository.findBySagaStepInAndUpdatedAtBefore` |

---

## 5. Đọc thêm

- **Lý thuyết chi tiết:** `docs/saga-resilience-solutions.md`
- **Thực tế công ty lớn:** `docs/saga-resilience-real-world.md`
- **Sơ đồ kiến trúc:** `docs/architecture-diagrams.md`

Nếu cần đào sâu từng class, mở từng file trong bảng trên và đọc comment trong code.
