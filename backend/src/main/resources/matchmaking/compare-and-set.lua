-- Set KEYS[1] to ARGV[2] with TTL ARGV[3] seconds, only if it currently holds ARGV[1].
-- Returns 1 if set, 0 otherwise.
--
-- Records a created game over its PENDING marker. If the marker has already lapsed and the
-- player has since been claimed by a newer pairing, overwriting blindly would replace that
-- newer claim with a stale game — so the write is conditional.

if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
  return 1
end
return 0
