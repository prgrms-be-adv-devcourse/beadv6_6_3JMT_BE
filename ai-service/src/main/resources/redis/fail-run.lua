if redis.call('HGET', KEYS[2], 'status') ~= 'RUNNING' then
  return 0
end
if redis.call('HGET', KEYS[2], 'actor-id') ~= ARGV[2] then
  return 0
end
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return 0
end

redis.call('HSET', KEYS[2],
  'status', 'FAILED',
  'error-code', ARGV[3],
  'error-message', ARGV[4],
  'failed-at', ARGV[5])
redis.call('HDEL', KEYS[2], 'completed-at', 'cancelled-at', 'answer')
redis.call('PEXPIRE', KEYS[2], ARGV[6])
redis.call('DEL', KEYS[1])
return 1
