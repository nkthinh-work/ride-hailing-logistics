-- atomic_driver_online.lua
-- Atomically registers driver presence, active session token, active vehicle, sequence, and GEO position.
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
-- ARGV[8]: vehicleId  (UUID string, or "" if not provided)
--
-- Returns:
--  1 : SUCCESS

local ttl = tonumber(ARGV[6])

local seqStr = ARGV[4]
if seqStr and seqStr ~= '' then
    redis.call('SET', KEYS[2], seqStr, 'EX', ttl)
end

redis.call('GEOADD', KEYS[3], ARGV[2], ARGV[3], ARGV[1])
redis.call('SET', KEYS[1], ARGV[5], 'EX', ttl)

if #KEYS >= 4 and KEYS[4] and KEYS[4] ~= '' then
    local sessId = ARGV[7]
    if sessId and sessId ~= '' then
        redis.call('SET', KEYS[4], sessId, 'EX', ttl)
    end
end

if #KEYS >= 5 and KEYS[5] and KEYS[5] ~= '' then
    local vehId = ARGV[8]
    if vehId and vehId ~= '' then
        redis.call('SET', KEYS[5], vehId, 'EX', ttl)
    end
end

return 1
