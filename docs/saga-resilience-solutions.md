# Giải pháp xử lý triệt để các vấn đề Saga (Distributed Transaction)

Tài liệu này nêu các giải pháp để xử lý **tất cả** các tình huống đã nêu: timeout, crash giữa chừng, retry gây double reserve, phòng treo. Áp dụng cho Saga Orchestration (thủ công hoặc Camunda).

---

## 1. Idempotency (Chống double reserve / double charge)

### Vấn đề
- Retry (Feign, Resilience4j) khi timeout → gọi reserve nhiều lần → Inventory trừ phòng nhiều lần.
- Tương tự payment: retry → trừ tiền nhiều lần.

### Giải pháp

**Nguyên tắc:** Mỗi “hành động” (reserve, payment) gắn với một **idempotency key** duy nhất. Service nhận request lần đầu → xử lý và lưu kết quả theo key; các lần sau cùng key → trả lại kết quả đã lưu, **không thực hiện lại**.

**Cách làm:**

| Bên | Trách nhiệm |
|-----|-------------|
| **Booking (Orchestrator)** | Tạo **một** key cho cả saga (vd: `bookingId` hoặc `sagaId = UUID`). Gửi key trong mọi request tới Inventory và Payment. |
| **Inventory** | Nhận `Idempotency-Key: {key}` (header hoặc trong body). Trước khi trừ phòng: `SELECT` hoặc check cache (Redis) theo key. Nếu đã có kết quả → trả lại kết quả đó (200, body giống lần trước). Nếu chưa → thực hiện reserve, lưu `(key, response)` (DB hoặc Redis, TTL 24h), rồi trả response. |
| **Payment** | Tương tự: nhận idempotency key, nếu đã xử lý key này rồi thì trả lại kết quả cũ (SUCCESS/FAILED), không gọi gateway lần hai. |

**Lưu ý:** Key nên gắn với **booking** (vd: `bookingId`) để một booking chỉ reserve một lần, pay một lần.

**Ví dụ flow:**
- Request 1: `Idempotency-Key: booking-123` → reserve → 200, `reservationId=1`.
- Request 2 (retry): `Idempotency-Key: booking-123` → Inventory thấy key đã xử lý → 200, `reservationId=1` (không trừ thêm phòng).

---

## 2. Reserve có TTL (Time-to-live) – Tránh phòng treo vĩnh viễn

### Vấn đề
- Reserve thành công nhưng Booking crash trước khi gọi payment → không ai gọi release → phòng bị giữ mãi.

### Giải pháp

**Nguyên tắc:** “Reserve” không phải giữ chỗ vĩnh viễn, mà chỉ **trong một khoảng thời gian** (vd: 15 phút). Hết hạn mà chưa có payment/confirm thì coi như hủy và **tự động release**.

**Cách làm:**

**Option A – Application level (job định kỳ)**

1. **Inventory:** Khi reserve, lưu thêm `reserved_at` (hoặc `expires_at = now + 15min`). Có bảng/cache kiểu `reservation_holds(booking_id, room_id, date, quantity, expires_at)`.
2. **Job (scheduled, mỗi 1–5 phút):**  
   `SELECT * FROM reservation_holds WHERE expires_at < NOW()`  
   → Với mỗi bản ghi: gọi logic “release” (tăng lại `available_count`), xóa hoặc đánh dấu đã release.
3. **Booking:** Khi payment success và confirm, gọi Inventory “confirm reservation” (chuyển từ “hold” sang “confirmed”, xóa hoặc gia hạn vĩnh viễn). Nếu không confirm trước `expires_at`, job sẽ release.

**Option B – Database level**

- Bảng `room_reservation_holds` có cột `expires_at`.
- Job chỉ cần: `UPDATE room_availability ra SET available_count = ra.available_count + rh.quantity FROM reservation_holds rh WHERE rh.expires_at < NOW() AND ...` rồi xóa `rh`.

**Kết quả:** Dù Booking down sau khi reserve, sau tối đa 15 phút phòng vẫn được trả lại.

---

## 3. Lưu trạng thái Saga (Saga state persistence) + Recovery job

### Vấn đề
- Crash giữa chừng → không biết “đã reserve chưa, đã pay chưa” → không biết cần compensate bước nào.

### Giải pháp

**Nguyên tắc:** Mỗi bước trong saga được ghi nhận vào DB (hoặc store có durability). Khi service restart hoặc job chạy, đọc state và quyết định: tiếp tục bước tiếp theo, hoặc chạy compensation.

**Cách làm:**

1. **Bảng saga_state (hoặc dùng bảng booking mở rộng):**
   - `booking_id`, `saga_id`, `current_step` (e.g. `RESERVE_SENT`, `RESERVE_OK`, `PAYMENT_SENT`, `PAYMENT_OK`, `CONFIRMED`, `FAILED`, `COMPENSATING`),
   - `reserve_completed_at`, `payment_completed_at`, `updated_at`, `version` (optimistic lock).

2. **Trong orchestrator (trước/sau mỗi bước):**
   - Trước khi gọi Inventory: lưu `current_step = RESERVE_SENT` (hoặc `IN_PROGRESS`).
   - Sau khi reserve thành công: `current_step = RESERVE_OK`, `reserve_completed_at = now()`.
   - Trước khi gọi Payment: `current_step = PAYMENT_SENT`.
   - Sau khi payment success: `current_step = PAYMENT_OK` → confirm → `CONFIRMED`.
   - Nếu payment fail: `current_step = FAILED`, rồi chạy compensation (release room), có thể set `COMPENSATING` → `FAILED`.

3. **Recovery job (định kỳ, vd mỗi phút):**
   - Tìm booking có `current_step IN ('RESERVE_SENT','PAYMENT_SENT')` và `updated_at` quá lâu (vd > 5–10 phút) → coi là “stuck”.
   - Với từng bản ghi:
     - Nếu `RESERVE_SENT`: có thể retry reserve (idempotency key = bookingId) hoặc nếu đã quá timeout (vd 15 phút) → compensate: gọi release (idempotent), set `FAILED`.
     - Nếu `PAYMENT_SENT`: hỏi Payment (status by idempotency key) hoặc retry payment; nếu không thể → compensate: release room, set `FAILED`.

4. **Sau khi restart:** Có thể có “startup job” quét `RESERVE_OK` chưa gọi payment, hoặc `PAYMENT_SENT` chưa có kết quả → tiếp tục hoặc compensate theo logic trên.

**Kết quả:** Biết chính xác đang ở bước nào → compensate đúng (chỉ release khi đã reserve), không bị “phòng treo” hoặc “không dám release vì không biết đã reserve chưa”.

---

## 4. Timeout và “Unknown” (Reserve thành công nhưng response mất)

### Vấn đề
- Inventory đã trừ phòng nhưng response bị mất (timeout, mạng) → Booking nghĩ reserve fail → có thể retry (double reserve nếu không idempotent) hoặc không retry (phòng treo nếu không có TTL).

### Giải pháp

- **Idempotency (mục 1):** Retry với cùng key → Inventory trả lại kết quả đã lưu → không trừ lần hai; Booking nhận được “success” và chuyển sang bước payment.
- **Saga state (mục 3):** Trước khi gọi reserve, set `RESERVE_SENT`. Nếu timeout, state vẫn là `RESERVE_SENT`. Recovery job có thể:
  - Gọi Inventory “get status by idempotency key” (nếu API hỗ trợ) để biết đã reserve chưa.
  - Hoặc retry reserve với cùng key: nếu đã reserve thì nhận 200 + kết quả cũ; nếu chưa thì reserve lần đầu. Sau đó cập nhật state `RESERVE_OK` và tiếp tục payment.

**Kết quả:** Dù response mất, retry + idempotency + state giúp không double reserve và vẫn tiến tới payment hoặc fail có kiểm soát.

---

## 5. Tóm tắt áp dụng vào từng tình huống

| Tình huống | Giải pháp chính | Bổ sung |
|------------|------------------|---------|
| Payment fail (bình thường) | Saga compensate (đã có) | - |
| Retry gây double reserve | **Idempotency key** (reserve, payment) | - |
| Reserve xong, response mất | **Idempotency** + **Saga state** + recovery job (retry hoặc hỏi status) | - |
| Reserve xong, Booking crash | **Saga state** + **Reserve TTL** + **recovery/cleanup job** | Release khi TTL hết hoặc detect stuck |
| Payment timeout / crash sau reserve | **Saga state** + recovery job (retry payment hoặc compensate) | Reserve TTL để không treo quá lâu |

---

## 6. Thứ tự triển khai gợi ý

1. **Idempotency** cho reserve (và payment): ít thay đổi nghiệp vụ, chặn được double reserve/charge ngay.
2. **Reserve TTL + job release:** giảm phòng treo mạnh, đơn giản về mặt ý tưởng.
3. **Saga state + recovery job:** cần thêm bảng/entity và cập nhật orchestrator; sau đó có thể xử lý mọi trạng thái “stuck” và crash giữa chừng.

Sau ba nhóm này, Saga trong project sẽ xử lý được **đầy đủ** các vấn đề đã nêu (timeout, crash, retry, phòng treo). Camunda có thể thay phần “orchestrator + state + timer” bằng BPMN, nhưng **idempotency** và **TTL/reserve** vẫn cần thiết kế ở tầng service (Inventory, Payment).

→ **Cách công ty lớn áp dụng trong thực tế:** xem [saga-resilience-real-world.md](saga-resilience-real-world.md).
