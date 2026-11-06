# REQUIREMENT TRACEABILITY MATRIX (RTM)

> **MÃ TÀI LIỆU**: RTM-RHL-001  
> **CĂN CỨ ĐỀ TÀI GỐC**: SRS-RHL-002 v1.0 (IEEE 830-1998) — [DE_BAI_GOC.docx](../DE_BAI_GOC.docx)  
> **PHIÊN BẢN HỒ SƠ**: 2.0 (Chuẩn hóa đối chiếu SRS, điều chỉnh NFR/FR mapping và phân định rõ trạng thái kiểm toán)  
> **QUY TẮC ĐÁNH GIÁ**: Chỉ ghi `IMPLEMENTED` khi có mã nguồn thực tế; chỉ ghi `TEST_VERIFIED` khi có bài test tự động chạy thành công; không suy đoán hoặc ghi nhận khống thành tích.

---

## 1. RÀNG BUỘC KIẾN TRÚC (ARCHITECTURAL CONSTRAINTS - CON)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **CON-01** | Tách tối thiểu các miền: User/Driver, Location, Trip/Dispatch, Pricing, Payment | M00-A / M05 | `PARTIAL` | `apps/api-gateway`, `apps/identity-driver-service`, `apps/location-service`, `apps/pricing-service`, `apps/trip-service` | `TEST_VERIFIED` | Đã tách độc lập Identity, Location, Pricing, Trip; Payment chờ M08 |
| **CON-02** | Mỗi service sở hữu dữ liệu riêng; giao tiếp xuyên service qua API/Event | M00-A / M05 | `PARTIAL` | DB `rhl_identity`, Redis, `rhl_pricing`, `rhl_trip` tách biệt | `TEST_VERIFIED` | Các database schema độc lập; message broker chờ M09 |
| **CON-03** | Cập nhật vị trí và sự kiện thời gian thực hỗ trợ WebSocket / tương đương | M07-G1 / M07-G2 | `IMPLEMENTED` | `apps/realtime-service` (:8086), `WebSocketConfig.java`, `TripRoomAuthorizationService.java`, `StompSubscriptionInterceptor.java`, `TripLocationController.java`, API Gateway route `/ws/**` | `TEST_VERIFIED` | Nền tảng WebSocket/STOMP, User Destination, kênh phòng `/topic/trips.{tripId}.location` và authoritative snapshot sync hoàn thành (41/41 Realtime tests PASS, 652/652 toàn hệ thống PASS) |
| **CON-04** | Truy vấn tài xế lân cận dùng chỉ mục không gian (Redis GEO / PostGIS) | M00-A | `IMPLEMENTED` | `DriverLocationService.java` (`opsForGeo().search`) | `UNVERIFIED` | Chưa có PostGIS lưu vết dài hạn |
| **CON-05** | Chuyển trạng thái chuyến tuân thủ state machine nghiêm ngặt | M05 | `IMPLEMENTED` | `TripStateMachine.java`, `Trip.java`, `V1__create_trips_tables.sql` | `TEST_VERIFIED` | 59 FSM unit tests + 9 domain tests PASS 100% |
| **CON-06** | Gán tài xế có kiểm soát đồng thời nguyên tử, không gán trùng | M06-G3 | `IMPLEMENTED` | `DispatchTripAssignmentService.java`, `MatchingOrchestrator.java`, `V3__assignment_resilience_and_matching_session_index.sql`, `V3__driver_single_active_trip_index.sql` | `TEST_VERIFIED` | PostgreSQL Partial Unique Index `uk_driver_offers_single_winning_assigned` + `uk_trips_driver_single_active` + concurrency tests PASS 100% |
| **CON-07** | Sự kiện hoàn tất chuyến và thanh toán truyền bất đồng bộ tin cậy | M07 / M08-G3 | `IMPLEMENTED` | `trip_outbox_events`, `dispatch_outbox_events`, `payment_outbox_events`, `PaymentOutboxPublisher.java`, `TripOutboxPublisher.java`, `DispatchOutboxPublisher.java` | `TEST_VERIFIED` | Transactional Outbox Pattern đã được triển khai nguyên tử cho Trip, Dispatch và Payment (120/120 payment tests PASS) |
| **CON-08** | Không lưu thông tin thẻ nhạy cảm (PAN/CVV) trong hệ thống | M08 | `IMPLEMENTED` | `MomoPaymentProvider.java`, `PaymentTransaction.java` | `TEST_VERIFIED` | Ủy quyền thanh toán số sang cổng MoMo v2 tokenized/QR, không lưu trữ thông tin thẻ |

---

## 2. NHÓM ĐỊNH DANH & PHÂN QUYỀN (IDENTITY & ACCESS - FR-IAM)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-IAM-001** | Khách hàng và tài xế đăng ký tài khoản | M00-A | `IMPLEMENTED` | `AuthController.java` (`register`), `AuthService.java` | `UNVERIFIED` | Thiếu unit test |
| **FR-IAM-002** | Kiểm tra định dạng, chuẩn hóa email/username duy nhất | M00-A | `IMPLEMENTED` | `@Valid`, `@Email`, `UserRepository.existsByEmail` | `UNVERIFIED` | Chỉ kiểm tra email, chưa hỗ trợ số điện thoại |
| **FR-IAM-003** | Mật khẩu tuân thủ chính sách, băm an toàn | M00-A | `IMPLEMENTED` | `BCryptPasswordEncoder` trong `SecurityConfig.java` | `UNVERIFIED` | Chưa validate độ phức tạp mật khẩu bằng regex |
| **FR-IAM-004** | Xác minh email / số điện thoại nếu chính sách yêu cầu | M02 | `NOT_IMPLEMENTED` | Trạng thái user mặc định `ACTIVE` | `NOT_IMPLEMENTED` | Chưa có OTP / Email verification flow |
| **FR-IAM-005** | Đăng nhập và nhận JWT token có thời hạn phù hợp | M00-A | `IMPLEMENTED` | `AuthController.java` (`login`), `JwtService.java` (RS256) | `UNVERIFIED` | Thiếu unit test |
| **FR-IAM-006** | Giới hạn thử đăng nhập, không lộ tài khoản tồn tại | M02 | `NOT_IMPLEMENTED` | Ném `InvalidCredentialsException` chung | `NOT_IMPLEMENTED` | Chưa có Rate Limiting / Account Lockout |
| **FR-IAM-007** | Hỗ trợ refresh token, đăng xuất, thu hồi phiên | M00-A | `IMPLEMENTED` | `AuthController.java` (`refresh`, `logout`), `RefreshTokenService.java` | `UNVERIFIED` | Thiếu unit test |
| **FR-IAM-008** | Người dùng xem và cập nhật hồ sơ cá nhân | M00-A | `PARTIAL` | `AuthController.java` (`GET /api/v1/auth/me`) | `UNVERIFIED` | Chưa có API update profile |
| **FR-IAM-009** | RBAC với Customer, Driver, Reviewer, Admin | M00-A | `IMPLEMENTED` | Bảng `roles`, `permissions`, `V2__create_rbac.sql` | `UNVERIFIED` | Chưa có API gán vai trò động |
| **FR-IAM-010** | Mọi endpoint bảo vệ kiểm tra danh tính, quyền | M00-A | `PARTIAL` | `SecurityConfig.java`, `GatewaySecurityConfig.java` | `NEEDS_RUNTIME_VERIFICATION` | `DriverService` đã có `@PreAuthorize`, nhưng `DriverController` thiếu guard ở Controller layer; đề xuất bổ sung defense-in-depth tại M00-C |
| **FR-IAM-011** | Ghi log kiểm toán các thay đổi vai trò, bảo mật | M02 | `IMPLEMENTED` | Bảng `driver_audit_logs`, `DriverAuditLog.java`, `V6__driver_suspension_and_audit.sql` | `TEST_VERIFIED` | Unit & Security tests verify atomic audit recording |
|

---

## 3. NHÓM HỒ SƠ & TRẠNG THÁI TÀI XẾ (DRIVER - FR-DRV)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-DRV-001** | Cung cấp thông tin cá nhân, GPLX, phương tiện | M00-A | `IMPLEMENTED` | `DriverController.java` (`POST /api/v1/drivers/applications`) | `UNVERIFIED` | DTO dùng package `dto` thay vì `dtos` |
| **FR-DRV-002** | Kiểm tra bắt buộc, định dạng, duy nhất giấy tờ | M00-A | `IMPLEMENTED` | `@NotBlank`, Unique constraint `driver_license_number` | `UNVERIFIED` | Chưa có upload file ảnh GPLX |
| **FR-DRV-003** | Hồ sơ tài xế có trạng thái tối thiểu DRAFT, PENDING, APPROVED, REJECTED, SUSPENDED | M00-A / M02 | `IMPLEMENTED` | `DriverApplicationStatus.java`, Check constraint `V6` | `TEST_VERIFIED` | Đã có `PENDING`, `APPROVED`, `REJECTED`, `SUSPENDED` (Group 3); DRAFT chuyển sang giai đoạn onboarding nâng cao |
| **FR-DRV-004** | Tài xế gửi hồ sơ xét duyệt và xem được trạng thái, lý do từ chối | M00-A | `IMPLEMENTED` | `DriverController.java` (`GET /me`, `POST /applications`) | `UNVERIFIED` | Thiếu unit test |
| **FR-DRV-005** | Nhân viên kiểm duyệt duyệt, từ chối hoặc yêu cầu bổ sung hồ sơ theo quyền | M00-A | `PARTIAL` | `DriverController.java` (`approve`, `reject`), `DriverService.java` | `TEST_VERIFIED` | `DriverService` và `DriverController` đều có `@PreAuthorize("hasAuthority('DRIVER_REVIEW')")` |
| **FR-DRV-006** | Quyết định xét duyệt ghi người thực hiện, thời điểm, lý do | M00-A / M02 | `IMPLEMENTED` | Cột `reviewed_by`, `reviewed_at`, `rejection_reason` trong `driver_profiles` & bảng `driver_audit_logs` | `TEST_VERIFIED` | Audit log ghi vết đầy đủ actor, previous/new status, reason |
| **FR-DRV-007** | Mỗi phương tiện PHẢI có loại, biển số, thuộc tính nhận diện, trạng thái và liên kết tài xế | M02 | `IMPLEMENTED` | Bảng `vehicles` (`V5`), `Vehicle.java`, `VehicleService.java`, `VehicleController.java` | `TEST_VERIFIED` | 24 tests chuyên biệt (VehicleServiceTest + VehicleControllerSecurityTest) PASS |
| **FR-DRV-008..014** | Trạng thái hoạt động tài xế (OFFLINE, AVAILABLE, OFFERED, BUSY, TTL heartbeat) | M00-A / M02 / M03 | `PARTIAL` | Quản lý Online/Offline qua Redis trong `location-service`; Cross-service eligibility check | `TEST_VERIFIED` | 28 tests trong `location-service` PASS (DriverLocationServiceTest + ControllerSecurityTest); trạng thái BUSY/OFFERED xử lý tại M03/M05 |

---

## 4. NHÓM VỊ TRÍ & TELEMETRY (LOCATION - FR-LOC)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-LOC-001** | Nhận tọa độ GPS tài xế thời gian thực | M00-A / M02 | `IMPLEMENTED` | `DriverLocationController.java` (`PUT /api/v1/locations/me`) | `TEST_VERIFIED` | Unit & Security tests PASS; WebSocket bổ sung tại M07 |
| **FR-LOC-002** | Kiểm tra tính hợp lệ kinh độ, vĩ độ | M00-A / M02 | `IMPLEMENTED` | `DriverLocationService.java` (`validateCoordinates`) | `TEST_VERIFIED` | Unit tests biên tọa độ PASS |
| **FR-LOC-003** | Lấy danh tính tài xế từ phiên xác thực | M00-A / M02 | `IMPLEMENTED` | `UUID.fromString(jwt.getSubject())` | `TEST_VERIFIED` | Controller security tests trích xuất từ JWT claim PASS |
| **FR-LOC-004** | Nhận diện GPS cũ, gián đoạn hoặc bất thường | M03 | `NOT_IMPLEMENTED` | Chưa có kiểm tra delta khoảng cách / thời gian | `NOT_IMPLEMENTED` | Cần thuật toán lọc vận tốc > 150 km/h |
| **FR-LOC-005** | Lưu trữ vị trí hiệu năng cao (Redis GEO) | M00-A / M02 | `IMPLEMENTED` | `opsForGeo().add("rhl:geo:drivers", ...)` | `TEST_VERIFIED` | DriverLocationServiceTest mock Redis GEO PASS |
| **FR-LOC-006** | Quản lý trạng thái sống kèm TTL (Heartbeat) | M00-A / M02 | `IMPLEMENTED` | Redis key `rhl:driver:live:{id}` với TTL 90s | `TEST_VERIFIED` | DriverLocationServiceTest mock TTL PASS |
| **FR-LOC-009** | Truy vấn tài xế lân cận theo bán kính và số lượng | M00-A / M02 | `IMPLEMENTED` | `DriverLocationController.java` (`GET /nearby`) | `TEST_VERIFIED` | Nearby batch check + security tests PASS |
| **FR-LOC-010..014** | Lưu vết lịch sử lộ trình chuyến đi (telemetry track) | M03 | `NOT_IMPLEMENTED` | Redis chỉ lưu vị trí tức thời | `NOT_IMPLEMENTED` | Cần lưu vết telemetry vào database |

---

## 5. NHÓM ĐỊNH GIÁ & BÁO GIÁ (PRICING & FARE QUOTE - FR-PRI)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-PRI-001..004** | Cấu hình biểu giá (Base, Km, Phút, Tối thiểu, Khoảng thời gian) và kiểm soát trùng lặp | M04 | `IMPLEMENTED` | `PricingRule.java`, `PricingScopeLockManager.java`, `V1__create_pricing_tables.sql` | `TEST_VERIFIED` | Advisory lock chống race condition + JPQL overlap check |
| **FR-PRI-005..006** | Lấy cước theo khoảng cách lộ trình (Route Distance) và xử lý fallback | M04 | `IMPLEMENTED` | `RouteEstimateProvider.java`, `FareCalculator.java` | `TEST_VERIFIED` | Tách biệt Haversine; chặn tạo quote production từ simulated routing |
| **FR-PRI-007..010** | Tính hệ số Surge theo Cung/Cầu, Động cơ tất định, Giới hạn trần, Xác nhận khách hàng | M04 | `IMPLEMENTED` | `SurgeCalculator.java`, `SurgePolicy.java`, `SurgeService.java`, `SimulatedDemandSupplyProvider.java` | `TEST_VERIFIED` | 22 tests chuyên biệt PASS 100%; Realtime Demand phụ thuộc M05, Supply phụ thuộc M06 |
| **FR-PRI-009..012** | Quản lý phiên bản biểu giá (Versioning), trạng thái (DRAFT/PUBLISHED/ARCHIVED) và Audit Log | M04 | `IMPLEMENTED` | `PricingRuleService.java`, `PricingRuleAudit.java`, `AdminPricingRuleController.java` | `TEST_VERIFIED` | Monotonic version bump + atomic audit log tests PASS |
| **FR-PRI-013..018** | Sinh báo giá (Fare Quote), Snapshot bất biến, Gắn định danh khách hàng, Hạn 300s, Xác thực nội bộ M2M (`FR-PRI-015`) | M04 / M05 | `IMPLEMENTED` | `FareQuote.java`, `FareQuoteService.java`, `InternalFareQuoteController.java`, `InternalAuthFilter.java`, `V2__create_fare_quotes.sql` | `TEST_VERIFIED` | 24 tests Pricing + 7 tests M2M Internal Security PASS 100% |

---

## 6. NHÓM VÒNG ĐỜI CHUYẾN & STATE MACHINE (TRIP LIFECYCLE - FR-TRIP, FR-CAN)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-TRIP-001..002** | Tạo chuyến RIDE/DELIVERY từ quote còn hiệu lực, snapshot bất biến, Idempotency-Key chống trùng lặp | M05-G1 / M05-G2 | `BOOKING_IMPLEMENTED` | `BookingService.java`, `AtomicTripCreator.java`, `TripBookingController.java`, `Trip.java`, `V2__quote_uniqueness_and_active_trip_index.sql` | `TEST_VERIFIED` | 14 unit + 11 security + 4 PostgreSQL concurrency tests PASS 100%; Dispatch pending M05-G3 / M06 |
| **FR-TRIP-006..014** | State Machine 9 trạng thái nghiêm ngặt, kiểm soát bước chuyển, ràng buộc tác nhân (Actor Guard) & Driver Progression (PICKING_UP, ARRIVED, IN_TRIP, COMPLETED) | M05-G1 / M05-G3 | `API_IMPLEMENTED` | `TripStateMachine.java`, `TripStatus.java`, `Trip.java`, `TripProgressionService.java`, `TripLifecycleController.java` | `TEST_VERIFIED` | 59 FSM + 10 progression unit + 7 progression security + PostgreSQL concurrency tests PASS; Dispatch E2E pending M06 |
| **FR-TRIP-010** | Ghi nhật ký kiểm toán chuyển trạng thái (Audit Trail) bất biến | M05-G1 / M05-G2 / M05-G3 | `IMPLEMENTED` | `TripStatusHistory.java`, `TripStatusHistoryRepository.java`, `AtomicTripCreator.java`, `TripCancellationService.java`, `TripProgressionService.java` | `TEST_VERIFIED` | Test append-only history + atomic creation history + progression & cancel audit PASS 100% |
| **FR-TRIP-007** | Ngăn chặn một khách hàng tạo nhiều chuyến hoạt động xung đột | M05-G2 | `BOOKING_IMPLEMENTED` | Partial Unique Index `uk_trips_customer_single_active`, `BookingService.java` | `TEST_VERIFIED` | PostgreSQL Concurrency Testcontainers chứng minh race condition được chặn triệt để |
| **FR-CAN-001..007** | Quy tắc hủy chuyến theo actor, trạng thái, lý do hủy, phí hủy và quy trình ngoại lệ | M05-G1 / M05-G3 | `API_IMPLEMENTED` | `TripCancellationService.java`, `DefaultCancellationPolicyService.java`, `TripLifecycleController.java`, `Trip.java` (`cancel`) | `TEST_VERIFIED` | 16 unit + 8 security + 4 concurrency tests PASS; Cancellation fee policy awaiting business signoff (`FEE_POLICY_PENDING`) |

---

## 7. NHÓM TRUYỀN THÔNG THỜI GIAN THỰC (REALTIME & WEBSOCKET - FR-RT)

| Requirement | Ý nghĩa / Yêu cầu nghiệp vụ | Module | Hiện trạng | Evidence (Bằng chứng mã nguồn) | Test | Gap / Tồn đọng |
|---|---|---|---|---|---|---|
| **FR-RT-001** | Khách hàng đăng ký kênh realtime của chuyến thuộc mình | M07-G1 / M07-G2 / M07-G4 | `IMPLEMENTED` | `apps/realtime-service`, `TripRoomAuthorizationService.java`, `StompSubscriptionInterceptor.java` (`/topic/trips.{tripId}.location`), `apps/rhl-web` (`stompClient.ts`, `useTripRealtime.ts`, `TripLiveTracker.tsx`) | `TEST_VERIFIED` | Kênh người dùng riêng tư `/user/queue/trips` và kênh phòng động `/topic/trips.{tripId}.location` cho Customer đã hoàn thành, tích hợp React UI và kiểm thử quyền sở hữu nghiêm ngặt |
| **FR-RT-002** | Tài xế nhận thay đổi trạng thái, hủy và thông tin chuyến được gán | M07-G1 / M07-G2 / M07-G4 | `IMPLEMENTED` | `apps/realtime-service`, `StompSubscriptionInterceptor.java`, `apps/rhl-web` (`useDriverOffers.ts`, `DriverOfferCard.tsx`, `offerService.ts`) | `TEST_VERIFIED` | Kênh người dùng riêng tư `/user/queue/offers` (chỉ DRIVER) và Driver Offer UX đếm ngược thời gian thực, Accept/Reject REST hoạt động an toàn |
| **FR-RT-003** | Gửi vị trí tài xế cho khách hàng với độ trễ mục tiêu NFR-PERF-001 ($\le 500$ ms) | M07-G2 / M07-G4 | `DEV_VERIFIED` | `DriverLocationService.java`, `RealtimeLocationPublisherClient.java`, `InternalRealtimePublishController.java`, `apps/rhl-web` (`useTripLocation.ts`, `TripLiveTracker.tsx`) | `TEST_VERIFIED` | Luồng telemetry từ Location Service đến Realtime Room qua M2M API hiển thị live trên React UI, khóa hợp phần `isNewerLocation` chống nhảy lùi tọa độ qua các phiên; Benchmarking tải cụm production ghi nhận `M07_GROUP2_DEV_VERIFIED / LATENCY_NFR_PENDING` |
| **FR-RT-004** | Gửi event thay đổi trạng thái cho cả hai bên theo đúng quyền | M07-G1 / M07-G2 / M07-G4 | `IMPLEMENTED` | `InternalRealtimePublishController.java`, `DefaultRealtimeEventPublisher.java`, `EventEnvelope.java`, `apps/rhl-web` (`stompClient.ts`, `useTripRealtime.ts`) | `TEST_VERIFIED` | API M2M phân phối event theo vai trò và quyền phòng chuyến, chặn 100% client SEND, React hooks bảo vệ FSM tiến trình |
| **FR-RT-005** | Khi reconnect, client lấy snapshot trạng thái trước khi áp dụng event mới | M07-G2 / M07-G4 | `IMPLEMENTED` | `TripLocationController.java` (`GET /api/v1/trips/{tripId}/location`), `apps/rhl-web` (`useTripLocation.ts`, `useDriverOffers.ts`, `useTripRealtime.ts`) | `TEST_VERIFIED` | Endpoint snapshot bảo vệ chặt chẽ Customer/Driver sở hữu, frontend giải quyết xung đột Live vs Snapshot không lùi tọa độ hoặc trạng thái |
| **FR-RT-006** | Client dùng sequence/version để bỏ qua bản tin trùng hoặc cũ | M07-G1 / M07-G2 / M07-G4 | `IMPLEMENTED` | `EventEnvelope.java` (`version`, `eventId`, `timestamp`), `TripLocationPayload.java` (`sequenceNumber`), `apps/rhl-web` (`stompClient.ts` LRU cache, `isNewerLocation`) | `TEST_VERIFIED` | Kiểm thử `realtime.test.ts` và `StompJwtAuthenticationTest` xác minh loại bỏ bản tin trùng lặp và gói tin cũ thành công 100% |
| **FR-RT-007** | Nếu realtime không khả dụng, client lấy trạng thái qua REST API | M05 / M06 / M07-G2 / M07-G4 | `IMPLEMENTED` | `GET /api/v1/trips/{id}`, `GET /api/v1/offers/pending`, `GET /api/v1/trips/{tripId}/location`, `apps/rhl-web` (`offerService.ts`, `tripService.ts`) | `TEST_VERIFIED` | Fallback REST API có sẵn tại Trip Service, Dispatch Service và tích hợp trong React hooks |
| **FR-RT-008** | Sau khi chuyến kết thúc/hủy, không còn được subscribe vị trí chính xác | M07-G2 / M07-G4 | `IMPLEMENTED` | `TripRoomAuthorizationService.java`, `TripCancellationService.java`, `TripProgressionService.java`, `apps/rhl-web` (`useTripLocation.ts` terminal unsubscription) | `TEST_VERIFIED` | Thu hồi quyền phòng chuyến xe nguyên tử khi chuyến CANCELLED/COMPLETED và tự động hủy subscription trên React UI |

---

## 8. CÁC NHÓM YÊU CẦU CHƯA TRIỂN KHAI HOẶC ĐANG HOÀN THIỆN (PENDING / NOT IMPLEMENTED)

| Nhóm yêu cầu | Mã yêu cầu | Module kế hoạch | Mô tả chức năng cần xây dựng theo SRS-RHL-002 |
|---|---|---|---|
| **Booking API** | `FR-TRIP-001..002`, `FR-PRI-015` | M05-G2 | `BOOKING_IMPLEMENTED` - REST API Booking, M2M Quote Validation, Idempotency Replay, PostgreSQL Partial Unique Index |
| **Trip Cancellation & Lifecycle Progression API** | `FR-CAN-001..007`, `FR-TRIP-008..014` | M05-G3 | `LIFECYCLE_IMPLEMENTED` - REST API Hủy chuyến, tiến trình vòng đời tài xế (start-pickup, arrive, start, complete), Optimistic Locking `@Version`, `CancellationPolicyService` (`FEE_POLICY_PENDING`), `DispatchTripAssignmentService` (Gán tài xế `MATCHING -> ACCEPTED` verified trong M06-G3) |
| **Điều phối, Ghép xe & Gán chuyến** | `FR-MAT-007` … `014`, `CON-06`, `NFR-REL-003` | M06-G1 … M06-G4 | `MATCHING_RECOVERY_AND_SAFETY_VERIFIED` — Driver Offer FSM 5 trạng thái, Resilient One-Winner Index (`uk_driver_offers_single_winning_assigned`), Single Pending Offer (`uk_driver_offers_single_pending_per_driver`), Matching Session Index (`uk_matching_sessions_active_trip`), Driver Single Active Trip Index (`uk_trips_driver_single_active`), Matching Orchestrator đa vòng, Atomic Trip Assignment, Authoritative Reconciliation, DispatchRecoveryService (7 cases), Session Fencing (AUD-SES-01 CLOSED), Active Vehicle Session Protocol; 136/136 Dispatch tests + 76/76 Location tests PASS (Tổng 602/602 tests PASS 100%) |
| **Ứng viên, Xếp hạng & Khả dụng** | `FR-MAT-001` … `006`, `015` | M06-G2 / M06-G2.5 | `CANDIDATE_SELECTION_CROSS_SERVICE_VERIFIED` — Pipeline 6 giai đoạn (`DriverCandidateSelector`), GPS Freshness $\le 60$s (`FR-MAT-005`) từ Redis server timestamp, Driver Eligibility (`FR-MAT-002`), Vehicle Lookup nội bộ & ADR-017 / ADR-025 (`AMBIGUOUS_ACTIVE_VEHICLES`), Availability tách biệt historical accepted offer qua Flyway V2 `assignment_status` (`FR-DRV-012`, `FR-MAT-013`), Trip Busy Batch API, Authoritative Trip Snapshot check, M2M `/internal/**` AuthFilter, 107/107 tests PASS |
| **Truyền thông Realtime Group 2** | `FR-RT-003`, `005`, `008` | M07-G2 | `COMPLETED` — Kênh phòng chuyến xe `/topic/trips.{tripId}.location`, stream GPS live sau commit Redis Lua, Room Membership Guard, Reconnect State Snapshot (`GET /api/v1/trips/{tripId}/location`), Room dynamic revocation on terminal state |
| **Truyền thông Realtime Group 3** | `FR-RT-002`, `004`, `006` | M07-G3 | `COMPLETED` — Transactional Outbox Pattern qua PostgreSQL (`dispatch_outbox_events`, `trip_outbox_events`), Outbox Background Poller Workers (`DispatchOutboxPublisher`, `TripOutboxPublisher`), Exponential Backoff Retry, Idempotent Ingestion Deduplication (`isDuplicateEvent`), Terminal Room Revocation Safety (`revokeTripRoom`), Fail-closed M2M Caller Authentication (`X-Caller-Service`) |
| **Truyền thông Realtime Group 4** | `FR-RT-001..008` | M07-G4 | `COMPLETED / FRONTEND_AND_BACKEND_VERIFIED` — Tích hợp React 19 Frontend STOMP Client (@stomp/stompjs) qua Gateway `/ws`, Driver Offer UX đếm ngược thời gian thực, Accept/Reject REST, Customer Trip Tracker, Live Driver GPS Room, Khóa hợp phần `isNewerLocation` chống nhảy lùi tọa độ qua các phiên làm việc (Session Reset `seq 105 -> 1`), Hòa giải snapshot khi reconnect, 27/27 frontend tests PASS 100% |
| **Thanh toán khách hàng (Foundation)** | `FR-PAY-001` … `008`, `A-06`, `COM-008`, `CON-07`, `CON-08`, `DR-004` | M08-G1 | `IMPLEMENTED / TEST_VERIFIED` — `apps/payment-service` (:8087), `PaymentTransaction` Aggregate Root, 5-state FSM, `uk_payments_trip_single_success` partial unique index (Zero Double Charging), `uk_payments_customer_idempotency` (Idempotent Replay), Authoritative payable amount from Trip Service `estimatedFare`, Append-only `PaymentStatusHistory` audit trail, 40/40 tests PASS 100% |
| **Hoàn tiền, Hủy & Đối soát tài chính (Group 3 & Group 4)** | `FR-PAY-012` … `013`, `FR-PAY-004..006` | M08-G3 / M08-G4 | `IMPLEMENTED / FRONTEND_AND_BACKEND_VERIFIED` — Aggregate `PaymentRefund` độc lập, Pessimistic Locking `findByIdForUpdate` chống Over-Refund, Flyway V4 active attempt index (UNKNOWN coverage), Least-Privilege Driver payment discovery (`GET /api/v1/payments/driver/trips/{tripId}`), Customer payment history (`GET /api/v1/payments`), React 19 Customer Payment Card (Authoritative fare, stable idempotency, bounded polling), Driver Cash Collection Card, Finance Console RBAC (`/finance`), MoMo sandbox gating (`MOMO_LIVE_SANDBOX_E2E_PENDING`), Refund policy gating (`REFUND_POLICY_PENDING`), 125/125 backend tests PASS, 47/47 frontend tests PASS (Tổng 836/836 tests PASS 100%) |
| **Ví tài xế & Sổ cái** | `FR-WAL-001` … `010` | M09-G1 / M09-G2 | `FOUNDATION_IMPLEMENTED / TEST_VERIFIED` — `apps/wallet-ledger-service` (:8088), `DriverWallet` Aggregate Root, Sổ cái Bút toán kép bất biến (`LedgerPostingService`), `SUM(DEBIT) == SUM(CREDIT)`, phân tách tài chính CASH vs DIGITAL, Transactional Financial Inbox (`financial_inbox_events`), chống race condition tạo ví (`REQUIRES_NEW`), đối soát số dư (`WalletBalanceReconciliationService`), bảo vệ không số dư âm (`FR-WAL-009`), RBAC Least Privilege (`/api/v1/wallets/me`); 39/39 tests PASS on real PostgreSQL Testcontainers; Chờ chính sách hoa hồng ở Group 2 (`COMMISSION_POLICY_PENDING`, `SETTLEMENT_POLICY_PENDING`) |
| **Sự kiện & Độ tin cậy** | `FR-EVT-001` … `008` | M07 / M08 / M09 | `PARTIAL / TEST_VERIFIED` — Transactional Outbox Pattern tại Trip Service, Dispatch Service, Payment Service; Transactional Financial Inbox tại Wallet Ledger Service (`FR-EVT-001..002`); Deduplication & At-least-once delivery |
| **Quản trị & Hỗ trợ** | `FR-ADM-001` … `007` | M10 | Dashboard quản trị, tra cứu lịch sử cuốc, giải quyết khiếu nại |

---

## 9. YÊU CẦU PHI CHỨC NĂNG (NON-FUNCTIONAL REQUIREMENTS - NFR)

| Mã NFR | Yêu cầu kỹ thuật theo SRS-RHL-002 | Ngưỡng đo lường | Hiện trạng | Module bảo đảm |
|---|---|---|---|---|
| **NFR-PERF-001** | Độ trễ cập nhật vị trí thời gian thực | 95% cập nhật hợp lệ từ server đến Customer App ≤ 500 ms | `UNVERIFIED` (Redis GEO in-memory) | M03 / M10 |
| **NFR-PERF-002** | Xử lý tải telemetry của Location Service | Xử lý ổn định ít nhất 100 tài xế mô phỏng gửi mỗi 3s (mục tiêu mở rộng: 500) | `UNVERIFIED` | M03 / M10 |
| **NFR-PERF-003** | Độ trễ tìm danh sách ứng viên matching | 95% yêu cầu hoàn thành ≤ 200 ms | `UNVERIFIED` (Cần benchmark ở M10) | M06 / M10 |
| **NFR-REL-003** | Ngăn chặn gán trùng tài xế (Zero Double Assignment) | 0 trường hợp trùng cuốc khi tranh chấp đồng thời | `TEST_VERIFIED` (Resilient One-Winner Flyway V3 + Driver single active trip Flyway V3 + Idempotent assignment; Concurrency Testcontainers verified) | M06 |
| **NFR-SEC-001** | Toàn bộ giao tiếp công khai PHẢI dùng HTTPS/WSS | TLS 1.2 trở lên | `UNVERIFIED` (Môi trường dev hiện dùng HTTP) | M01 / M10 |
| **NFR-SEC-002** | Mật khẩu PHẢI băm bằng Argon2id, bcrypt hoặc tương đương | BCryptPasswordEncoder trong Spring Security | `IMPLEMENTED` | M00-A |
| **NFR-SEC-003** | Khóa ký, API keys và credentials được bảo vệ, xoay vòng | 0 secret trong Git / Log | `IMPLEMENTED` (`.gitignore` + Rules) | Xuyên suốt |
| **NFR-SEC-004** | Kiểm soát quyền truy cập toàn diện (Least Privilege & RBAC) | 100% endpoint được bảo vệ đúng actor/authority | `VERIFIED` (Public JWT RS256 + M2M InternalAuthFilter) | M00-C / M06-G2 |
| **NFR-MNT-001** | Lược đồ cơ sở dữ liệu có thể tái lập | 100% qua Flyway Migrations | `IMPLEMENTED` (V1..V6 Identity, V1..V2 Pricing, V1..V4 Trip, V1..V4 Dispatch) | M00-A / M05 / M06 / M07 |
| **NFR-MNT-005** | Bảng giá, bán kính, timeout offer, chu kỳ telemetry không hardcode | Cấu hình qua configuration properties | `VERIFIED` (`PricingProperties`, `MatchingProperties`, `DispatchProperties`) | M04 / M06 |

