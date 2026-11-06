-- atomic_location_update.lua
-- Atomically validates driver presence, enforces session fencing (AUD-SES-01),
-- compares sequence number, updates GEO position, and refreshes presence/session TTL.
--
-- KEYS[1]: liveKey    (e.g., "rhl:driver:live:<driverId>")
-- KEYS[2]: seqKey     (e.g., "rhl:driver:seq:<driverId>")
-- KEYS[3]: geoKey     (e.g., "rhl:geo:drivers")
-- KEYS[4]: sessionKey (e.g., "rhl:driver:session:<driverId>") [Optional / M06]
-- KEYS[5]: vehicleKey (e.g., "rhl:driver:vehicle:<driverId>") [Optional / M06]
-- ARGV[1]: driverId   (UUID string)
-- ARGV[2]: longitude  (coordinate as string)
-- ARGV[3]: latitude   (coordinate as string)
-- ARGV[4]: sequence   (sequence integer as string, or "" if not provided)
-- ARGV[5]: timestamp  (epoch millis as string)
-- ARGV[6]: ttlSeconds (integer as string, e.g., "90")
-- ARGV[7]: sessionId  (UUID string, or "" if not provided)
--
-- Returns:
--  1 : SUCCESS
-- -1 : DRIVER_OFFLINE (liveKey does not exist)
-- -2 : OUT_OF_ORDER_TELEMETRY (incomingSeq < currentSeq)
-- -3 : DUPLICATE_TELEMETRY (incomingSeq == currentSeq)
-- -4 : STALE_SESSION_PACKET (incomingSession != currentSession in Redis)

local ttl = tonumber(ARGV[6])

-- Step 1: Check if driver presence exists
local liveExists = redis.call('EXISTS', KEYS[1])
if liveExists == 0 then
    return -1
end

-- Step 2: Validate active session fencing (AUD-SES-01)
if #KEYS >= 4 and KEYS[4] and KEYS[4] ~= '' then
    local currentSession = redis.call('GET', KEYS[4])
    if currentSession and currentSession ~= false then
        local incomingSession = ARGV[7]
        if not incomingSession or incomingSession == '' or incomingSession ~= currentSession then
            return -4
        end
    end
end

-- Step 3: Validate sequence number if provided
local incomingSeqStr = ARGV[4]
if incomingSeqStr and incomingSeqStr ~= '' then
    local incomingSeq = tonumber(incomingSeqStr)
    local currentSeqStr = redis.call('GET', KEYS[2])
    if currentSeqStr and currentSeqStr ~= false then
        local currentSeq = tonumber(currentSeqStr)
        if incomingSeq < currentSeq then
            return -2
        end
        if incomingSeq == currentSeq then
            return -3
        end
    end
end

-- Step 4: Atomically update GEO spatial index (fails-safe before mutating sequence or presence)
redis.call('GEOADD', KEYS[3], ARGV[2], ARGV[3], ARGV[1])

-- Step 5: After GEOADD succeeds, commit sequence update and refresh presence & session TTL
if incomingSeqStr and incomingSeqStr ~= '' then
    redis.call('SET', KEYS[2], incomingSeqStr, 'EX', ttl)
end

redis.call('SET', KEYS[1], ARGV[5], 'EX', ttl)

if #KEYS >= 4 and KEYS[4] and KEYS[4] ~= '' then
    local currentSession = redis.call('GET', KEYS[4])
    if currentSession and currentSession ~= false then
        redis.call('EXPIRE', KEYS[4], ttl)
    end
end

if #KEYS >= 5 and KEYS[5] and KEYS[5] ~= '' then
    local currentVehicle = redis.call('GET', KEYS[5])
    if currentVehicle and currentVehicle ~= false then
        redis.call('EXPIRE', KEYS[5], ttl)
    end
end

return 1
