-- One token bucket per card (ADR 0015). Redis runs a script alone, so check-and-take is atomic: two billing
-- instances can never both take the last token.
--   KEYS[1] = the card's bucket, a hash {tokens, ts}
--   ARGV[1] = capacity (burst), ARGV[2] = refill interval in ms (one token comes back per interval)
-- Returns {allowed (1 or 0), whole tokens left, ms until the next token (0 if allowed)}
local capacity = tonumber(ARGV[1])
local interval = tonumber(ARGV[2])
-- Redis' own clock, in ms: the same for every billing instance, whatever their clocks say
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil or ts == nil then
  tokens = capacity                                   -- a card we haven't seen (or whose bucket expired): full
  ts = now
end
if now > ts then                                      -- refill for the time that passed, fractions included
  tokens = math.min(capacity, tokens + (now - ts) / interval)
  ts = now
end

local allowed = 0
local wait = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  wait = math.ceil((1 - tokens) * interval)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', ts)
-- a full bucket needs no key: forget the card once it would have refilled completely
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * interval) + 1000)
return {allowed, math.floor(tokens), wait}
