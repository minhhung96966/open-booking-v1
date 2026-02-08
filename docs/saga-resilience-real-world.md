# Trong thực tế các công ty lớn handle các vấn đề Saga như thế nào

Tài liệu này bổ sung cho [saga-resilience-solutions.md](saga-resilience-solutions.md): nêu cách các công ty lớn (Stripe, PayPal, AWS, Uber, Netflix, Booking.com, airlines…) xử lý **cùng các vấn đề** đã liệt kê trong doc đó (idempotency, TTL, saga state, timeout/unknown). Dựa trên bài viết kỹ thuật, API docs và chia sẻ công khai – không phải "chỉ có một cách", mà là các mô hình phổ biến.

---

## 1. Idempotency (chống double reserve / double charge)

- **Chuẩn hóa:** Nhiều công ty **bắt buộc** hoặc **khuyến nghị** idempotency key cho mọi thao tác "charge tiền" hoặc "giữ tài nguyên".
- **Stripe / PayPal / Adyen:** API payment có header `Idempotency-Key`. Client (orchestrator) gửi key; lần đầu xử lý và lưu, lần sau trả lại kết quả cũ. Retry an toàn.
- **AWS (một số API):** ClientGeneratedToken hoặc idempotency token cho thao tác tạo tài nguyên (vd tạo request một lần).
- **Cách lưu:** DB bảng `idempotency_keys(key, response_body_hash, created_at)` với unique key, hoặc Redis `SET key NX EX 86400`. Key thường gắn với business entity (bookingId, orderId).

→ **Kết luận:** Idempotency được xem là **bắt buộc** cho payment và reserve; công ty lớn thường có chuẩn chung (header, format key) và middleware/filter xử lý trước khi vào business logic.

---

## 2. Reserve TTL (hold có thời hạn – tránh phòng treo)

- **Booking / travel / ticket:** "Giữ chỗ 10–15 phút" rất phổ biến. Sau đó tự động release nếu không thanh toán.
- **Cách làm thường gặp:**
  - **DB + scheduled job:** Bảng `holds(booking_id, expires_at, ...)`. Job (cron/quartz/JobRunr) mỗi 1–5 phút quét `expires_at < NOW()` → release (cộng lại inventory) và xóa/đánh dấu hold.
  - **Message queue delay:** Gửi message "release hold X" với delay 15 phút (RabbitMQ delay, SQS delay, Kafka + timer). Consumer nhận sau 15 phút và release. Nếu confirm trước đó thì cancel message (nếu queue hỗ trợ) hoặc message tới nhưng handler check "đã confirm rồi" thì no-op (idempotent).
- **Airlines / GDS:** PNR (Passenger Name Record) có thời hạn hold; hết hạn thì seat về pool. Logic tương tự: state "held" + `expires_at` + job hoặc event delay.

→ **Kết luận:** TTL hold là **chuẩn** trong travel/booking; implementation thường là DB + job định kỳ hoặc queue có delay, ít khi "giữ vĩnh viễn đến khi có người gọi release".

---

## 3. Saga state + recovery (crash giữa chừng, không biết bước nào)

- **State machine trong DB:** Nhiều công ty lưu trạng thái order/booking (CREATED → RESERVE_SENT → RESERVE_OK → PAYMENT_SENT → CONFIRMED / FAILED) trong chính bảng order/booking hoặc bảng `saga_state`. Mỗi bước cập nhật state trước/sau khi gọi service ngoài. Recovery job (hoặc startup job) quét state "stuck" (vd còn RESERVE_SENT quá lâu) → retry hoặc compensate (release, set FAILED).
- **Workflow engine:** Uber (Cadence, sau đó Temporal), Netflix (Conductor/Cadence) và một số công ty dùng **durable workflow** (Temporal/Cadence/Conductor): state từng bước do engine persist, retry và timer (TTL) built-in. Orchestrator là "workflow code" hoặc DAG, không phải BPMN. Idempotency và TTL vẫn thiết kế ở tầng service (activity gọi Inventory/Payment nhận key, reserve có expiry).
- **Event sourcing + saga:** Một số hệ thống ghi event (OrderCreated, ReserveRequested, ReserveCompleted, PaymentRequested, …); saga listener đọc event và quyết định bước tiếp theo hoặc compensate. State suy ra từ event log; recovery = replay hoặc job xử lý "saga chưa kết thúc".
- **Outbox pattern:** Để đảm bảo "ít nhất một lần" gửi request/event: ghi vào bảng outbox trong cùng transaction với cập nhật state; job đọc outbox và gửi (HTTP/Kafka); đánh dấu đã gửi sau khi thành công. Tránh "đã cập nhật state nhưng chưa gửi được" khi crash.

→ **Kết luận:** Công ty lớn thường có **một trong**: (1) state machine trong DB + recovery job, (2) workflow engine (Temporal/Cadence/Conductor), (3) event sourcing + saga. Outbox thường đi kèm khi cần at-least-once gửi event/request.

---

## 4. Timeout / response mất (reserve thành công nhưng client không biết)

- **Retry với cùng idempotency key:** Chuẩn: client retry với đúng key; service (Inventory/Payment) trả lại kết quả đã lưu → không double, client có thể tiến sang bước tiếp theo.
- **Status / query API:** Một số hệ thống có API "get status by idempotency key" hoặc "get order/booking by id": recovery job hoặc client gọi để biết "đã reserve chưa, đã pay chưa" rồi quyết định retry hay compensate.
- **Reconciliation job:** Định kỳ so sánh state giữa các hệ thống (vd booking state vs inventory holds vs payment) và sửa lệch (release hold orphan, mark booking failed, …). Dùng cho edge case và audit.

→ **Kết luận:** Idempotency + retry là nền tảng; "query status" và reconciliation bổ sung cho trường hợp phức tạp và tuân thủ.

---

## 5. Tóm tắt theo vấn đề (map với saga-resilience-solutions.md)

| Vấn đề (trong doc giải pháp) | Cách công ty lớn thường làm |
|------------------------------|----------------------------|
| **Double reserve / charge** | **Idempotency key** bắt buộc (header/body), lưu DB hoặc Redis; API payment (Stripe, PayPal…) đều hỗ trợ. |
| **Phòng treo (reserve xong crash)** | **Hold có TTL** (10–15 phút); **scheduled job** hoặc **queue delay** để tự release; confirm thì "convert hold → confirmed" hoặc cancel delay. |
| **Crash giữa chừng, không biết bước nào** | **State machine trong DB** (order/booking/saga_state) + **recovery job**; hoặc **workflow engine** (Temporal, Cadence, Conductor) persist state và retry/compensate. |
| **Timeout / response mất** | **Retry với cùng idempotency key**; có thể thêm **status API** và **reconciliation job** cho edge case. |

Không có "một stack duy nhất": có công ty chỉ DB + job + idempotency, có công ty dùng Temporal/Cadence, có công ty event sourcing. Điểm chung là **idempotency** và **TTL hold** gần như luôn có; **saga state + recovery** hoặc **workflow engine** tùy quy mô và độ phức tạp.

---

## 6. Ví dụ cụ thể: Agoda, Airbnb, Booking.com

### Agoda – Booking deduplication (chống double booking)

- **Vấn đề:** User không nhận confirm (delay/incident) → user nghĩ failed → book lại → **nhiều booking trùng** → hoàn tiền, chi phí hủy.
- **Giải pháp:** **Booking Deduplication** – trước khi tạo booking mới, check xem đã có booking “giống” chưa (cùng khách, cùng khách sạn/phòng, khoảng ngày trùng). Nếu có → **không tạo mới**, hiện popup: *"Is this a duplicate booking?"* cho user xác nhận.
- **Multi-DC:** Một DC central chứa dữ liệu từ mọi DC; service đọc cả **local + central** để khi chuyển traffic giữa DC vẫn detect duplicate. Dùng **hash (SHA-256)** cho dữ liệu tĩnh, **JSON** cho dữ liệu so sánh động (ngày, range). Một bảng thống nhất cho mọi sản phẩm (hotel, flight, car…).
- **Duplicate API request:** Dùng **unique key constraint + isolation level** trong SQL để chặn duplicate request trong vòng ~1 giây.

*Nguồn: [Agoda Engineering – Booking Deduplication Part 1 & 2](https://medium.com/agoda-engineering/booking-deduplication-how-agoda-manages-duplicate-bookings-across-multiple-data-centers-08ddbe9e22f1).*

### Airbnb – Idempotency cho Payment (tránh double charge)

- **Vấn đề:** SOA, nhiều service, request fail/timeout giữa chừng → retry → **double charge** hoặc **double payout**.
- **Giải pháp:** Framework idempotency **“Orpheus”** (library) dùng chung cho nhiều payment service.
  - **Idempotency key** do client gửi; **retry dùng cùng key** → server trả lại kết quả đã lưu, **không thực hiện lại**.
  - **Ba phase tách bạch:** **Pre-RPC** (ghi DB, không gọi mạng), **RPC** (chỉ gọi downstream, không ghi DB), **Post-RPC** (ghi kết quả DB). Pre + Post gói trong **một transaction** → commit một lần, tránh “đã charge nhưng chưa kịp ghi idempotency”.
  - **Retryable vs non-retryable:** 5XX / network → retryable; 4XX / validation → non-retryable. Phân loại sai có thể dẫn tới double payment hoặc “failed mãi”.
  - **Lease (row lock)** theo idempotency key, có **expiration**; retry chỉ được sau khi lease hết hạn (tránh race khi user bấm nhiều lần).
  - **Đọc/ghi idempotency chỉ trên master DB**, không dùng replica → tránh replica lag khiến retry “không thấy” kết quả đã lưu và charge lại.
- **Client:** Phải lưu idempotency key, retry với cùng key, không đổi payload; dùng exponential backoff + jitter.

*Nguồn: [Airbnb – Avoiding Double Payments in a Distributed Payments System](https://medium.com/airbnb-engineering/avoiding-double-payments-in-a-distributed-payments-system-2981f6b070bb).*

### Booking.com – Search + orchestration

- **Search / availability:** Sharding theo hotel ID, MapReduce, index availability local; search < 30ms cho vùng lớn.
- **Orchestration:** Chuyển từ **monolith** (1 layer xử lý mọi domain) sang **Apollo Federation** – nhiều subgraph (35+), mỗi domain (accommodation, flight, attraction…) tự quản; federation tổng hợp. Giảm bottleneck, tách team.

*Nguồn: Booking.com engineering blog, Apollo Federation.*

### So sánh nhanh với OpenBooking

| Khía cạnh | Agoda / Airbnb / Booking | OpenBooking (project này) |
|-----------|---------------------------|----------------------------|
| Chống double booking | Agoda: dedup trước khi tạo booking + popup; Airbnb: idempotency payment | Idempotency reserve + payment; recovery job |
| Chống double charge | Airbnb: Orpheus, Pre/RPC/Post, master-only | Idempotency key + DB/Redis, 503 khi store down |
| Hold / TTL | Travel: hold 10–15 phút + job release | Hold 15 phút + expiry job |
| Stuck / recovery | State machine + recovery job hoặc workflow engine | saga_step + SagaRecoveryJob, give-up sau 24h |
| Multi-DC | Agoda: central DC + local, query cả hai | Single region |
| Notify user khi “delayed confirm” | UX + email/SMS | BookingConfirmedEvent.recoveryConfirmed cho notification-service |
