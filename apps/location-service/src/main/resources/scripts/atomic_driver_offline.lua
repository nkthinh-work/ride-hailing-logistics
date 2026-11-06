-- atomic_driver_offline.lua
-- Atomically evicts driver presence, session token, selected vehicle, sequence, and removes member from GEO index.
--
-- KEYS[1]: liveKey    (e.g., "rhl:driver:live:<driverId>")
-- KEYS[2]: seqKey     (e.g., "rhl:driver:seq:<driverId>")
-- KEYS[3]: geoKey     (e.g., "rhl:geo:drivers")
-- KEYS[4]: sessionKey (e.g., "rhl:driver:session:<driverId>") [Optional / M06]
-- KEYS[5]: vehicleKey (e.g., "rhl:driver:vehicle:<driverId>") [Optional / M06]
-- ARGV[1]: driverId   (UUID string)
--
-- Returns:
--  1 : SUCCESS

redis.call('DEL', KEYS[1])
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])

if #KEYS >= 4 and KEYS[4] and KEYS[4] ~= '' then
    redis.call('DEL', KEYS[4])
end

if #KEYS >= 5 and KEYS[5] and KEYS[5] ~= '' then
    redis.call('DEL', KEYS[5])
end

return 1
