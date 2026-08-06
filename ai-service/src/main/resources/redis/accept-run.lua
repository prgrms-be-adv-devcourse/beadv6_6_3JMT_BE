local active = redis.call('GET', KEYS[3])
if active then
  return {0, active}
end

local conversationId = redis.call('HGET', KEYS[1], 'conversation-id')
if not conversationId then
  conversationId = ARGV[1]
end
local messagesKey = KEYS[2]
if conversationId ~= ARGV[1] then
  messagesKey = ARGV[9] .. '{' .. conversationId .. '}:messages'
end

local locked = redis.call('SET', KEYS[3], ARGV[2], 'NX', 'PX', ARGV[8])
if not locked then
  return {0, redis.call('GET', KEYS[3])}
end

redis.call('HSET', KEYS[1],
  'conversation-id', conversationId,
  'latest-run-id', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[7])
if redis.call('EXISTS', messagesKey) == 1 then
  redis.call('PEXPIRE', messagesKey, ARGV[7])
end
redis.call('HSET', KEYS[4],
  'run-id', ARGV[2],
  'conversation-id', conversationId,
  'actor-id', ARGV[3],
  'question', ARGV[4],
  'status', 'RUNNING',
  'stage', 'ANALYZING',
  'started-at', ARGV[5],
  'deadline-at', ARGV[6])
redis.call('PEXPIRE', KEYS[4], ARGV[7])
return {1, conversationId}
