# TÀI LIỆU KIẾN TRÚC TỔNG THỂ HỆ THỐNG
## Ride-Hailing & Logistics Platform — Mã hiệu IEEE 830: SRS-RHL-002 v1.0

---

## 1. TỔNG QUAN KIẾN TRÚC TỔNG THỂ (SYSTEM ARCHITECTURE)

Hệ thống Ride-Hailing & Logistics (RHL) được thiết kế theo mô hình Microservices phân tán cao cấp, áp dụng mẫu thiết kế Database-per-Service, phân tách ranh giới rõ ràng (Bounded Contexts) và kết hợp hài hòa giữa giao tiếp đồng bộ HTTP REST/JSON cho các tác vụ nghiệp vụ xác định và giao tiếp bất đồng bộ qua Apache Kafka KRaft cho các sự kiện phân tán.

### Sơ đồ 1: Tổng thể Kiến trúc Toàn Hệ thống

```mermaid
flowchart TD
    subgraph ClientTier["Tầng Giao Diện Người Dùng (Client Tier)"]
        WebCustomer["Khách Hàng\n(/customer)"]
        WebDriver["Tài Xế\n(/driver)"]
        WebFinance["Tài Vụ\n(/finance)"]
        WebDev["Dev Console\n(/dashboard)"]
    end

    subgraph IngressTier["Tầng Cửa Ngõ Tập Trung (Ingress Tier)"]
        Gateway["API Gateway (:8080)\nSpring Cloud Gateway WebFlux\nOAuth2 RS256 JWT Decentralized Verify"]
    end

    subgraph CoreServices["Tầng Dịch Vụ Nghiệp Vụ (Microservices Tier)"]
        IdentitySvc["identity-driver-service (:8081)\nQuản lý Tài khoản, KYC & Xe"]
        LocationSvc["location-service (:8082)\nTelemetry GPS & Radar Lân Cận"]
        PricingSvc["pricing-service (:8083)\nĐộng cơ Tính cước & Báo giá"]
        TripSvc["trip-service (:8084)\nQuản lý Vòng đời Chuyến đi (FSM)"]
        DispatchSvc["dispatch-service (:8085)\nThuật toán Điều phối & Lời mời"]
        RealtimeSvc["realtime-service (:8086)\nWebSocket STOMP Broker"]
        PaymentSvc["payment-service (:8087)\nNghĩa vụ & Cổng Thanh toán"]
        WalletSvc["wallet-ledger-service (:8088)\nSổ cái Bút toán kép & Ví"]
    end

    subgraph StorageTier["Tầng Lưu Trữ & Hướng Sự Kiện (Data & Streaming Tier)"]
        DB_Identity[(PostgreSQL\nrhl_identity)]
        Redis_Location[(Redis 7 GEO\nrhl-redis :6380)]
        DB_Pricing[(PostgreSQL\nrhl_pricing)]
        DB_Trip[(PostgreSQL\nrhl_trip)]
        DB_Dispatch[(PostgreSQL\nrhl_dispatch)]
        DB_Payment[(PostgreSQL\nrhl_payment)]
        DB_Wallet[(PostgreSQL\nrhl_wallet_ledger)]
        KafkaBroker["Apache Kafka KRaft (:9094 host / :9092 docker)\nTopics: rhl.dispatch.offer.v1, rhl.trip.lifecycle.v1, rhl.payment.events.v1"]
    end

    ClientTier -->|HTTP REST / WebSocket| Gateway
    Gateway -->|/api/v1/auth/**, /api/v1/drivers/**| IdentitySvc
    Gateway -->|/api/v1/locations/**| LocationSvc
    Gateway -->|/api/v1/fares/**, /api/v1/admin/pricing-rules/**| PricingSvc
    Gateway -->|/api/v1/trips/**| TripSvc
    Gateway -->|/api/v1/offers/**| DispatchSvc
    Gateway -->|/ws, /ws/**| RealtimeSvc
    Gateway -->|/api/v1/payments/**, /api/v1/finance/**| PaymentSvc
    Gateway -->|/api/v1/wallets/**, /api/v1/finance/wallets/**| WalletSvc

    IdentitySvc --- DB_Identity
    LocationSvc --- Redis_Location
    PricingSvc --- DB_Pricing
    TripSvc --- DB_Trip
    DispatchSvc --- DB_Dispatch
    PaymentSvc --- DB_Payment
    WalletSvc --- DB_Wallet

    TripSvc -->|Transactional Outbox| KafkaBroker
    DispatchSvc -->|Transactional Outbox| KafkaBroker
    PaymentSvc -->|Transactional Outbox| KafkaBroker

    KafkaBroker -->|Consumer Group: rhl.realtime.dispatch.v1| RealtimeSvc
    KafkaBroker -->|Consumer Group: rhl.realtime.trip.v1| RealtimeSvc
    KafkaBroker -->|Consumer Group: rhl.wallet-ledger.v1| WalletSvc
```

### Các bước hoạt động và thành phần tương ứng:
1. **Cửa ngõ đơn (Single Ingress Point)**: [GatewaySecurityConfig.java](apps/api-gateway/src/main/java/com/rhl/gateway/config/GatewaySecurityConfig.java) tiếp nhận toàn bộ lưu lượng, giải mã chữ ký số RS256 qua khóa công khai và từ chối ngay lập tức token giả mạo hoặc hết hạn.
2. **Ngăn chặn lộ lọt nội bộ**: Cổng Gateway cấu hình chặn triệt để mọi yêu cầu từ mạng ngoài cố ý truy cập vào đường dẫn có tiền tố `/internal/**`.
3. **Database-per-Service**: Mỗi microservice sở hữu một schema cơ sở dữ liệu hoàn toàn độc lập trong PostgreSQL 15, nghiêm cấm quan hệ khóa ngoại xuyên cơ sở dữ liệu.
4. **Định vị không gian tách biệt**: [DriverLocationService.java](apps/location-service/src/main/java/com/rhl/location/services/DriverLocationService.java) lưu trữ tọa độ tần suất cao trực tiếp trên Redis GEO, không ghi xuống đĩa cơ sở dữ liệu quan hệ.
5. **Giao tiếp liên dịch vụ (Machine-to-Machine)**: Các lời gọi REST nội bộ được bảo vệ bởi [InternalAuthFilter.java](apps/identity-driver-service/src/main/java/com/rhl/identity/security/InternalAuthFilter.java) với cơ chế xác thực bí mật máy chủ và chống tấn công timing attack.
6. **Xương sống hướng sự kiện**: [docker-compose.yml](infra/docker-compose.yml) vận hành Apache Kafka single-node chế độ KRaft phân phối tin cậy các biến động nghiệp vụ.

---

## 2. LUỒNG ĐẶT CHUYẾN & ĐIỀU PHỐI (BOOKING & MATCHING FLOW)

Quá trình từ lúc khách hàng yêu cầu báo giá cho đến khi tìm được tài xế phù hợp được điều phối chặt chẽ qua 6 dịch vụ.

### Sơ đồ 2: Luồng Đặt Chuyến và Điều Phối Xe

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Khách Hàng
    participant Gateway as API Gateway (:8080)
    participant Pricing as Pricing Service (:8083)
    participant Trip as Trip Service (:8084)
    participant Dispatch as Dispatch Service (:8085)
    participant Location as Location Service (:8082)
    participant Kafka as Apache Kafka (:9094)
    participant Realtime as Realtime Service (:8086)
    actor Driver as Tài Xế

    Customer->>Gateway: POST /api/v1/fares/quote (pickup, dropoff, vehicleType)
    Gateway->>Pricing: Tính cước tất định
    Pricing-->>Customer: FareQuoteResponse (quoteId, estimatedFare, ttlSeconds=300)

    Customer->>Gateway: POST /api/v1/trips (quoteId, idempotencyKey)
    Gateway->>Trip: Tạo chuyến đi
    Trip->>Pricing: GET /internal/v1/fares/quotes/{quoteId}
    Trip->>Trip: Lưu Trip (status=MATCHING) & OutboxEvent
    Trip->>Dispatch: POST /internal/v1/dispatch/match (tripId, criteria)
    Trip-->>Customer: TripResponse (tripId, status=MATCHING)

    Dispatch->>Location: GET /internal/v1/locations/nearby (radius 1km/3km/5km)
    Location-->>Dispatch: Danh sách tài xế online & hợp lệ
    Dispatch->>Dispatch: Lọc tài xế bận, tạo DriverOffer & DispatchOutboxEvent
    Dispatch->>Kafka: Publish topic: rhl.dispatch.offer.v1 (key=tripId)
    Kafka->>Realtime: Consume offer event
    Realtime->>Realtime: TTL check & Dedup
    Realtime->>Driver: STOMP push /user/{driverId}/queue/offers
```

### Các bước hoạt động và thành phần tương ứng:
1. **Báo giá bất biến**: [FareQuoteService.java](apps/pricing-service/src/main/java/com/rhl/pricing/services/FareQuoteService.java) phát hành báo giá với TTL 300 giây, bảo vệ chống giả mạo giá cước từ client.
2. **Khởi tạo chuyến đi**: [TripService.java](apps/trip-service/src/main/java/com/rhl/trip/services/TripService.java) đối chiếu `quoteId` với Pricing Service, chuyển trạng thái chuyến đi sang `MATCHING`.
3. **Kích hoạt điều phối**: [MatchingOrchestrator.java](apps/dispatch-service/src/main/java/com/rhl/dispatch/services/MatchingOrchestrator.java) quét bán kính đa tầng (1km, 3km, 5km) gọi sang Location Service.
4. **Kiểm tra tài xế hợp lệ**: [InternalLocationController.java](apps/location-service/src/main/java/com/rhl/location/controllers/InternalLocationController.java) chỉ trả về các tài xế có trạng thái hoạt động thực tế trên Redis presence key.
5. **Khóa slot lời mời nguyên tử**: Ràng buộc [uk_driver_offers_single_pending_per_driver](apps/dispatch-service/src/main/resources/db/migration/V1__create_dispatch_tables.sql) đảm bảo 1 tài xế chỉ nhận tối đa 1 lời mời đang chờ xử lý tại một thời điểm.
6. **Xuất bản sự kiện Outbox**: [DispatchOutboxPublisher.java](apps/dispatch-service/src/main/java/com/rhl/dispatch/services/DispatchOutboxPublisher.java) đẩy sự kiện lời mời sang Kafka broker.
7. **Phân phối STOMP riêng tư**: [DispatchOfferKafkaListener.java](apps/realtime-service/src/main/java/com/rhl/realtime/consumers/DispatchOfferKafkaListener.java) kiểm tra TTL và gửi qua kênh WebSocket riêng của tài xế.

---

## 3. KIẾN TRÚC TRANSACTIONAL OUTBOX QUA KAFKA (KAFKA EVENT FLOW)

Mẫu thiết kế Transactional Outbox 5 bước đảm bảo độ tin cậy At-Least-Once, loại bỏ hoàn toàn hiện tượng Dual-Write và tách biệt ranh giới mạng khỏi database transaction.

### Sơ đồ 3: Luồng Xử Lý Transactional Outbox và Consumer Idempotency

```mermaid
flowchart TD
    subgraph ServiceDB["Database Giao Dịch Nội Bộ"]
        BusinessTx["1. Nghiệp Vụ Chính Commit\n(Trip/Offer/Payment + OutboxEvent)"] -->|Atomic Commit| OutboxTable[("Bảng {service}_outbox_events\n(Trạng thái: PENDING)")]
    end

    subgraph OutboxWorker["Outbox Scheduled Poller (5 Bước Chuẩn Mực)"]
        Poller["Publisher Poller Worker"]
        TxService["OutboxTxService (REQUIRES_NEW)"]
        
        Poller -->|2. Atomic Claim| TxService
        TxService -->|UPDATE next_retry_at = now + 30s\nWHERE status=PENDING AND next_retry_at <= now| OutboxTable
        
        Poller -->|3. Kafka Send mạng\n(Ngoài DB Transaction)| KafkaProducer["KafkaTemplate Publisher\n(acks=all, retries=3)"]
        KafkaProducer -->|Gửi tới Broker| KafkaTopic[("Apache Kafka Topic\nPartition Key: tripId / paymentId")]
        KafkaTopic -->|Broker Acknowledgment| KafkaProducer
        
        KafkaProducer -->|4. Broker ACK Thành Công| Poller
        Poller -->|5. markSent có Fencing\n(next_retry_at = expectedLease)| TxService
        TxService -->|UPDATE status=SENT| OutboxTable
    end

    subgraph KafkaConsumers["Kafka Consumer Engine"]
        Listener["@KafkaListener (AckMode.RECORD)"]
        KafkaTopic -->|Poll Message| Listener
        
        DedupCheck{"Kiểm Tra Đã Xử Lý?\n(Read-only check)"}
        Listener --> DedupCheck
        
        DedupCheck -- Đã tồn tại --> SkipDrop["Bỏ qua an toàn (Idempotent Drop)"]
        DedupCheck -- Chưa xử lý --> BusinessHandler["Thực hiện Xử lý Nghiệp vụ\n(STOMP Push / Financial Inbox)"]
        
        BusinessHandler --> SuccessRecord["Đánh Dấu markProcessed(eventId)"]
        SuccessRecord --> CommitOffset["Commit Kafka Offset"]
        
        BusinessHandler -- Lỗi Tạm Thời --> ThrowsEx["Ném Ngoại Lệ cho Container"]
        ThrowsEx --> RetryBackoff["Spring Kafka ErrorHandler Retry 3 lần"]
        RetryBackoff -- Vượt số lần retry --> DLT[("Topic Dead Letter Queue (.dlt)")]
    end
```

### Các bước hoạt động và thành phần tương ứng:
1. **Nguyên tử trong Local Transaction**: Entity nghiệp vụ và bản ghi Outbox được commit trong cùng 1 database transaction cục bộ, loại trừ rủi ro mất mát dữ liệu khi hệ thống gặp sự cố mất điện.
2. **PostgreSQL Atomic Claim**: [DispatchOutboxTxService.java](apps/dispatch-service/src/main/java/com/rhl/dispatch/services/DispatchOutboxTxService.java) thực hiện UPDATE trực tiếp tận dụng Row-level Lock của PostgreSQL, chặn đứng race condition giữa các worker đồng thời.
3. **Lease Owner Fencing**: Phương thức `markSent` và `markFailed` bắt buộc khớp `next_retry_at = expectedClaimUntil`, vô hiệu hóa an toàn worker cũ nếu đã bị hết hạn lease.
4. **Broker ACK tin cậy**: [KafkaTripEventPublisher.java](apps/trip-service/src/main/java/com/rhl/trip/services/KafkaTripEventPublisher.java) sử dụng `acks=all` và `enable.idempotence=true`.
5. **Khử trùng 2 pha ở Consumer**: [TripLifecycleKafkaListener.java](apps/realtime-service/src/main/java/com/rhl/realtime/consumers/TripLifecycleKafkaListener.java) chỉ ghi nhận `markProcessed` sau khi STOMP dispatch hoàn thành, bảo vệ event không bị mất khi gặp retry mạng.
6. **Cách ly tin độc (DLT)**: Các thông điệp sai cấu trúc không thể giải mã được chuyển vào Dead Letter Topic bởi [KafkaRealtimeConsumerConfig.java](apps/realtime-service/src/main/java/com/rhl/realtime/configs/KafkaRealtimeConsumerConfig.java).

---

## 4. ĐỊNH VỊ GPS VÀ WEBSOCKET STOMP (GPS & REALTIME STREAMING)

Dữ liệu định vị GPS tần suất cao (1-3 giây/lần) được cách ly trên Redis GEO và STOMP WebSocket, tuyệt đối không đưa vào Kafka nhằm bảo vệ hiệu năng broker.

### Sơ đồ 4: Luồng Định Vị GPS và Phát Tín Hiệu Thời Gian Thực

```mermaid
sequenceDiagram
    autonumber
    actor Driver as Tài Xế (Mobile/Web)
    participant Gateway as API Gateway (:8080)
    participant Location as Location Service (:8082)
    participant Redis as Redis 7 (:6380)
    participant Realtime as Realtime Service (:8086)
    actor Customer as Khách Hàng (Web)

    Driver->>Gateway: PUT /api/v1/locations/me (lon, lat, seq, timestamp, sessionId)
    Gateway->>Location: Chuyển tiếp tọa độ
    Location->>Location: Validate tọa độ [-85, 85], độ trễ & vận tốc phi thực tế (>150km/h)
    Location->>Redis: Thực thi script Lua nguyên tử: atomic_location_update.lua
    Redis->>Redis: Kiểm tra Session Fencing & Monotonic Sequence
    Redis-->>Location: Kết quả cập nhật thành công (GEOADD + SET TTL 90s)
    Location-->>Driver: HTTP 200 OK (DriverLocationResponse)

    Location->>Realtime: POST /internal/v1/realtime/publish/trip-location
    Realtime->>Realtime: Kiểm tra quyền xem phòng: TripRoomAuthorizationService
    Note over Realtime: Nếu chuyến đi COMPLETED/CANCELLED -> Fail-closed TERMINAL_REVOKED
    Realtime->>Customer: STOMP /topic/trips.{tripId}.location (DriverLocationPayload)
    Customer->>Customer: useTripLocation: Bù trừ thứ tự gói tin & Session Generation Reset
```

### Các bước hoạt động và thành phần tương ứng:
1. **Lọc dữ liệu ngoại lai**: [GeoUtils.java](apps/location-service/src/main/java/com/rhl/location/utils/GeoUtils.java) kiểm tra tính hợp lệ của tọa độ, độ chính xác (accuracy threshold) và cự ly nhảy vọt bất thường.
2. **Session Fencing trên Redis**: [atomic_location_update.lua](apps/location-service/src/main/resources/scripts/atomic_location_update.lua) từ chối gói tin từ phiên cũ với mã lỗi `-4` (HTTP 409 `STALE_SESSION_PACKET`).
3. **Phòng thủ đa tầng thu hồi phòng GPS**: [TripProgressionService.java](apps/trip-service/src/main/java/com/rhl/trip/services/TripProgressionService.java) gọi HTTP `revokeTripRoom` tức thời khi chuyến đi kết thúc, kết hợp với Kafka event thu hồi phòng bất đồng bộ.
4. **Khóa thẩm quyền kết thúc (Terminal Guard)**: [HttpTripMembershipProvider.java](apps/realtime-service/src/main/java/com/rhl/realtime/services/HttpTripMembershipProvider.java) duy trì danh sách `terminalTrips`, lập tức loại bỏ mọi gói tin GPS đến trễ của chuyến đi đã hoàn thành.
5. **Chống giật lùi tọa độ trên Web**: [useTripLocation.ts](apps/rhl-web/src/hooks/useTripLocation.ts) áp dụng hàm kiểm tra khóa hợp phần: mốc thời gian máy chủ `observedAt` kết hợp với số `sequenceNumber`.

---

## 5. LUỒNG THANH TOÁN TIỀN MẶT VÀ SỔ CÁI TÀI CHÍNH (PAYMENT & WALLET LEDGER)

Kiểm soát chặt chẽ nghĩa vụ thanh toán và bảo toàn nguyên tắc kế toán kép bất biến.

### Sơ đồ 5: Luồng Thanh Toán Tiền Mặt và Bút Toán Sổ Cái

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Khách Hàng
    actor Driver as Tài Xế
    participant Gateway as API Gateway (:8080)
    participant Trip as Trip Service (:8084)
    participant Payment as Payment Service (:8087)
    participant Kafka as Apache Kafka (:9094)
    participant Wallet as Wallet Ledger Service (:8088)

    Customer->>Gateway: POST /api/v1/payments/initiate (tripId, CASH, idempotencyKey)
    Gateway->>Payment: Khởi tạo thanh toán
    Payment->>Trip: GET /internal/v1/trips/{tripId} (Lấy số tiền thẩm quyền estimatedFare)
    Payment->>Payment: Lưu PaymentTransaction (status=PROCESSING)
    Payment-->>Customer: PaymentResponse (paymentId, amount, status=PROCESSING)

    Driver->>Gateway: POST /api/v1/payments/{paymentId}/cash/confirm
    Gateway->>Payment: Xác nhận đã thu tiền mặt
    Payment->>Payment: Kiểm tra Driver đúng là tài xế được gán chuyến
    Payment->>Payment: Chuyển status=SUCCEEDED, tạo OutboxEvent (PAYMENT_SUCCEEDED)
    Payment-->>Driver: HTTP 200 OK (PaymentStatus=SUCCEEDED)

    Payment->>Kafka: Publish topic: rhl.payment.events.v1 (key=paymentId)
    Kafka->>Wallet: Consumer Group: rhl.wallet-ledger.v1
    Wallet->>Wallet: Transactional Financial Inbox (Chống duplicate eventId)
    Wallet->>Wallet: Lưu inbox event (status=PENDING_POLICY)
    Note over Wallet: Tiền mặt CASH không tự cộng availableBalance vì COMMISSION_POLICY_PENDING
```

### Các bước hoạt động và thành phần tương ứng:
1. **Giá trị thẩm quyền bất biến**: [PaymentService.java](apps/payment-service/src/main/java/com/rhl/payment/services/PaymentService.java) lấy giá cước trực tiếp từ Trip snapshot, client không được truyền số tiền.
2. **Chống thanh toán trùng lặp (Zero Double-Charging)**: Ràng buộc [uk_payments_trip_single_success](apps/payment-service/src/main/resources/db/migration/V1__create_payment_tables.sql) đảm bảo đúng 1 giao dịch thành công duy nhất cho mỗi cuốc xe.
3. **Phân quyền xác nhận tiền mặt**: [DriverPaymentController.java](apps/payment-service/src/main/java/com/rhl/payment/controllers/DriverPaymentController.java) xác thực `authenticatedDriverId == trip.driverId`, từ chối tài xế lạ với HTTP 403.
4. **Sổ cái Bút toán kép bất biến**: [LedgerPostingService.java](apps/wallet-ledger-service/src/main/java/com/rhl/wallet/services/LedgerPostingService.java) bắt buộc $\sum \text{DEBIT} == \sum \text{CREDIT}$, append-only.
5. **Cơ chế Financial Inbox**: [FinancialEventIngestionService.java](apps/wallet-ledger-service/src/main/java/com/rhl/wallet/services/FinancialEventIngestionService.java) bảo vệ khử trùng và phân tách an toàn giữa các giao dịch CASH và DIGITAL.

---

## 6. MÔ HÌNH VẬN HÀNH & TRIỂN KHAI (RUNTIME & DEPLOYMENT TOPOLOGY)

### Sơ đồ 6: Cấu Trúc Triển Khai và Cổng Giao Tiếp Mạng

```mermaid
flowchart TB
    subgraph HostOS["Môi Trường Máy Chủ Windows (Local Development)"]
        Browser["Trình Duyệt Web / React App (:3000)\napps/rhl-web (Vite Dev Server)"]
        
        subgraph JavaProcesses["Các Tiến Trình Java 21 (Spring Boot Apps)"]
            P_GW["api-gateway (:8080)"]
            P_ID["identity-driver-service (:8081)"]
            P_LOC["location-service (:8082)"]
            P_PRI["pricing-service (:8083)"]
            P_TRIP["trip-service (:8084)"]
            P_DISP["dispatch-service (:8085)"]
            P_RT["realtime-service (:8086)"]
            P_PAY["payment-service (:8087)"]
            P_WAL["wallet-ledger-service (:8088)"]
        end
    end

    subgraph DockerHost["Hạ Tầng Dịch Vụ Nền Tảng (Docker Compose)"]
        C_PG["rhl-postgres\npostgres:15-alpine\nCổng: 5436 -> 5432"]
        C_REDIS["rhl-redis\nredis:7-alpine\nCổng: 6380 -> 6379"]
        C_KAFKA["rhl-kafka\nconfluentinc/cp-kafka:7.7.1\nCổng: 9094 (host) / 9092 (docker)"]
    end

    Browser -->|HTTP :3000 / Proxy /api -> :8080| P_GW
    Browser -->|WS STOMP ws://localhost:8080/ws| P_GW
    
    P_GW --> P_ID & P_LOC & P_PRI & P_TRIP & P_DISP & P_RT & P_PAY & P_WAL

    P_ID & P_PRI & P_TRIP & P_DISP & P_PAY & P_WAL -->|JDBC: localhost:5436| C_PG
    P_LOC -->|Redis: localhost:6380| C_REDIS
    P_TRIP & P_DISP & P_PAY & P_RT & P_WAL -->|Kafka: localhost:9094| C_KAFKA
```

### Các bước hoạt động và thành phần tương ứng:
1. **Cô lập hạ tầng qua Docker**: File cấu hình [docker-compose.yml](infra/docker-compose.yml) quản lý 3 containers nền tảng với dữ liệu bền vững trên các volumes Docker.
2. **Thiết lập mạng Kafka kép**: Listener `EXTERNAL://0.0.0.0:9094` phục vụ các ứng dụng Spring Boot chạy trên host Windows, và listener `PLAINTEXT://rhl-kafka:9092` phục vụ mạng nội bộ container.
3. **Phân phối cổng chuẩn hóa**: Các cổng từ `:8080` đến `:8088` được gán cố định, tránh xung đột tài nguyên mạng.
4. **Môi trường Web tối ưu**: Ứng dụng [rhl-web](apps/rhl-web/) sử dụng reverse proxy tích hợp trong `vite.config.ts`, định tuyến toàn bộ request `/api/**` về Gateway `:8080` để phòng tránh lỗi CORS.
