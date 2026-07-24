if redis.call('HGET', KEYS[1], 'status') ~= 'RUNNING' then
  return 0
end
local deadline = tonumber(redis.call('HGET', KEYS[1], 'deadline-at'))
if not deadline or tonumber(ARGV[2]) > deadline then
  return 0
end
local ranks = {ANALYZING = 1, FETCHING_DATA = 2, GENERATING_ANSWER = 3}
local currentStage = redis.call('HGET', KEYS[1], 'stage')
if not ranks[currentStage] or not ranks[ARGV[1]] or ranks[ARGV[1]] < ranks[currentStage] then
  return 0
end
redis.call('HSET', KEYS[1], 'stage', ARGV[1])
return 1
