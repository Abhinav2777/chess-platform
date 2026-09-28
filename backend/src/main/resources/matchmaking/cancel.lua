-- Leave the queue. Atomic with pairing: a cancel and a pairing that race resolve to one
-- or the other, never to "cancelled but also matched".
--
-- KEYS[1] mm:seek:{userId}   KEYS[2] mm:match:{userId}
-- ARGV[1] userId             ARGV[2] queue key prefix ('mm:q:')
--
-- Returns {'MATCHED', 'PENDING' | gameId} | {'CANCELLED', tc} | {'NOT_SEEKING', ''}
--
-- The queue keys are derived here rather than passed in KEYS, because only the seek key
-- knows which time control to leave. Fine on a single node; a cluster requires every key a
-- script touches to be declared — see ADR-016 on what sharding would change.

local match = redis.call('GET', KEYS[2])
if match then
  return {'MATCHED', match}
end

local tc = redis.call('GET', KEYS[1])
if not tc then
  return {'NOT_SEEKING', ''}
end

redis.call('ZREM', ARGV[2] .. tc .. ':rating', ARGV[1])
redis.call('ZREM', ARGV[2] .. tc .. ':since', ARGV[1])
redis.call('DEL', KEYS[1])
return {'CANCELLED', tc}
