-- Join (or re-assert) a seek. Atomic: Valkey runs one script at a time, so no other
-- seek, cancel or pairing can observe this player half-queued.
--
-- KEYS[1] mm:q:{tc}:rating   ZSET  userId -> rating
-- KEYS[2] mm:q:{tc}:since    ZSET  userId -> enqueue time (ms, Valkey clock)
-- KEYS[3] mm:seek:{userId}   STRING tc, with TTL — the liveness key
-- KEYS[4] mm:match:{userId}  STRING 'PENDING' or a game id
-- ARGV[1] userId  ARGV[2] rating  ARGV[3] tc  ARGV[4] seek TTL (seconds)
--
-- Returns {status, detail}:
--   {'MATCHED', 'PENDING' | gameId}  already paired; nothing queued
--   {'ALREADY_SEEKING', otherTc}     seeking a different time control; nothing changed
--   {'QUEUED', tc}                   queued, or re-asserted (TTL refreshed)

local match = redis.call('GET', KEYS[4])
if match then
  return {'MATCHED', match}
end

local current = redis.call('GET', KEYS[3])
if current and current ~= ARGV[3] then
  return {'ALREADY_SEEKING', current}
end

-- Queue time from Valkey's own clock, so every instance measures waiting against one
-- authority — the same reasoning as PostgreSQL now() for game clocks (ADR-006).
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('SET', KEYS[3], ARGV[3], 'EX', ARGV[4])
-- NX on both: a re-assert keeps the original rating snapshot and, crucially, the original
-- join time, so re-seeking every 15 s never resets how long a player has waited. On a
-- first seek, or after the entry was lost (Valkey restart), NX simply adds it — a
-- re-assert is also a repair.
redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
redis.call('ZADD', KEYS[2], 'NX', now, ARGV[1])
return {'QUEUED', ARGV[3]}
