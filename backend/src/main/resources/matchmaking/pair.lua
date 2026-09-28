-- Find ONE compatible pair in a time control's queue and claim both players.
--
-- This script is the reason matchmaking needs no lock. Valkey executes scripts one at a
-- time, so "read the queue, choose two players, remove both" cannot interleave with any
-- other instance's pairing, a seek or a cancel. Every instance may run it concurrently;
-- a player can be claimed at most once.
--
-- KEYS[1] mm:q:{tc}:rating   KEYS[2] mm:q:{tc}:since
-- ARGV[1] base window        ARGV[2] window growth per second waited
-- ARGV[3] max window         ARGV[4] how many of the longest waiters to consider
-- ARGV[5] PENDING TTL (s)    ARGV[6] seek key prefix   ARGV[7] match key prefix
-- ARGV[8] neighbours to inspect on each side of a rating
--
-- Returns {a, b, aWaitedMs, bWaitedMs} or false when no pair is possible right now.
--
-- Bounded work per call: at most ARGV[4] seekers, each inspecting at most 2 * ARGV[8]
-- neighbours. The caller loops for more pairs; each call is short, because while a script
-- runs Valkey serves nobody else.

local rating, since = KEYS[1], KEYS[2]
local baseWindow, growth, maxWindow = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])
local scanLimit, pendingTtl = tonumber(ARGV[4]), ARGV[5]
local seekPrefix, matchPrefix, neighbours = ARGV[6], ARGV[7], tonumber(ARGV[8])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local function evict(user)
  redis.call('ZREM', rating, user)
  redis.call('ZREM', since, user)
end

-- The seek key's TTL is the failure detector. A client that stopped re-asserting its seek
-- (tab closed, instance died, network gone) has no seek key, and its queue entry is
-- removed here, lazily, the first time pairing walks past it.
local function alive(user)
  return redis.call('EXISTS', seekPrefix .. user) == 1
end

-- Oldest first: the player who has waited longest has the widest window and is served
-- first. That ordering also makes the window effectively symmetric — anyone older than
-- the current seeker was visited earlier with a window at least as wide, and found no one.
local oldest = redis.call('ZRANGE', since, 0, scanLimit - 1, 'WITHSCORES')

for i = 1, #oldest, 2 do
  local a, joinedA = oldest[i], tonumber(oldest[i + 1])
  local ratingA = tonumber(redis.call('ZSCORE', rating, a))

  if not alive(a) or ratingA == nil then
    evict(a)
  else
    local waited = math.max(0, now - joinedA) / 1000
    local window = math.min(baseWindow + growth * waited, maxWindow)

    local best, bestDiff
    local function consider(candidates)
      for j = 1, #candidates, 2 do
        local b = candidates[j]
        if b ~= a then
          if not alive(b) then
            evict(b)
          else
            local diff = math.abs(tonumber(candidates[j + 1]) - ratingA)
            if bestDiff == nil or diff < bestDiff then
              best, bestDiff = b, diff
            end
          end
        end
      end
    end
    -- Nearest neighbours above and below, not the whole window: a 400-point window in a
    -- busy queue could hold thousands of players, and only the closest matter.
    consider(redis.call('ZRANGEBYSCORE', rating, ratingA, ratingA + window,
                        'WITHSCORES', 'LIMIT', 0, neighbours))
    consider(redis.call('ZREVRANGEBYSCORE', rating, ratingA, ratingA - window,
                        'WITHSCORES', 'LIMIT', 0, neighbours))

    if best then
      local joinedB = tonumber(redis.call('ZSCORE', since, best))
      evict(a)
      evict(best)
      redis.call('DEL', seekPrefix .. a, seekPrefix .. best)
      -- PENDING, with a TTL, until the game row is committed. If the instance holding this
      -- pair dies before that, the marker expires and the players' next re-seek queues
      -- them again. Nothing here is the only copy of anything that matters (ADR-004).
      redis.call('SET', matchPrefix .. a, 'PENDING', 'EX', pendingTtl)
      redis.call('SET', matchPrefix .. best, 'PENDING', 'EX', pendingTtl)
      return {a, best, tostring(now - joinedA), tostring(now - joinedB)}
    end
  end
end

return false
