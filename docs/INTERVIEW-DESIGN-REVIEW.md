# Review thiết kế Saga / Booking – Chuẩn bị phỏng vấn

Tài liệu này tóm tắt **thiết kế hiện tại**, **điểm mạnh**, **hạn chế / trade-off** và **câu hỏi thường gặp** để trả lời khi interview.

---

## 1. Tổng quan kiến trúc

- **Pattern:** Saga **Orchestration** (Booking Service điều phối, gọi Inventory và Payment qua Feign).
- **Flow:** Create booking (persist) → Reserve (Inventory) → Payment (Payment) → Confirm (Inventory xóa hold) → Publish event.
- **Compensate:** Nếu reserve OK nhưng payment fail (hoặc lỗi rõ ràng) → release room + set FAILED.
- **Idempotency key:** `booking-{id}` (một key cho cả reserve và payment).

---

## 2. Các vấn đề đã xử lý và cách làm

| Vấn đề | Cách làm | Ghi chú |
|--------|----------|--------|
| **Double reserve / double charge** | Idempotency: Inventory (Redis), Payment (DB). Cùng key → trả response đã lưu. | Key = `booking-{id}`. |
| **Idempotency store down khi đọc** | **Không** coi là "chưa có key": throw **503** (ServiceUnavailableException). Client/recovery retry sau; không reserve/pay lại. | Tránh "đã xử lý rồi nhưng store down → retry → double". |
| **Phòng treo (reserve xong crash)** | Hold TTL 15 phút + **expiry job** (mỗi 1 phút) release hold hết hạn. Confirm = xóa hold. | Bảng `reservation_holds`, `expires_at`. |
| **Crash giữa chừng, không biết bước nào** | Cột **saga_step** (RESERVE_SENT → RESERVE_OK → PAYMENT_SENT → CONFIRMED/FAILED). **Recovery job** (mỗi 5 phút) tìm stuck (updated_at > 10 phút), retry với cùng idempotency key. | advanceStuckBooking; unclear failure không set FAILED. |
| **API báo failed nhưng 30 phút sau recovery success** | Lỗi **unclear** (503, timeout) → **không** release, **không** set FAILED → throw **BookingPendingUnclearException** → API trả **202 Accepted** + booking + message "Booking is being processed. Check status shortly." | Client hiển thị "processing"; khi recovery xong → CONFIRMED/FAILED + notify. |
| **Đã chuyển tiền nhưng compensate release** | Khi step = PAYMENT_SENT và lỗi unclear → **không** release (202). Khi **give-up** (stuck > 24h): PAYMENT_SENT **không** release, chỉ set FAILED; RESERVE_SENT mới release. | Tránh "user charged, no room". |
| **User tưởng failed, book chỗ khác, sau đó recovery confirm** | Event **recoveryConfirmed: true** → notification-service gửi email/push nhắc kiểm tra / hủy duplicate. | Giảm double book do hiểu nhầm. |

---

## 3. Điểm mạnh có thể nói trong interview

- **Idempotency** đúng chuẩn: key gắn booking, đọc lỗi → 503 (không xử lý lại).
- **Phân biệt lỗi rõ ràng vs unclear:** Unclear → 202, không release; rõ ràng → compensate + FAILED.
- **Saga state** rõ ràng (saga_step), recovery có **give-up** và **không release khi PAYMENT_SENT** give-up.
- **Hold TTL** tránh phòng treo; confirm xóa hold.
- **API nhất quán với trạng thái cuối:** 202 khi chưa biết kết quả, không trả "failed" rồi sau đó success.

---

## 4. Hạn chế / trade-off (thành thật khi hỏi)

| Điểm | Giải thích ngắn |
|------|------------------|
| **Reserve và idempotency** | **Đã sửa:** Idempotency reserve lưu trong DB, **cùng transaction** với reserve (check idempotency → reserve → ghi idempotency → holds). Ghi idempotency lỗi → rollback cả reserve. |
| **Payment idempotency và process payment không cùng transaction** | Tương tự: payment có thể thành công, ghi idempotency_store sau; crash → retry có thể double charge. Có thể gộp vào một transaction (insert payment + insert idempotency_store trong cùng transaction). |
| **Recovery không có "payment status API"** | Khi PAYMENT_SENT give-up ta không biết đã charge chưa; cần reconciliation thủ công. Nếu có API "get payment status by bookingId" thì recovery có thể query trước khi give-up. |
| **Một instance recovery job** | Nếu chạy nhiều instance, job chạy trên mọi node → có thể xử lý trùng. Có thể dùng distributed lock (Redis) cho job hoặc chỉ chạy job trên một instance (leader election). |
| **Circuit breaker fallback** | Khi circuit mở có thể đang ở PAYMENT_SENT; fallback **không** release khi step = PAYMENT_SENT (đã sửa). |

---

## 5. Câu hỏi thường gặp và cách trả lời

**Q: Tại sao Inventory dùng Redis idempotency còn Payment dùng DB?**  
A: Inventory đã có Redis (lock); Payment chỉ cần DB. Redis nhanh nhưng nếu Redis die thì mất idempotency; DB bền hơn. Có thể thống nhất sang DB (hoặc Redis + DB) tùy yêu cầu.

**Q: Redis die mà request đó đã xử lý rồi thì sao?**  
A: Retry sẽ không đọc được key → ta **throw 503** (không coi là "chưa có") → không reserve lại. Client/recovery retry sau khi Redis lên; lúc đó vẫn không có key (có thể đã expire) → có thể double reserve. Giảm rủi ro: dùng DB làm source of truth cho idempotency.

**Q: Đã chuyển tiền rồi mà API timeout, sau đó client compensate (release) thì sao?**  
A: Khi lỗi **unclear** ở PAYMENT_SENT ta **không** release; trả **202**. Chỉ khi lỗi **rõ ràng** (vd payment declined) mới release. Give-up (stuck > 24h) với PAYMENT_SENT cũng **không** release, chỉ set FAILED; reconciliation thủ công.

**Q: Recovery có nguy hiểm không?**  
A: Có rủi ro nếu give-up mà vẫn release khi đã charge. Ta giảm bằng: (1) PAYMENT_SENT give-up **không** release; (2) advanceStuckBooking khi unclear **không** set FAILED; (3) idempotency → retry an toàn; (4) recovery confirm thì publish event **recoveryConfirmed** để notify user.

**Q: API trả lỗi nhưng 30 phút sau recovery success có sai thiết kế không?**  
A: Đúng là sai nếu API trả "failed". Thiết kế hiện tại: khi **unclear** ta trả **202 Accepted** + booking, message "processing". Client không hiển thị "failed"; khi recovery xong thì CONFIRMED/FAILED và notify. API và trạng thái cuối nhất quán.

**Q: Flow API và flow retry có tách biệt không?**  
A: Có. Retry trong request (Resilience4j) và retry sau (recovery job) là hai lớp. Điểm quan trọng: khi không chắc kết quả (unclear) ta **không** trả "failed" mà trả 202 để hai flow nhất quán.

---

## 6. Checklist trước khi trả lời "thiết kế đã ổn chưa"

- [ ] Idempotency: có key, đọc lỗi → 503, không xử lý lại.
- [ ] Reserve TTL: hold + expiry job; confirm xóa hold.
- [ ] Saga step: lưu trước/sau mỗi bước; recovery tìm stuck, retry cùng key.
- [ ] Unclear vs rõ ràng: unclear → 202, không release; rõ ràng → compensate + FAILED.
- [ ] Give-up: RESERVE_SENT → release; PAYMENT_SENT → không release, reconciliation.
- [ ] Recovery confirm → publish event recoveryConfirmed để notify.
- [ ] Biết rõ trade-off: Redis idempotency, reserve/idempotency không cùng transaction, recovery single-instance.

Nếu trả lời được các ý trên và thành thật về hạn chế thì thiết kế đủ tốt cho interview; có thể nói "đã xử lý các case X, Y, Z; production có thể bổ sung DB idempotency cho Inventory và cùng transaction nếu cần".
