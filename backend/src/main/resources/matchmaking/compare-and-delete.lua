-- Delete each key in KEYS only if it still holds ARGV[1]. Returns how many were deleted.
--
-- GET-then-DEL from Java would race: between the two, the key could be replaced (a
-- PENDING marker overwritten by the game id, or a new match recorded), and the DEL would
-- remove a value this caller never saw. Used to release PENDING markers after a failed
-- game creation, and to acknowledge a delivered match.

local deleted = 0
for _, key in ipairs(KEYS) do
  if redis.call('GET', key) == ARGV[1] then
    redis.call('DEL', key)
    deleted = deleted + 1
  end
end
return deleted
