local conversationId = redis.call('HGET', KEYS[1], 'conversation-id')
if not conversationId then
  return {}
end

local activeRunId = redis.call('GET', KEYS[2])
local latestRunId = redis.call('HGET', KEYS[1], 'latest-run-id')
local candidateRunId = activeRunId or latestRunId
local cancelledRunId = ''

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
    if activeRunId == candidateRunId then
      redis.call('PEXPIRE', KEYS[2], ARGV[5])
    end
  end
end

return {conversationId, cancelledRunId}
