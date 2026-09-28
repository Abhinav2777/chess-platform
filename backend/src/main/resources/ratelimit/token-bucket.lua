-- Token bucket. Take one token from KEYS[1] if there is one.
--
-- KEYS[1] rl:{limit}:{subject}   HASH  tokens, ts (ms)
-- ARGV[1] capacity               the burst allowed
-- ARGV[2] refill per millisecond capacity / period
--
-- Returns {allowed (1|0), retryAfterMs}.
--
-- Atomic, so two instances taking the last token at once cannot both get it. The state is
-- two numbers per key, refilled lazily on read — nothing runs between requests. The key
-- expires once it would have refilled completely: an idle subject costs no memory, and a
-- missing key means "full", so expiry and refill agree.
--
-- Time from Valkey's clock, one authority for every instance (as in pair.lua).

local capacity = tonumber(ARGV[1])
local refillPerMs = tonumber(ARGV[2])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens, ts = capacity, now
end

-- Refill for the time elapsed, never past capacity. max(0, …) guards against a clock that
-- stepped backwards — the bucket simply does not refill rather than draining.
tokens = math.min(capacity, tokens + math.max(0, now - ts) * refillPerMs)

local allowed, retryAfterMs = 0, 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retryAfterMs = math.ceil((1 - tokens) / refillPerMs)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refillPerMs) + 1000)
return {allowed, retryAfterMs}
