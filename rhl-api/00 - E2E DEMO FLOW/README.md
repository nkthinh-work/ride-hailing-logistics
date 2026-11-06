# RHL End-to-End Live Demonstration Workflow

This Bruno collection (`00 - E2E DEMO FLOW`) orchestrates the complete Ride-Hailing & Logistics business flow across all 6 core microservices via API Gateway (`http://localhost:8080`).

---

## 1. Prerequisites

1. **Docker Infrastructure Running**:
   ```bash
   docker ps
   # Must see: rhl-postgres (port 5436), rhl-redis (port 6380), rhl-kafka (port 9092)
   ```
2. **Spring Boot Backend Services Running**:
   - `api-gateway`: port 8080
   - `identity-driver-service`: port 8081
   - `location-service`: port 8082
   - `pricing-service`: port 8083
   - `trip-service`: port 8084
   - `dispatch-service`: port 8085
   - `realtime-service`: port 8086
   - `payment-service`: port 8087
   - `wallet-ledger-service`: port 8088
3. **Bruno Environment**:
   - Select environment: `local`
   - All response scripts automatically extract and propagate tokens and UUIDs into environment variables (`customerAccessToken`, `driverAccessToken`, `reviewerAccessToken`, `driverSessionId`, `tripId`, `offerId`, `paymentId`).

---

## 2. Correct Execution Order

| Group | Subgroup Name | Key Requests & Action | Role Used |
|---|---|---|---|
| **01** | `01 - Authentication & KYC` | Login Customer, Login Driver, Verify Profile, Reviewer Approval | `CUSTOMER`, `DRIVER`, `REVIEWER` |
| **02** | `02 - Driver Online & Redis GPS` | Check Eligibility, Go Online, Update GPS with Session ID | `DRIVER` |
| **03** | `03 - Pricing & Booking` | Get Fare Quote (Hanoi Coords), Create Booking with Idempotency Key | `CUSTOMER` |
| **04** | `04 - Dispatch Matching & Kafka Offer` | Trigger Matching (INTERNAL), Dispatch Offer (INTERNAL), Driver Get & Accept Offer | `INTERNAL_SERVICE`, `DRIVER` |
| **05** | `05 - Trip Lifecycle` | Start Pickup -> Arrived -> Start Trip -> Complete Trip | `DRIVER` |
| **06** | `06 - Payment & Wallet Ledger` | Customer Initiate Cash -> Driver Confirm Cash -> Check Wallet Balance & Policy | `CUSTOMER`, `DRIVER` |
| **07** | `07 - Negative & Idempotency Tests` | 401 Missing/Invalid Token, 403 Role Forbidden, 409 State & Idempotency Guards | Various |

---

## 3. Important Execution Notes & Skip Paths

- **Existing APPROVED Driver**: If `driver@gmail.com` is already approved with an active vehicle, you can skip `06 - Submit Driver Application` and `09 - Approve Driver Application`. The flow handles already approved drivers safely.
- **Offer Expiry TTL**: When `02 - Dispatch Driver Offer` executes, an offer is issued with a short TTL (configurable in Dispatch Service, typically 15–30 seconds). Run `03 - Driver Get Pending Offer` and `05 - Driver Accept Offer` promptly.
- **Session Fencing (AUD-SES-01)**: `02 - Driver Go Online` returns a fresh `sessionId`. All subsequent `03 - Update Driver GPS` calls automatically pass this `sessionId`. If omitted or mismatched, the Redis Lua script returns 409 `STALE_SESSION_PACKET`.

---

## 4. Concurrent Web App Demonstration (Side-by-Side)

1. Open **Customer Browser Window** (Standard Window):
   - URL: `http://localhost:3000`
   - Switch to account: **Customer** (`customer@gmail.com` / `Password123!`)
   - Demonstrates: Fare estimation, booking submission, real-time trip status.
2. Open **Driver Browser Window** (Incognito Window):
   - URL: `http://localhost:3000`
   - Switch to account: **Driver** (`driver@gmail.com` / `Password123!`)
   - Demonstrates: Cockpit Go Online toggle, live GPS broadcaster, offer alert dialog, and trip state progression.

---

## 5. Live Infrastructure Verification Commands

### A. Redis Verification (Presence & Geospatial)
```bash
# Check Redis connectivity
docker exec rhl-redis redis-cli ping

# Verify driver presence in spatial index
docker exec rhl-redis redis-cli geopos rhl:geo:drivers 6755c4fe-e5ac-4459-b35c-893c9b9fb7c7

# Check driver live heartbeat and active session TTL
docker exec rhl-redis redis-cli ttl rhl:driver:live:6755c4fe-e5ac-4459-b35c-893c9b9fb7c7
docker exec rhl-redis redis-cli get rhl:driver:session:6755c4fe-e5ac-4459-b35c-893c9b9fb7c7
```

### B. Kafka Topics & Event Streams
```bash
# List all active Kafka topics
docker exec rhl-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Inspect Dispatch Offer events
docker exec rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.dispatch.offer.v1 --from-beginning --max-messages 5

# Inspect Trip Lifecycle events
docker exec rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.trip.lifecycle.v1 --from-beginning --max-messages 5

# Inspect Payment events
docker exec rhl-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic rhl.payment.events.v1 --from-beginning --max-messages 5
```

### C. PostgreSQL Authoritative Records
```bash
# View active Driver Profile and Vehicle status
docker exec rhl-postgres psql -U rhl -d rhl_identity -c "SELECT id, user_id, status, vehicle_plate FROM driver_profiles;"

# View Trips and Lifecycle States
docker exec rhl-postgres psql -U rhl -d rhl_trip -c "SELECT id, status, customer_id, driver_id, estimated_fare FROM trips ORDER BY created_at DESC LIMIT 5;"

# View Payment Transactions
docker exec rhl-postgres psql -U rhl -d rhl_payment -c "SELECT id, trip_id, customer_id, driver_id, method, status FROM payments ORDER BY created_at DESC LIMIT 5;"
```

---

## 6. Architecture Concepts Summary

- **REST (Synchronous Command/Query)**: Immediate request-response contract between clients and microservices via API Gateway. Enforces strong schema validation and RBAC.
- **Kafka (Asynchronous Backend Events)**: Decoupled pub/sub event streaming for high-throughput downstream consumers across service boundaries.
- **WebSocket/STOMP (Realtime Browser Delivery)**: Full-duplex push channel delivering incoming dispatch offers and driver telemetry straight to the driver and customer cockpits.
- **Transactional Outbox**: Pattern ensuring database mutations and corresponding event notifications are committed atomically in the same local database transaction, guaranteeing zero message loss.
- **Idempotent Consumer**: Handlers deduplicating redelivered messages using idempotency keys to ensure processing exactly-once semantics without state corruption.

---

## 7. Current Project Status & Boundaries

- **CASH Payment Workflow**: Implemented with full idempotency and dual-party confirmation (Customer initiates -> Driver confirms physical cash collection).
- **Wallet Ledger & Commission Policy**: Wallet balance reflects completed cash trips. Commission deduction and automated settlement policies are currently documented as policy-pending flags (`policyStatus: COMMISSION_POLICY_PENDING`) per project governance rules.
