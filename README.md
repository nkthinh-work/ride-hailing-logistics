# RIDE-HAILING & LOGISTICS PLATFORM (RHL)

Hệ thống Microservices Nền tảng Điều phối Gọi xe và Vận chuyển Hàng hóa (Ride-Hailing & Logistics).  
Xây dựng theo chuẩn đặc tả phần mềm IEEE 830-1998 (Mã hiệu `SRS-RHL-002` v1.0).

---

## 1. GIỚI THIỆU TỔNG QUAN

Dự án triển khai kiến trúc phân tán (Distributed Microservices Architecture) chịu tải cao, hướng sự kiện (Event-Driven Architecture) với Apache Kafka KRaft, đảm bảo tính nhất quán dữ liệu qua Transactional Outbox/Inbox và giao tiếp thời gian thực hai chiều qua WebSocket/STOMP.

### Điểm nổi bật kỹ thuật:
- **Ngôn ngữ & Nền tảng**: Java 21 LTS, Spring Boot 4.x, Gradle đa dự án.
- **Cổng vào tập trung**: API Gateway phản ứng (Spring Cloud Gateway WebFlux) giải mã phi tập trung chữ ký số RS256 JWT tại cửa ngõ.
- **Cơ sở dữ liệu độc lập (Database-per-Service)**: 6 schema PostgreSQL 15 độc lập, quản lý tiến hóa qua Flyway Migrations bất biến.
- **Bộ nhớ không gian & Định vị thời gian thực**: Redis GEO 7.x với Lua Scripts nguyên tử, Session Fencing, Active Vehicle Protocol và bù trừ vận tốc GPS.
- **Xương sống hướng sự kiện**: Apache Kafka KRaft với Transactional Outbox 5 bước, câu lệnh Atomic Claim cấp database, Lease Fencing và Transactional Financial Inbox.
- **Sổ cái tài chính bất biến**: Mô hình Kế toán kép (Double-Entry Ledger) với Aggregate Root Ví tài xế, đối soát sai lệch số dư tự động và phân tách kế toán Tiền mặt (CASH) vs Trực tuyến (DIGITAL).
- **Giao diện đa phân hệ**: Ứng dụng React 19 + TypeScript + Vite phân tách 4 không gian trải nghiệm: Khách hàng (`/customer`), Tài xế (`/driver`), Bảng điều khiển kiểm thử API (`/dashboard`) và Bảng giám sát Tài vụ (`/finance`).

---

## 2. KIẾN TRÚC HỆ THỐNG & DANH MỤC DỊCH VỤ

Hệ thống gồm 9 dịch vụ backend và 1 ứng dụng web hoạt động trên các cổng mặc định:

| Cổng | Dịch vụ / Ứng dụng | Công nghệ chính | Trách nhiệm cốt lõi |
|---|---|---|---|
| `:8080` | [api-gateway](apps/api-gateway/) | Spring Cloud Gateway WebFlux | Reverse proxy, xác thực RS256 JWT, định tuyến HTTP/WebSocket, chặn `/internal/**`. |
| `:8081` | [identity-driver-service](apps/identity-driver-service/) | Spring Boot, PostgreSQL, Flyway | Xác thực người dùng, cấp phát JWT/Refresh Token rotation, hồ sơ tài xế, quản lý xe, audit trail. |
| `:8082` | [location-service](apps/location-service/) | Spring Boot, Redis GEO, Lua | Thu nhận GPS telemetry, kiểm tra chuỗi sequence, Session Fencing, tìm tài xế lân cận. |
| `:8083` | [pricing-service](apps/pricing-service/) | Spring Boot, PostgreSQL, Flyway | Động cơ tính cước tất định, quản lý phiên bản bảng giá, phát hành Fare Quote có TTL 300s. |
| `:8084` | [trip-service](apps/trip-service/) | Spring Boot, PostgreSQL, Kafka Producer | Quản lý vòng đời chuyến đi theo FSM 9 trạng thái, Transactional Outbox, thu hồi phòng GPS. |
| `:8085` | [dispatch-service](apps/dispatch-service/) | Spring Boot, PostgreSQL, Kafka Producer | Thuật toán điều phối đa vòng, quản lý vòng đời lời mời (Driver Offer), Transactional Outbox. |
| `:8086` | [realtime-service](apps/realtime-service/) | Spring Boot, WebSocket/STOMP, Kafka Consumer | Phân phối thông báo thời gian thực, khử trùng sự kiện 2 pha, drop offer quá TTL, room security. |
| `:8087` | [payment-service](apps/payment-service/) | Spring Boot, PostgreSQL, Kafka Producer | Quản lý nghĩa vụ thanh toán, xác nhận tiền mặt, adapter MoMo Sandbox, Transactional Outbox. |
| `:8088` | [wallet-ledger-service](apps/wallet-ledger-service/) | Spring Boot, PostgreSQL, Kafka Consumer | Sổ cái kế toán kép bất biến, Aggregate Ví tài xế, Financial Inbox, đối soát số dư. |
| `:3000` | [rhl-web](apps/rhl-web/) | React 19, TypeScript, Vite | Giao diện Single Page App cho Customer, Driver, Finance và Developer Console. |

---

## 3. TÀI LIỆU CHUYÊN SÂU DÀNH CHO GIẢNG VIÊN VÀ CHỦ DỰ ÁN

Toàn bộ tài liệu chính thức được lưu trữ tập trung trong thư mục [docs/](docs/):

1. [Tài liệu Thiết kế Kiến trúc Tổng thể (ARCHITECTURE_OVERVIEW.md)](docs/ARCHITECTURE_OVERVIEW.md): Bao gồm 6 sơ đồ Mermaid chi tiết cho từng luồng nghiệp vụ, giải thích từng bước và liên kết mở mã nguồn trực tiếp.
2. [Tài liệu Kiến trúc Hướng sự kiện với Apache Kafka (EVENT_DRIVEN_KAFKA.md)](docs/EVENT_DRIVEN_KAFKA.md): Phân tích chi tiết mô hình Kafka KRaft, Transactional Outbox, Transactional Inbox, Idempotency, DLT, Partitioning Key và các kịch bản sự cố.
3. [Hướng dẫn Chạy Thực hành & Vấn đáp Viva (DEMO_GUIDE.md)](docs/DEMO_GUIDE.md): Quy trình mở dự án bằng IntelliJ IDEA, chạy Docker, khởi động microservices, kịch bản demo 6 bước và bộ 20 câu hỏi vấn đáp cốt lõi.
4. [Ma trận Truy vết Yêu cầu SRS (REQUIREMENT_TRACEABILITY.md)](docs/requirements/REQUIREMENT_TRACEABILITY.md): Đối chiếu chi tiết 222 yêu cầu của bản đặc tả `SRS-RHL-002` với mã nguồn và kiểm thử tự động.

---

## 4. HƯỚNG DẪN KHỞI ĐỘNG NHANH (QUICKSTART)

### Yêu cầu môi trường:
- Java 21 LTS (OpenJDK / Eclipse Temurin).
- Docker Desktop và Docker Compose v2.
- Node.js 20+ và npm.
- IntelliJ IDEA (Community hoặc Ultimate).

### Bước 1: Khởi động Hạ tầng Docker
```bash
docker compose -f infra/docker-compose.yml up -d
```
Xác nhận 3 containers đang chạy ở trạng thái `healthy`:
- PostgreSQL 15: cổng `5436`
- Redis 7: cổng `6380`
- Apache Kafka 7.7.1 KRaft: cổng `9094` (host) và `9092` (mạng docker)

### Bước 2: Mở Dự án trong IntelliJ IDEA
1. Mở thư mục gốc `ride-hailing-logistics` bằng IntelliJ IDEA.
2. IntelliJ sẽ tự động nhận diện 9 dự án con Gradle thông qua tệp cấu hình chia sẻ [.idea/gradle.xml](.idea/gradle.xml).
3. Các cấu hình chạy sẵn đã được chuẩn bị trong thư mục [.run/](.run/) cho phép khởi chạy từng dịch vụ chỉ với một cú nhấp chuột.

### Bước 3: Khởi động Ứng dụng Frontend
```bash
cd apps/rhl-web
npm install
npm run dev
```
Truy cập giao diện tại: `http://localhost:3000`

---

## 5. BẢO TRÌ VÀ KIỂM THỬ TỰ ĐỘNG

Hệ thống được bảo vệ bởi **922 bài kiểm thử tự động (100% PASS)**:
- **Backend**: 875 tests phân tán qua 9 microservices (Unit tests, WebMvc security tests, PostgreSQL Testcontainers, Redis Testcontainers, EmbeddedKafka/Live Kafka integration tests).
- **Frontend**: 47 tests (Node native test runner, TypeScript typecheck, Vite production build).

```bash
# Kiểm tra toàn bộ frontend
cd apps/rhl-web && npm test && npm run build

# Kiểm tra một dịch vụ backend (Ví dụ: dispatch-service)
cd apps/dispatch-service && .\gradlew.bat test
```
