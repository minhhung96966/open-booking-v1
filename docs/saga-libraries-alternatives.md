# Thư viện / nền tảng hỗ trợ Saga (ngoài Camunda)

Các công cụ nổi tiếng giúp xử lý **state persistence**, **retry**, **compensation**, **timeout/TTL** cho Saga (hoặc giao dịch phân tán) trong hệ thống Java/Spring.

---

## JobRunr (jobrunr.io) – Background jobs, **không phải** workflow/BPMN

**JobRunr** là thư viện **xử lý job nền (background jobs)** trong Java: job được lưu vào DB (hoặc Redis), chạy bất đồng bộ, có retry, có lên lịch (schedule/delay). **Không** có BPMN, **không** có khái niệm saga/workflow sẵn – bạn viết toàn bộ bằng code (lambda, method).

### JobRunr là gì?

- **Fire-and-forget:** Gửi job chạy ngay (async).
- **Scheduled / delayed:** Chạy sau X phút hoặc tại thời điểm cố định (vd "sau 15 phút chạy task release room").
- **Recurring (CRON):** Job lặp theo lịch (vd mỗi phút chạy "cleanup stuck bookings").
- **Durable:** Job lưu trong DB/Redis → server restart không mất job.
- **Distributed:** Nhiều worker cùng lấy job từ storage.
- **Retry:** Job fail có thể retry với backoff.

### So với Camunda / Temporal

| | Camunda | Temporal | JobRunr |
|---|---------|----------|---------|
| Mục đích | Workflow BPMN | Durable workflow (code) | Background jobs |
| BPMN | Có | Không | Không |
| Saga/compensation | Có sẵn | Có sẵn | Không – bạn tự viết |
| Timer/delay | Có | Có | Có (scheduled job) |

→ **JobRunr không phải alternative trực tiếp của Camunda** cho orchestration saga đa bước. Nó là **job queue/scheduler**; bạn dùng JobRunr để **hỗ trợ** saga (TTL, retry, cleanup).

### Dùng JobRunr để hỗ trợ Saga (không BPMN)

- **Reserve TTL:** Sau khi reserve, schedule job chạy sau 15 phút: "nếu booking chưa CONFIRMED thì release room". Confirm xong thì xóa job.
- **Cleanup:** Recurring job mỗi phút tìm booking stuck → enqueue job compensate (release room).
- **Retry payment:** Enqueue job "process payment for booking X"; JobRunr retry khi fail.

**Kết luận:** Không muốn BPMN → giữ saga thủ công + **JobRunr** cho TTL, cleanup, retry job là đủ đơn giản. Cần full workflow state + compensate tự động thì dùng Temporal/Seata hoặc tự lưu saga state (JobRunr chỉ là executor).

---

## 1. **Temporal** (temporal.io)

- **Mô tả:** Nền tảng **durable workflow** (workflow có lưu trạng thái, chạy lại được sau khi crash). Code workflow bằng Java (hoặc Go, TS, …), engine đảm bảo thực thi đúng một lần từng bước, retry, timer.
- **Giải quyết:**
  - **Crash giữa chừng:** State workflow được persist → restart tiếp tục đúng bước.
  - **Timeout / retry:** Retry có cấu hình, backoff; timer (vd “chờ 15 phút rồi compensate”) built-in.
  - **Compensation:** Viết activity “release room”, gọi khi payment fail hoặc khi timer hết hạn.
- **Idempotency:** Bạn vẫn thiết kế activity (reserve, payment) nhận idempotency key; Temporal đảm bảo activity được gọi đúng và có thể retry an toàn (cùng workflow ID).
- **Ưu điểm:** Rất phổ biến, tài liệu tốt, Java SDK, self-hosted hoặc Temporal Cloud.
- **Nhược điểm:** Cần chạy Temporal server (hoặc dùng cloud).

---

## 2. **Seata** (seata.io) – Alibaba

- **Mô tả:** Framework **giao dịch phân tán** cho Java, tích hợp Spring Cloud. Hỗ trợ nhiều mode: **AT** (auto commit/rollback), **TCC**, **Saga**.
- **Giải quyết:**
  - **Saga mode:** Định nghĩa chuỗi service call + compensation tương ứng; Seata theo dõi trạng thái và gọi compensate khi có lỗi.
  - **State:** Trạng thái saga lưu ở TC (Transaction Coordinator); có recovery khi service restart.
- **Ưu điểm:** Không cần viết orchestrator thủ công; tích hợp Spring; dùng rộng rãi (đặc biệt Trung Quốc).
- **Nhược điểm:** Cần chạy Seata Server (TC, RM); Saga mode thường dùng file config JSON mô tả flow, ít linh hoạt như “workflow as code”.

---

## 3. **Narayana LRA** (Long Running Action) – Red Hat / JBoss

- **Mô tả:** Part of **Narayana** (JTA implementation). **LRA** là mô hình saga: mỗi participant đăng ký với coordinator, khi coordinator báo “cancel” thì participant gọi compensation.
- **Giải quyết:**
  - **Compensation:** Chuẩn LRA (JAX-RS annotation `@LRA`, `@Compensate`) → coordinator gọi compensate khi có lỗi hoặc timeout.
  - **State:** Coordinator lưu trạng thái LRA; có recovery.
- **Ưu điểm:** Chuẩn JVM, open source, tích hợp với Quarkus/JBoss.
- **Nhược điểm:** Mô hình hơi “JTA-style”; cần chạy coordinator; ít “workflow as code” như Temporal.

---

## 4. **Axon Framework** (axoniq.io)

- **Mô tả:** Framework **CQRS + Event Sourcing**, có khái niệm **Saga** (event-driven): Saga listen event, gửi command, có thể emit event “compensation” hoặc xử lý lỗi.
- **Giải quyết:**
  - **State:** Saga state lưu trong Axon (event store / saga store); crash/restart vẫn tiếp tục.
  - **Compensation:** Thiết kế bằng event/command (vd: nhận PaymentFailed → gửi ReleaseRoom command).
- **Ưu điểm:** Rất tốt nếu đã dùng CQRS/event sourcing; saga gắn với event.
- **Nhược điểm:** Cách nghĩ khác “orchestration” (command/event), cần event store; idempotency vẫn do bạn thiết kế ở handler.

---

## 5. **Apache Camel** (camel.apache.org)

- **Mô tả:** Integration framework, có **Saga EIP** (saga pattern): định nghĩa các bước và compensation trong route.
- **Giải quyết:**
  - **Compensation:** Saga API của Camel cho phép “chạy bước 1, bước 2, nếu lỗi thì compensate bước 1”.
  - **State:** Có in-memory hoặc persistence tùy cấu hình.
- **Ưu điểm:** Java, nhiều connector; quen thuộc trong integration.
- **Nhược điểm:** Saga Camel ít “durable” bằng Temporal/Seata; thường dùng trong một process, ít tập trung vào “distributed saga across services” như Seata/Temporal.

---

## 6. **Conductor** (Netflix / Orkes) – conductor.netflix.com, orkes.io

- **Mô tả:** **Workflow orchestration** (DAG of tasks). Định nghĩa workflow bằng JSON/UI; worker (Java, …) thực thi task. Orkes là bản thương mại/cloud.
- **Giải quyết:**
  - **State:** Workflow state lưu DB; có retry, timeout per task.
  - **Compensation:** Có thể mô hình task “compensate” và gọi khi task khác fail.
- **Ưu điểm:** Tách biệt workflow definition và worker; phù hợp multi-team.
- **Nhược điểm:** Cần chạy Conductor server; idempotency vẫn do worker/service đảm bảo.

---

## So sánh nhanh (theo vấn đề đã nêu)

| Công cụ        | State persistence | Retry / timeout | Compensation | Idempotency      | Ghi chú                    |
|----------------|-------------------|------------------|--------------|------------------|----------------------------|
| **Temporal**   | ✅ Durable         | ✅ Built-in       | ✅ Trong code | Bạn thiết kế key | Rất phù hợp saga phức tạp  |
| **Seata**      | ✅ TC lưu          | ✅ Có recovery    | ✅ Saga config| Bạn / AT mode    | Tích hợp Spring, Saga mode |
| **Narayana LRA** | ✅ Coordinator   | ✅ Có             | ✅ @Compensate| Bạn thiết kế     | Chuẩn JVM, LRA             |
| **Axon**       | ✅ Saga store      | ✅ Có             | ✅ Event/command | Bạn thiết kế   | Tốt nếu dùng CQRS/events   |
| **Camel Saga** | ⚠️ Tùy cấu hình   | ✅ Có             | ✅ Có         | Bạn thiết kế     | Integration, 1 process     |
| **Conductor**  | ✅ DB              | ✅ Per task       | ✅ Mô hình được | Bạn thiết kế   | Workflow DAG, worker riêng |

---

## Idempotency “thuần thư viện” (không full saga)

- **Spring / Java:** Không có một thư viện “de facto” chuẩn cho idempotency key; thường implement bằng:
  - **Redis:** Check/set key (vd `SET idempotency:key NX EX 86400`).
  - **DB:** Bảng `idempotency_keys(key, response_hash, created_at)` với unique key.
- Một số thư viện nhỏ / example: **idempotent-spring**, **custom filter + Redis**; không nổi tiếng bằng Temporal/Seata.

---

## Gợi ý chọn

- Cần **durable workflow, retry, timer, compensation** mạnh, “workflow as code”, chấp nhận chạy thêm server → **Temporal**.
- Đã dùng **Spring Cloud**, muốn “Saga có coordinator” sẵn, chấp nhận config JSON → **Seata** (Saga mode).
- Đã dùng **CQRS / event sourcing** → **Axon** (Saga event-driven).
- Cần chuẩn **JVM / LRA** → **Narayana LRA**.

Tất cả các công cụ trên **vẫn cần** bạn thiết kế **idempotency** ở tầng service (Inventory, Payment) khi gọi từ saga/workflow; engine chỉ giúp state, retry, timeout và gọi compensate.
