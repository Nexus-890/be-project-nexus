-- Kiểm tra và tăng counter nguyên tử, kể cả khi chạy nhiều gateway.
-- Kết quả: {được phép (1/0), quota còn lại, TTL còn lại (mili giây)}.
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2])

local count = tonumber(redis.call("GET", key) or "0")

-- Request đầu tiên bắt đầu cửa sổ 60 giây.
if count == 0 then
    redis.call("SET", key, 1, "PX", windowMillis)
    return {1, limit - 1, windowMillis}
end

local ttlMillis = redis.call("PTTL", key)

-- Bảo đảm counter không tồn tại vĩnh viễn.
if ttlMillis < 0 then
    redis.call("PEXPIRE", key, windowMillis)
    ttlMillis = windowMillis
end

-- Request bị chặn không tăng counter và không gia hạn cửa sổ.
if count >= limit then
    return {0, 0, ttlMillis}
end

count = redis.call("INCR", key)
return {1, limit - count, ttlMillis}
