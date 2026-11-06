# HƯỚNG DẪN THỰC HÀNH KHỞI CHẠY, DEMO E2E VÀ BỘ CÂU HỎI VẤN ĐÁP VIVA
## Ride-Hailing & Logistics Platform — Mã hiệu: SRS-RHL-002 v1.0

---

## 1. YÊU CẦU MÔI TRƯỜNG & PHẦN MỀM CẦN THIẾT

Trước khi bắt đầu, đảm bảo máy tính đã cài đặt đầy đủ các phần mềm sau:

1. **Java Development Kit (JDK)**: Phiên bản **Java 21 LTS** (Khuyến nghị Eclipse Temurin 21 hoặc OpenJDK 21).
   - Kiểm tra bằng lệnh: `java -version`
2. **Docker & Docker Compose**: **Docker Desktop** phiên bản mới nhất đang chạy ở chế độ Linux Containers.
   - Kiểm tra bằng lệnh: `docker compose version`
3. **Node.js & npm**: **Node.js v20.x hoặc v22.x LTS** kèm npm.
   - Kiểm tra bằng lệnh: `node -v` và `npm -v`
4. **IntelliJ IDEA**: Bản **Community Edition** hoặc **Ultimate Edition** (2024.x trở lên).

---

## 2. QUY TRÌNH MỞ DỰ ÁN TRÊN INTELLIJ IDEA

1. **Mở Thư mục Gốc**:
   - Khởi động IntelliJ IDEA, chọn **Open** và trỏ tới thư mục gốc `ride-hailing-logistics`.
2. **Đồng bộ Gradle Tự động**:
   - Dự án đã tích hợp sẵn tệp cấu hình [.idea/gradle.xml](../.idea/gradle.xml). IntelliJ sẽ tự động nhận diện và đồng bộ cả 9 dự án con trong thư mục `apps/`.
   - Nếu IntelliJ hỏi *Trust Project*, chọn **Trust Project**.
3. **Kiểm tra Cấu hình SDK**:
   - Vào menu `File -> Project Structure -> Project`.
   - Mục **SDK**: Chọn `21` (JDK 21).
   - Mục **Language level**: Chọn `21 - Pattern matching, record patterns, virtual threads`.
4. **Bật Annotation Processing (cho Lombok)**:
   - Vào `File -> Settings -> Build, Execution, Deployment -> Compiler -> Annotation Processors`.
   - Tích chọn checkbox **Enable annotation processing** và nhấn **Apply**.
5. **Cấu hình Chạy Sẵn (.run)**:
   - Thư mục [.run/](../.run/) đã chứa sẵn 9 cấu hình chuẩn `Application` (tương thích cả IntelliJ Community lẫn Ultimate). Chúng sẽ tự động xuất hiện trên thanh công cụ Run/Debug của IntelliJ:
     - `01_ApiGateway`
     - `02_IdentityService`
     - `03_LocationService`
     - `04_PricingService`
     - `05_TripService`
     - `06_DispatchService`
     - `07_RealtimeService`
     - `08_PaymentService`
     - `09_WalletLedgerService`

---

## 3. KHỞI ĐỘNG HẠ TẦNG DOCKER

Mở terminal tại thư mục gốc và chạy lệnh sau để bật 3 dịch vụ nền tảng:

```bash
docker compose -f infra/docker-compose.yml up -d
```

Kiểm tra trạng thái containers:
```bash
docker compose -f infra/docker-compose.yml ps
```

**Kỳ vọng hiển thị:**
- `rhl-postgres`: Cổng `127.0.0.1:5436->5432/tcp` (Status: `Up (healthy)`)
- `rhl-redis`: Cổng `127.0.0.1:6380->6379/tcp` (Status: `Up (healthy)`)
- `rhl-kafka`: Cổng `127.0.0.1:9094->9094/tcp`, `9092->9092/tcp` (Status: `Up (healthy)`)

*Lưu ý*: Toàn bộ 6 databases PostgreSQL (`rhl_identity`, `rhl_pricing`, `rhl_trip`, `rhl_dispatch`, `rhl_payment`, `rhl_wallet_ledger`) và 6 Kafka topics nghiệp vụ đã được chuẩn bị sẵn sàng.

---

## 4. THỨ TỰ KHỞI ĐỘNG BACKEND SERVICES

Do hệ thống có các phụ thuộc kiểm tra chéo (Cross-Service Readiness), vui lòng khởi động 9 services trên IntelliJ hoặc terminal theo đúng thứ tự sau:

```text
Thứ tự 1: 02_IdentityService      (:8081) - Dịch vụ Định danh, Tài xế & Cấp phát JWT
Thứ tự 2: 03_LocationService      (:8082) - Dịch vụ Telemetry GPS & Redis Presence
Thứ tự 3: 04_PricingService       (:8083) - Dịch vụ Bảng giá & Báo giá Chuyến đi
Thứ tự 4: 05_TripService          (:8084) - Dịch vụ Quản lý Vòng đời Chuyến đi
Thứ tự 5: 06_DispatchService      (:8085) - Dịch vụ Điều phối & Lời mời Tài xế
Thứ tự 6: 07_RealtimeService      (:8086) - Dịch vụ WebSocket/STOMP & Kafka Consumer
Thứ tự 7: 08_PaymentService       (:8087) - Dịch vụ Nghĩa vụ Thanh toán & CASH
Thứ tự 8: 09_WalletLedgerService  (:8088) - Dịch vụ Sổ cái Kế toán kép & Ví Tài xế
Thứ tự 9: 01_ApiGateway           (:8080) - Cổng Reverse Proxy Tập trung
```

*Cách chạy nhanh bằng terminal Gradle (nếu không dùng IntelliJ Run Config)*:
```bash
# Chạy từng service trong cửa sổ terminal riêng biệt:
cd apps/identity-driver-service && .\gradlew.bat bootRun
cd apps/location-service && .\gradlew.bat bootRun
cd apps/pricing-service && .\gradlew.bat bootRun
cd apps/trip-service && .\gradlew.bat bootRun
cd apps/dispatch-service && .\gradlew.bat bootRun
cd apps/realtime-service && .\gradlew.bat bootRun
cd apps/payment-service && .\gradlew.bat bootRun
cd apps/wallet-ledger-service && .\gradlew.bat bootRun
cd apps/api-gateway && .\gradlew.bat bootRun
```

---

## 5. KHỞI ĐỘNG GIAO DIỆN FRONTEND (REACT / VITE)

Mở một terminal mới:
```bash
cd apps/rhl-web
npm install
npm run dev
```

Ứng dụng khởi động tại: **`http://localhost:3000`**

### Bảng Điều Hướng Đường Dẫn Web (Frontend Routes):
- **`/` hoặc `/dashboard`**: Bảng điều khiển dành cho Giảng viên & Kỹ sư (Developer Console, API Form Tester, Token Inspector, Live Radar GPS).
- **`/customer`**: Ứng dụng Khách hàng (Đặt xe, xem báo giá, theo dõi xe trên bản đồ, thanh toán cước).
- **`/driver`**: Buồng lái Tài xế (Đăng ký xe, bật ONLINE, nhận popup lời mời, cập nhật trạng thái đón/trả khách, xác nhận thu tiền mặt).
- **`/finance`**: Bảng điều khiển Tài vụ (Giám sát sổ cái, phát hiện lệch số, tra cứu danh sách yêu cầu hoàn tiền).

---

## 6. KỊCH BẢN DEMO END-TO-END 6 BƯỚC

Mở hai cửa sổ trình duyệt song song (ví dụ: Chrome thường cho Khách hàng và Chrome Ẩn danh cho Tài xế).

### Bước 1: Đăng Nhập & Chuyển Đổi Vai Trò Nhanh (Authentication)
1. Tại giao diện `http://localhost:3000`, sử dụng **1-Click Demo Persona** trên góc phải hoặc thanh điều hướng:
   - Cửa sổ 1: Chọn vai trò **Customer** (`customer.demo@example.com`).
   - Cửa sổ 2: Chọn vai trò **Driver** (`driver.demo@example.com`).
2. Mở **Developer Console** (bấm tổ hợp `Ctrl + \`` hoặc nút Floating Drawer):
   - Quan sát tab **Token Inspector**: Token JWT RS256 giải mã trực tiếp `roles`, `permissions`, thời gian hết hạn hợp lệ.

### Bước 2: Tài Xế Bật Trạng Thái ONLINE (Driver Presence & Telemetry)
1. Trên Cửa sổ Tài xế, điều hướng tới `/driver`.
2. Kiểm tra trạng thái: Tài xế đã được phê duyệt hồ sơ (`APPROVED`) và có xe máy/ô tô hoạt động (`ACTIVE`).
3. Gạt công tắc **Chế độ Trực tuyến (Go ONLINE)**:
   - Location Service gọi M2M sang Identity Service xác thực tính đủ điều kiện (`eligible = true`).
   - Redis GEO lưu vị trí tài xế, khởi tạo key presence TTL 90 giây và sinh `sessionId`.
4. Mở tab `/dashboard`: Bấm thẻ **Location Radar** -> Bấm **Tìm tài xế lân cận** -> Tọa độ tài xế xuất hiện trên radar!

### Bước 3: Khách Hàng Yêu Cầu Báo Giá & Đặt Chuyến (Booking & Fare Quote)
1. Trên Cửa sổ Khách hàng, điều hướng tới `/customer`.
2. Nhập điểm đón (Pickup) và điểm đến (Dropoff) (ví dụ: Chợ Bến Thành -> Nhà hát Thành phố).
3. Bấm **Xem Báo Giá (Get Fare Quote)**:
   - Pricing Service tính toán tất định theo cước cơ sở + số km, trả về `FareQuoteResponse` có `quoteId` bất biến và thời hạn 300 giây.
4. Bấm **Đặt Xe Ngay (Request Ride)**:
   - Trip Service tạo chuyến đi ở trạng thái `MATCHING`, kích hoạt Dispatch Service.

### Bước 4: Lời Mời Nhận Chuyến Qua Kafka & WebSocket (Dispatch Matching)
1. Dispatch Service quét bán kính lân cận, tìm thấy tài xế đang trực tuyến từ Bước 2.
2. Dispatch tạo `DriverOffer` và ghi sự kiện vào Transactional Outbox.
3. Outbox Publisher đẩy tin vào Kafka topic `rhl.dispatch.offer.v1`.
4. Realtime Service tiêu thụ sự kiện từ Kafka, kiểm tra TTL, đẩy gói tin STOMP tới `/user/{driverId}/queue/offers`.
5. **Trên Cửa sổ Tài xế (`/driver`)**: Popup lời mời cuốc xe lập tức xuất hiện kèm âm báo và đồng hồ đếm ngược 15 giây!

### Bước 5: Tiến Trình Chuyến Xe & Thu Hồi Phòng Định Vị (Trip FSM & Room Revocation)
1. Tài xế bấm **Chấp nhận chuyến (Accept Offer)**:
   - Chuyến đi chuyển trạng thái: `ACCEPTED`.
2. Tài xế thực hiện tuần tự các bước tiến trình trên giao diện:
   - Bấm **Đã đến điểm đón** -> Trạng thái: `ARRIVED`.
   - Bấm **Bắt đầu chở khách** -> Trạng thái: `IN_TRIP`.
   - Cửa sổ Khách hàng tự động cập nhật thời gian thực theo từng bước qua STOMP `/user/{customerId}/queue/trips`.
3. Tài xế bấm **Hoàn thành chuyến xe** -> Trạng thái: `COMPLETED`:
   - Lệnh gọi tức thời HTTP thu hồi phòng kết hợp sự kiện Kafka `TRIP_COMPLETED` kích hoạt **P0 Room Revocation**.
   - Phòng định vị `/topic/trips.{tripId}.location` bị đóng vĩnh viễn, ngăn chặn tuyệt đối rò rỉ dữ liệu GPS sau khi trả khách.

### Bước 6: Thanh Toán Tiền Mặt & Bút Toán Sổ Cái (Cash Payment & Wallet Inbox)
1. Trên Cửa sổ Khách hàng (`/customer`), thẻ thanh toán cước xe xuất hiện với số tiền thẩm quyền lấy từ cuốc xe.
2. Khách hàng chọn phương thức **Tiền Mặt (CASH)** và bấm **Xác nhận thanh toán**:
   - Trạng thái chuyển sang `PROCESSING`.
3. Trên Cửa sổ Tài xế (`/driver`), thẻ **Xác Nhận Thu Tiền Mặt (Cash Collection)** xuất hiện đúng số tiền cần thu.
4. Tài xế bấm **Đã nhận đủ tiền mặt**:
   - Payment Service xác nhận giao dịch `SUCCEEDED`.
   - Payment Transactional Outbox đẩy sự kiện `PAYMENT_SUCCEEDED` vào Kafka topic `rhl.payment.events.v1`.
5. Wallet Ledger Service tiêu thụ sự kiện, ghi nhận nguyên tử vào Financial Inbox (`financial_inbox_events`).
6. Mở trang `/finance`:
   - Tab **Reconciliation** hiển thị trạng thái khớp sổ, không phát sinh lệch số.
   - Thẻ hoàn tiền hiển thị cảnh báo an toàn `REFUND_POLICY_PENDING` (chính sách chưa ban hành, hệ thống khóa tự động hoàn tiền để bảo vệ nguồn vốn sàn).

---

## 7. KIỂM CHỨNG KAFKA QUA TERMINAL CLI

Để chứng minh cho Giảng viên thấy Apache Kafka đang vận hành trực tiếp chứ không phải gọi mock in-memory, mở terminal và thực thi các lệnh CLI sau:

### Lệnh 1: Liệt kê các Topic thực tế
```powershell
docker exec -it rhl-kafka kafka-topics --bootstrap-server localhost:9092 --list
```
*Kết quả xác nhận có đủ 6 topics nghiệp vụ và DLT.*

### Lệnh 2: Bật Console Consumer lắng nghe sự kiện Lời mời
```powershell
docker exec -it rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.dispatch.offer.v1 --from-beginning
```

### Lệnh 3: Bật Console Consumer lắng nghe sự kiện Vòng đời Chuyến đi
```powershell
docker exec -it rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.trip.lifecycle.v1 --from-beginning
```

### Lệnh 4: Bật Console Consumer lắng nghe sự kiện Thanh toán
```powershell
docker exec -it rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.payment.events.v1 --from-beginning
```
*Khi thực hiện đặt xe và thanh toán trên Web, toàn bộ JSON payload sẽ xuất hiện ngay lập tức trên các cửa sổ terminal console consumer.*

---

## 8. XỬ LÝ SỰ CỐ KHỞI ĐỘNG THƯỜNG GẶP (TROUBLESHOOTING)

| Hiện tượng | Nguyên nhân | Biện pháp xử lý |
|---|---|---|
| Lỗi `Connection refused: localhost:5436` | Container PostgreSQL chưa bật | Chạy `docker compose -f infra/docker-compose.yml up -d postgres` |
| Lỗi `Connection refused: localhost:6380` | Container Redis chưa bật | Chạy `docker compose -f infra/docker-compose.yml up -d redis` |
| Lỗi `Connection to node -1 (localhost/127.0.0.1:9094) could not be established` | Container Kafka chưa sẵn sàng | Chờ Kafka đạt trạng thái `healthy` (khoảng 10s sau khi bật container), kiểm tra bằng `docker ps` |
| Lỗi `Port 8080 (hoặc 8081-8088) is already in use` | Tiến trình cũ chưa tắt | Mở PowerShell: `Get-Process -Id (Get-NetTCPConnection -LocalPort <PORT>).OwningProcess \| Stop-Process -Force` |
| Lỗi `Fatal: role postgres does not exist` | Dùng nhầm user mặc định | Hệ thống sử dụng user `rhl`, password `local`, database `rhl_identity` |
| Frontend báo lỗi `Failed to fetch` | Chưa bật API Gateway | Khởi động `01_ApiGateway` trên cổng 8080 |

---

## 9. BỘ 20 CÂU HỎI VẤN ĐÁP VIVA QUAN TRỌNG (KÈM ĐÁP ÁN NGẮN GỌN)

Dành cho chủ dự án chuẩn bị trả lời bảo vệ đồ án trước Hội đồng:

#### Q1: Tại sao hệ thống lại tách thành 9 Microservices mà không làm Monolith?
> **Đáp**: Tách theo Bounded Contexts (Định danh, Định vị, Tính giá, Chuyến đi, Điều phối, Realtime, Thanh toán, Sổ cái) giúp các phân hệ có đặc tính chịu tải khác nhau scale độc lập. Ví dụ: Location Service nhận GPS 1s/lần cần Redis in-memory cao tốc, trong khi Wallet Ledger cần tính toàn vẹn tuyệt đối của ACID.

#### Q2: Tại sao không đưa dữ liệu GPS của tài xế vào Apache Kafka?
> **Đáp**: Tọa độ GPS có tần suất 1-3s/lần từ hàng nghìn tài xế là dữ liệu trạng thái nhất thời (ephemeral telemetry). Đưa vào Kafka sẽ gây phình log đĩa vô ích và tăng độ trễ. Hệ thống tối ưu bằng cách lưu trực tiếp trên Redis GEO với script Lua và chỉ stream qua STOMP WebSocket.

#### Q3: Transactional Outbox Pattern giải quyết vấn đề gì trong dự án?
> **Đáp**: Giải quyết vấn đề "Dual-Write": không thể vừa commit database vừa gửi Kafka trong cùng một distributed transaction nếu không dùng 2PC nặng nề. Outbox lưu event vào bảng `outbox_events` trong cùng local transaction với dữ liệu nghiệp vụ, sau đó worker độc lập mới đọc và phát tin sang Kafka broker.

#### Q4: Cơ chế "Atomic Claim" ở bước 2 của Outbox hoạt động ra sao?
> **Đáp**: Sử dụng câu lệnh SQL nguyên tử `UPDATE ... SET next_retry_at = :claimUntil WHERE id = :id AND status = 'PENDING' AND next_retry_at <= :now`. Lệnh này kích hoạt khóa cấp dòng (Row-level Lock) của PostgreSQL; chỉ duy nhất 1 worker cập nhật thành công 1 dòng, worker khác nhận 0 dòng và bỏ qua, loại bỏ hoàn toàn race condition.

#### Q5: "Lease Fencing" trong Outbox là gì và tại sao cần thiết?
> **Đáp**: Khi worker cập nhật trạng thái `SENT` hoặc `FAILED`, câu lệnh bắt buộc kèm điều kiện `next_retry_at = :expectedClaimUntil`. Nếu worker bị trễ mạng quá 30 giây lease và bị worker mới reclaim, lệnh update của worker cũ sẽ bị từ chối an toàn (0 rows updated), không ghi đè trạng thái của new owner.

#### Q6: Tại sao Kafka Consumer ở Realtime Service cần kiểm tra TTL của Offer?
> **Đáp**: Nếu có nghẽn mạng Kafka, thông điệp lời mời có thể nằm trong queue một khoảng thời gian. Khi đến Consumer, nếu thời gian hiện tại đã quá `expiresAt`, Consumer drop ngay lập tức, không gửi popup ảo xuống tài xế để tránh việc tài xế bấm nhận nhưng server từ chối.

#### Q7: Consumer Idempotency được xử lý như thế nào để không mất message khi retry?
> **Đáp**: Áp dụng quy trình 2 pha: chỉ đọc (`containsKey`) trước khi xử lý; CHỈ ghi nhận `markProcessed` SAU KHI STOMP dispatch thành công. Nếu dispatch gặp lỗi mạng, listener ném ngoại lệ để Kafka container retry; ở lần retry tiếp theo event chưa bị coi là duplicate và được gửi lại an toàn.

#### Q8: Tại sao lại chọn `tripId` làm Partition Key cho Kafka topics?
> **Đáp**: Kafka bảo đảm thứ tự tuần tự nghiêm ngặt (Strict FIFO) trong cùng 1 partition. Sử dụng `tripId` làm key đảm bảo mọi sự kiện của một chuyến đi (`OFFER`, `ACCEPTED`, `ARRIVED`, `COMPLETED`) luôn vào cùng 1 partition theo đúng thứ tự phát sinh.

#### Q9: Cơ chế bảo vệ "Terminal Room Security" (P0) là gì?
> **Đáp**: Khi chuyến đi kết thúc (`COMPLETED`/`CANCELLED`), phòng định vị GPS bị đóng lập tức qua 2 tầng: gọi HTTP revoke đồng bộ và xử lý Kafka event bất đồng bộ. Lớp `HttpTripMembershipProvider` duy trì danh sách `terminalTrips` fail-closed, chặn đứng mọi cố gắng subscribe hoặc gửi GPS muộn.

#### Q10: Tại sao API Gateway lại dùng Spring Cloud Gateway WebFlux thay vì Spring MVC?
> **Đáp**: WebFlux sử dụng mô hình I/O bất đồng bộ phi chặn (Non-blocking Reactive I/O trên nền Netty EventLoop), cho phép 1 luồng xử lý đồng thời hàng nghìn kết nối mà không bị cạn kiệt Thread Pool như mô hình thread-per-request của Tomcat.

#### Q11: Xác thực JWT RS256 phi tập trung (Decentralized Verify) tại Gateway mang lại lợi ích gì?
> **Đáp**: Gateway chỉ cần nạp khóa công khai `public.pem` tại chỗ để giải mã và kiểm tra chữ ký số của token mà không cần gọi mạng hoặc truy vấn database của Identity Service, giúp giảm thiểu độ trễ xuống mức tối đa.

#### Q12: Session Fencing trong Location Service ngăn chặn lỗi gì?
> **Đáp**: Ngăn chặn hiện tượng giật lùi tọa độ do gói tin trễ từ phiên cũ. Khi tài xế online, server cấp `sessionId`. Script Lua trên Redis kiểm tra `incomingSession == currentSession`; nếu gói tin trễ từ phiên cũ đến muộn, Redis từ chối ngay với mã lỗi `-4` (HTTP 409).

#### Q13: Bút toán kép (Double-Entry Bookkeeping) trong Wallet Ledger hoạt động thế nào?
> **Đáp**: Mọi biến động tài chính đều tạo một bút toán gồm tối thiểu 2 dòng định khoản, bắt buộc tổng nợ bằng tổng có: $\sum \text{DEBIT} == \sum \text{CREDIT}$. Dữ liệu là append-only bất biến, không bao giờ sửa hoặc xóa dữ liệu lịch sử.

#### Q14: Tại sao khi cuốc xe Tiền Mặt (CASH) hoàn thành, ví tài xế chưa được tăng số dư khả dụng?
> **Đáp**: Vì chính sách hoa hồng sàn (`COMMISSION_POLICY_PENDING`) chưa được phê duyệt. Để bảo đảm an toàn tài chính, hệ thống lưu sự kiện vào Financial Inbox ở trạng thái chờ duyệt chính sách, không tự ý cộng tiền rút ảo.

#### Q15: Làm sao để bảo đảm Zero Double-Charging (không trừ tiền trùng) cho một chuyến đi?
> **Đáp**: Cơ sở dữ liệu PostgreSQL áp dụng Partial Unique Index `uk_payments_trip_single_success ON payment_transactions (trip_id) WHERE status = 'SUCCEEDED'`. Tầng DB bảo đảm vật lý chỉ có đúng 1 giao dịch thành công duy nhất cho mỗi `tripId`.

#### Q16: Tại sao client không được gửi trường `amount` khi khởi tạo thanh toán?
> **Đáp**: Để phòng chống tấn công sửa giá (Client Tampering). Payment Service gọi M2M sang Trip Service lấy giá trị thẩm quyền bất biến `estimatedFare` từ snapshot chuyến đi làm số tiền thanh toán chính thức.

#### Q17: Dead Letter Topic (DLT) được kích hoạt trong trường hợp nào?
> **Đáp**: Khi Kafka Consumer gặp thông điệp bị lỗi dữ liệu nghiêm trọng (Poison Pill) như sai định dạng JSON hoặc sau khi `DefaultErrorHandler` đã retry 3 lần mà service downstream vẫn lỗi; thông điệp được đẩy vào topic `.dlt` để không làm nghẽn partition.

#### Q18: Tại sao lại dùng Docker Compose cho môi trường phát triển?
> **Đáp**: Đảm bảo tính nhất quán môi trường (Infrastructure as Code). Bất kỳ ai mở dự án chỉ cần 1 lệnh `docker compose up -d` là có đủ PostgreSQL 15, Redis 7 và Kafka KRaft với cấu hình mạng giống hệt nhau, không cần cài đặt rườm rà.

#### Q19: Sự khác biệt giữa `At-Least-Once` và `Exactly-Once` trong kiến trúc này là gì?
> **Đáp**: Hệ thống đảm bảo `At-Least-Once` delivery trên đường truyền mạng kết hợp với cơ chế `Idempotency` (khử trùng theo `eventId` và Unique Constraints) ở tầng Consumer và Database để đạt được kết quả ngữ nghĩa tương đương `Exactly-Once Processing`.

#### Q20: Điểm mạnh nhất về mặt an toàn bảo mật của dự án là gì?
> **Đáp**: Nguyên tắc phòng thủ theo chiều sâu (Defense-in-Depth): Phân quyền RBAC tại Gateway, Controller và Service proxy; mã hóa mật mã bất đối xứng RS256; M2M secret so sánh hằng thời gian chống timing attack; chặn hoàn toàn route `/internal/**` từ bên ngoài; và khóa cứng các cổng chính sách tài chính ở tầng backend.
