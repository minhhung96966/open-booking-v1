# Ghi chú về JobRunr (tổng hợp đã thảo luận)

---

## 1. JobRunr là gì?

- **Thư viện Java** xử lý **background job** (công việc nền): chạy task bất đồng bộ, lên lịch, retry.
- **Không có BPMN**, không có diagram – mọi thứ viết bằng **code** (lambda, method).
- Job được **lưu vào DB hoặc Redis** (StorageProvider) → durable, không mất khi restart app.
- Nhiều worker (BackgroundJobServer) cùng poll storage và lấy job để chạy → distributed.

**Các loại job:**
- **Fire-and-forget:** Chạy ngay một lần.
- **Scheduled / delayed:** Chạy sau X phút hoặc tại thời điểm cố định.
- **Recurring (CRON):** Chạy lặp theo lịch (vd mỗi phút).

---

## 2. Service crash rồi start lại – có chạy lại code không?

**Có.** JobRunr lưu job trong DB/Redis, nên:

- **Job đang chờ (scheduled):** Sau khi server start lại, worker tiếp tục poll; đến đúng thời điểm job sẽ được lấy ra và **chạy** (code trong job chạy lại như bình thường).
- **Job đang chạy dở khi crash:** JobRunr coi job đó “failed” hoặc “interrupted” (vì worker chết), sau khi có worker mới sẽ **retry** job đó theo cấu hình retry → **code trong job được chạy lại**.

→ Nói gọn: **Crash rồi start lại thì job vẫn được chạy** (đúng lịch hoặc retry), vì state nằm ở storage (DB/Redis), không nằm trong memory của process.

---

## 3. “Vậy nó cũng giống Camunda?”

**Giống ở chỗ:** Cả hai đều **persist state** và **sống sót sau restart** – không mất “công việc đang làm” khi process chết.

**Khác ở chỗ:**

| | **Camunda** | **JobRunr** |
|---|-------------|-------------|
| **Persist cái gì** | Trạng thái **workflow** (process instance: đang ở activity nào, biến nào) | **Từng job** (task đơn lẻ: chạy cái gì, lúc nào, retry ra sao) |
| **Sau restart** | Engine đọc lại process state → **tiếp tục đúng bước** trong một quy trình nhiều bước | Worker đọc lại job queue → **chạy/retry từng job** (mỗi job là một đơn vị độc lập) |
| **Saga / compensation** | Có sẵn (BPMN: boundary event, compensation subprocess) | **Không có** – bạn tự viết logic (vd schedule job “release room” sau 15 phút) |
| **BPMN / diagram** | Có | Không |

→ **Giống:** durable, crash-safe, “chạy lại được”.  
→ **Khác:** Camunda quản lý **cả quy trình nhiều bước** (workflow/saga); JobRunr quản lý **từng job** (queue + schedule + retry). JobRunr **không** thay thế Camunda cho bài “orchestration saga đa bước + compensate tự động”.

---

## 4. JobRunr có phải alternative của Camunda không?

**Không.** JobRunr là **job queue / scheduler**, Camunda là **workflow engine**:

- **Camunda:** Định nghĩa process (BPMN), nhiều bước, có compensate, có state “đang ở bước nào”.
- **JobRunr:** Định nghĩa “chạy hàm này lúc này, retry thế này” – không có khái niệm “bước 1, bước 2, nếu lỗi thì compensate bước 1”.

Dùng JobRunr để **hỗ trợ** saga (TTL, cleanup, retry) thì được; dùng thay Camunda cho full saga orchestration thì không đủ.

---

## 5. Dùng JobRunr để hỗ trợ Saga (không dùng BPMN)

Trong project có thể **giữ saga orchestrator thủ công** và dùng JobRunr cho:

1. **Reserve TTL (15 phút rồi release):**  
   Sau khi reserve thành công → `BackgroundJob.schedule(instant.plus(15, MINUTES), () -> releaseIfNotConfirmed(bookingId))`.  
   Nếu payment success và confirm → xóa job đã schedule (JobRunr cho phép delete).  
   → Dù service crash sau khi reserve, sau 15 phút job vẫn chạy (khi server đã start lại) và release phòng.

2. **Cleanup / recovery:**  
   Recurring job (CRON mỗi phút): tìm booking “stuck” (vd state = RESERVE_OK, updated_at quá lâu) → enqueue job “release room” hoặc “retry payment”.  
   → Giảm phòng treo, booking kẹt.

3. **Retry payment (hoặc reserve):**  
   Thay vì retry ngay trong Feign, enqueue job “processPayment(bookingId)”. JobRunr retry job khi fail → code trong job được chạy lại sau crash hoặc sau vài giây.

---

## 6. Tóm tắt một dòng

- **JobRunr:** Thư viện background job (queue + schedule + retry), persist trong DB/Redis → **crash rồi start lại vẫn chạy lại được** (giống Camunda ở tính “durable”), nhưng **không** phải workflow/saga engine, **không** có BPMN, **không** thay thế Camunda; dùng để **hỗ trợ** saga (TTL, cleanup, retry) khi không muốn BPMN.
