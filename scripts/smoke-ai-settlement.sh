#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf 'AI settlement smoke failed: %s\n' "$1" >&2
  exit 1
}

extract_safe_code() {
  local response_file="$1"
  local candidate

  candidate="$(jq -r '.code // empty' "$response_file" 2>/dev/null || true)"
  if [[ "$candidate" =~ ^[A-Z][A-Z0-9_]{0,79}$ ]]; then
    printf '%s' "$candidate"
  else
    printf 'unknown'
  fi
}

[[ -n "${AI_GATEWAY_BASE_URL:-}" ]] || fail 'AI_GATEWAY_BASE_URL is required'
[[ -n "${AI_SMOKE_BEARER_TOKEN:-}" ]] || fail 'AI_SMOKE_BEARER_TOKEN is required'

command -v curl >/dev/null 2>&1 || fail 'curl is required'
command -v jq >/dev/null 2>&1 || fail 'jq is required'

if [[ "$AI_SMOKE_BEARER_TOKEN" == *$'\n'* || "$AI_SMOKE_BEARER_TOKEN" == *$'\r'* ]]; then
  fail 'AI_SMOKE_BEARER_TOKEN contains an invalid line break'
fi

gateway_base_url="$AI_GATEWAY_BASE_URL"
while [[ "$gateway_base_url" == */ ]]; do
  gateway_base_url="${gateway_base_url%/}"
done

case "$gateway_base_url" in
  http://* | https://*) ;;
  *) fail 'AI_GATEWAY_BASE_URL must use http or https' ;;
esac

question="${AI_SMOKE_QUESTION:-지난달 정산 매출, 환불, 수수료, 지급액을 요약해줘}"
[[ -n "${question//[[:space:]]/}" ]] || fail 'AI_SMOKE_QUESTION must not be blank'
(( ${#question} <= 2000 )) || fail 'AI_SMOKE_QUESTION must be at most 2000 characters'

umask 077
smoke_tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ai-settlement-smoke.XXXXXX")"
trap 'rm -rf -- "$smoke_tmp_dir"' EXIT

request_file="$smoke_tmp_dir/request.json"
post_body_file="$smoke_tmp_dir/post-response.json"
sse_header_file="$smoke_tmp_dir/sse-headers.txt"
sse_body_file="$smoke_tmp_dir/sse-events.txt"
conversation_body_file="$smoke_tmp_dir/conversation-response.json"

printf '%s' "$question" | jq -Rs '{content: .}' > "$request_file"

post_http_status='000'
if post_http_status="$(
  curl --fail-with-body --silent --show-error \
    --output "$post_body_file" \
    --write-out '%{http_code}' \
    -X POST "${gateway_base_url}/api/v2/ai/settlement/conversations/current/messages" \
    -H "Authorization: Bearer ${AI_SMOKE_BEARER_TOKEN}" \
    -H 'Content-Type: application/json' \
    --data-binary "@${request_file}"
)"; then
  post_curl_exit=0
else
  post_curl_exit=$?
fi

post_safe_code="$(extract_safe_code "$post_body_file")"
if (( post_curl_exit != 0 )) || [[ "$post_http_status" != '202' ]]; then
  fail "POST was not accepted (curl_exit=${post_curl_exit}, http_status=${post_http_status}, code=${post_safe_code})"
fi

if ! jq -e \
  '.success == true and .data.status == "RUNNING" and ((.data.runId | type) == "string")' \
  "$post_body_file" >/dev/null 2>&1; then
  fail 'POST returned an invalid acceptance envelope (http_status=202, code=invalid_response)'
fi

run_id="$(jq -r '.data.runId' "$post_body_file")"
uuid_v4_pattern='^[[:xdigit:]]{8}-[[:xdigit:]]{4}-4[[:xdigit:]]{3}-[89aAbB][[:xdigit:]]{3}-[[:xdigit:]]{12}$'
[[ "$run_id" =~ $uuid_v4_pattern ]] ||
  fail 'POST returned a non-UUID-v4 runId (http_status=202, code=invalid_run_id)'

sse_metrics=$'000\t0'
if sse_metrics="$(
  curl --fail-with-body --silent --show-error --no-buffer --max-time 125 \
    --output "$sse_body_file" \
    --dump-header "$sse_header_file" \
    --write-out $'%{http_code}\t%{time_total}' \
    "${gateway_base_url}/api/v2/ai/settlement/runs/${run_id}/events" \
    -H "Authorization: Bearer ${AI_SMOKE_BEARER_TOKEN}" \
    -H 'Accept: text/event-stream'
)"; then
  sse_curl_exit=0
else
  sse_curl_exit=$?
fi

IFS=$'\t' read -r sse_http_status sse_elapsed_seconds <<< "$sse_metrics"
[[ "$sse_http_status" == '200' ]] ||
  fail "SSE returned an unexpected status (curl_exit=${sse_curl_exit}, http_status=${sse_http_status:-000})"
grep -Eiq '^content-type:[[:space:]]*text/event-stream([[:space:]]*;|[[:space:]]*$)' "$sse_header_file" ||
  fail 'SSE response Content-Type is not text/event-stream'

if grep -Eq '^event:[[:space:]]*failed[[:space:]]*\r?$' "$sse_body_file"; then
  fail 'SSE reached terminal event=failed'
fi
if grep -Eq '^event:[[:space:]]*cancelled[[:space:]]*\r?$' "$sse_body_file"; then
  fail 'SSE reached terminal event=cancelled'
fi
grep -Eq '^event:[[:space:]]*done[[:space:]]*\r?$' "$sse_body_file" ||
  fail "SSE did not reach event=done within 125 seconds (curl_exit=${sse_curl_exit})"
(( sse_curl_exit == 0 )) ||
  fail "SSE connection ended unexpectedly after event=done (curl_exit=${sse_curl_exit})"

[[ "$sse_elapsed_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]] ||
  fail 'SSE duration could not be measured'
sse_elapsed_whole_seconds="${sse_elapsed_seconds%%.*}"
if (( sse_elapsed_whole_seconds >= 20 )); then
  done_line="$(grep -En -m 1 '^event:[[:space:]]*done[[:space:]]*\r?$' "$sse_body_file" | cut -d: -f1)"
  heartbeat_line="$(grep -En -m 1 '^:[[:space:]]*heartbeat[[:space:]]*\r?$' "$sse_body_file" | cut -d: -f1 || true)"
  [[ -n "$heartbeat_line" && "$heartbeat_line" -lt "$done_line" ]] ||
    fail 'SSE ran for at least 20 seconds without a heartbeat before event=done'
fi

conversation_http_status='000'
if conversation_http_status="$(
  curl --fail-with-body --silent --show-error \
    --output "$conversation_body_file" \
    --write-out '%{http_code}' \
    "${gateway_base_url}/api/v2/ai/settlement/conversations/current" \
    -H "Authorization: Bearer ${AI_SMOKE_BEARER_TOKEN}"
)"; then
  conversation_curl_exit=0
else
  conversation_curl_exit=$?
fi

conversation_safe_code="$(extract_safe_code "$conversation_body_file")"
if (( conversation_curl_exit != 0 )) || [[ "$conversation_http_status" != '200' ]]; then
  fail "GET current failed (curl_exit=${conversation_curl_exit}, http_status=${conversation_http_status}, code=${conversation_safe_code})"
fi

if ! jq -e \
  '.success == true and (.data != null) and ((.data.messages | type) == "array") and ((.data.messages | length) > 0)' \
  "$conversation_body_file" >/dev/null 2>&1; then
  fail 'GET current returned an invalid conversation envelope'
fi

if answer="$(
  jq -er \
    '.data.messages[-1] | select(.role == "ASSISTANT") | .content | select(type == "string" and test("[^[:space:]]"))' \
    "$conversation_body_file" 2>/dev/null
)"; then
  :
else
  fail 'GET current did not end with a non-blank assistant message'
fi

if [[ "$answer" == *'?'* || "$answer" == *'？'* ]]; then
  fail 'stored assistant answer contains a follow-up question'
fi

uuid_pattern='[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}'
[[ ! "$answer" =~ $uuid_pattern ]] || fail 'stored assistant answer contains a UUID'

answer_lc="$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')"
for forbidden_text in 'x-user-id' 'x-internal-service-token' 'system prompt' 'tool payload'; do
  [[ "$answer_lc" != *"$forbidden_text"* ]] ||
    fail 'stored assistant answer contains an internal field or prompt label'
done

raw_id_field_pattern='(seller|settlement|settlement[_[:space:]-]?detail|detail|order|order[_[:space:]-]?product)[_[:space:]-]?id'
[[ ! "$answer_lc" =~ $raw_id_field_pattern ]] ||
  fail 'stored assistant answer contains a raw seller, settlement, detail, or order ID field'

printf 'AI settlement HTTP/SSE smoke passed: POST=202, SSE=done (%ss), stored_answer_chars=%d\n' \
  "$sse_elapsed_seconds" "${#answer}"
printf '%s\n' \
  'Rollout acceptance remains pending until safe evidence confirms >=1 AI tool call and >=1 User gRPC call.'

if [[ "${AI_SMOKE_PRINT_ANSWER:-false}" == 'true' ]]; then
  printf '%s\n' "$answer"
fi
