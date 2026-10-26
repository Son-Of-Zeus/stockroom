local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000
local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
local tokens = tonumber(state[1]) or capacity
local updated = tonumber(state[2]) or now
tokens = math.min(capacity, tokens + math.max(0, now - updated) * refill)
local allowed = 0
local retry = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry = math.ceil((1 - tokens) / refill * 1000)
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill * 1000) + 1000)
return {allowed, math.floor(tokens), retry}
