local conversationId = redis.call('HGET', KEYS[1], 'conversation-id')
local activeRunId = redis.call('GET', KEYS[2])
local latestRunId = redis.call('HGET', KEYS[1], 'latest-run-id')
local candidateRunId = activeRunId or latestRunId
local cancelledRunId = nil

if candidateRunId then
  local runKey = ARGV[4] .. '{' .. candidateRunId .. '}'
  if redis.call('HGET', runKey, 'status') == 'RUNNING'
      and redis.call('HGET', runKey, 'actor-id') == ARGV[1] then
    redis.call('HSET', runKey,
      'status', 'CANCELLED',
      'cancelled-at', ARGV[2])
    redis.call('HDEL', runKey, 'completed-at', 'failed-at', 'answer', 'error-code', 'error-message')
    redis.call('PEXPIRE', runKey, ARGV[3])
    cancelledRunId = candidateRunId
  end
end

if activeRunId then
  if redis.call('GET', KEYS[2]) == activeRunId then
    redis.call('DEL', KEYS[2])
  end
end

if conversationId then
  redis.call('DEL', ARGV[5] .. '{' .. conversationId .. '}:messages')
end
redis.call('DEL', KEYS[1])
return cancelledRunId
