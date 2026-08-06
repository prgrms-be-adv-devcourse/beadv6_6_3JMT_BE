if redis.call('HGET', KEYS[4], 'status') ~= 'RUNNING' then
  return 0
end
if redis.call('HGET', KEYS[4], 'actor-id') ~= ARGV[2] then
  return 0
end
if redis.call('GET', KEYS[3]) ~= ARGV[1] then
  return 0
end
local deadline = tonumber(redis.call('HGET', KEYS[4], 'deadline-at'))
if not deadline or tonumber(ARGV[3]) > deadline then
  return 0
end

redis.call('HSET', KEYS[4],
  'status', 'COMPLETED',
  'stage', 'DONE',
  'answer', ARGV[4],
  'completed-at', ARGV[5])
redis.call('HDEL', KEYS[4], 'failed-at', 'cancelled-at', 'error-code', 'error-message')
redis.call('RPUSH', KEYS[2], ARGV[6])
redis.call('LTRIM', KEYS[2], -tonumber(ARGV[7]), -1)
redis.call('PEXPIRE', KEYS[2], ARGV[8])
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('PEXPIRE', KEYS[4], ARGV[8])
redis.call('DEL', KEYS[3])
return 1
