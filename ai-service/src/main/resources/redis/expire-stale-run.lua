if redis.call('HGET', KEYS[2], 'status') ~= 'RUNNING' then
  return 0
end
if redis.call('HGET', KEYS[2], 'actor-id') ~= ARGV[1] then
  return 0
end
local deadline = tonumber(redis.call('HGET', KEYS[2], 'deadline-at'))
if not deadline or tonumber(ARGV[3]) <= deadline then
  return 0
end

redis.call('HSET', KEYS[2],
  'status', 'FAILED',
  'error-code', ARGV[4],
  'error-message', ARGV[5],
  'failed-at', ARGV[3])
redis.call('HDEL', KEYS[2], 'completed-at', 'cancelled-at', 'answer')
redis.call('PEXPIRE', KEYS[2], ARGV[6])
if redis.call('GET', KEYS[1]) == ARGV[2] then
  redis.call('DEL', KEYS[1])
end
return 1
