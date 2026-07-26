local conversationId = redis.call('HGET', KEYS[1], 'conversation-id')
if not conversationId or conversationId ~= ARGV[2] then
  return 0
end

local activeRunId = redis.call('GET', KEYS[2])
local latestRunId = redis.call('HGET', KEYS[1], 'latest-run-id')
local cancelledRunId = ARGV[3]

if cancelledRunId ~= '' then
  if latestRunId ~= cancelledRunId then
    return 0
  end
  local runKey = ARGV[4] .. '{' .. cancelledRunId .. '}'
  if redis.call('HGET', runKey, 'status') ~= 'CANCELLED'
      or redis.call('HGET', runKey, 'actor-id') ~= ARGV[1] then
    return 0
  end
  if activeRunId and activeRunId ~= cancelledRunId then
    return 0
  end
elseif activeRunId then
  return 0
end

redis.call('DEL', ARGV[5] .. '{' .. conversationId .. '}:messages')
redis.call('DEL', KEYS[1])
if activeRunId and activeRunId == cancelledRunId then
  redis.call('DEL', KEYS[2])
end
return 1
