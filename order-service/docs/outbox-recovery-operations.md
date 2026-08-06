# Outbox retry and recovery operations

## Rollout

This change is deployed with the additive `V8__add_outbox_retry_recovery.sql` migration. Apply the migration before starting a version that uses lease-aware Outbox publishing; it adds retry, lease, and audit data without removing existing Outbox records.

Before starting any lease-aware Order Service instance, stop every legacy Relay instance. Running the legacy and lease-aware Relays together defeats the lease claim and can create concurrent publishes. After the legacy Relay is stopped, deploy the migration and the lease-aware version, then enable its Relay. Confirm that pending records are claimed and published before scaling the new version.

Use Order Service-owned runtime defaults: a 10-second publish timeout, a 30-second lease, retry delays from 5 seconds to 5 minutes, and at most 10 automatic attempts. The monitor runs once a minute by default.

## Failed-event inspection and redrive

These endpoints are reached through the API Gateway, which validates the administrator role. The Order Service uses the gateway-provided user ID only as the redrive audit actor. Set the values in the execution environment; do not put access tokens or event payloads into shell history, logs, or tickets.

```bash
curl --request GET "${API_GATEWAY_BASE_URL}/api/v1/admin/outbox-events?page=1&size=20" \
  --header "Authorization: Bearer ${ADMIN_ACCESS_TOKEN}"
```

```bash
curl --request POST "${API_GATEWAY_BASE_URL}/api/v1/admin/outbox-events/${OUTBOX_EVENT_ID}/redrive" \
  --header "Authorization: Bearer ${ADMIN_ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{"reason":"Kafka recovery verified"}'
```

The GET endpoint returns failed events. Redrive accepts only an event currently in `FAILED` state, creates its audit entry, and returns it to `PENDING` for a new lease-aware publish attempt.

| HTTP status | Meaning | Operator action |
| --- | --- | --- |
| 400 | Invalid page values, malformed event ID or gateway-provided user-ID UUID, or blank/oversized reason | Correct the request and retry. |
| 401 | Gateway authentication is absent/invalid, or the gateway-provided `X-User-Id` is missing for redrive | Authenticate through the Gateway; do not send `X-User-Id` from the client. |
| 404 | The Outbox event does not exist | Re-check the event ID and retention/incident context. |
| 409 | The event is not in `FAILED` state | Refresh the GET result; do not retry redrive blindly. |

Do not copy Kafka event payloads, exception stacks, credentials, or personal data into the redrive reason or operational notes.

## Metrics, thresholds, and logs

The service exposes these Micrometer meters:

| Meter | Tags/value | Purpose |
| --- | --- | --- |
| `order.outbox.publish.attempts` | `outcome=success|failure` | Kafka publication outcomes. |
| `order.outbox.retry.attempts` | `outcome=success|failure` | Outcomes for events that were retry attempts. |
| `order.outbox.redrive.requests` | `outcome=success|failure` | Administrator redrive outcomes. |
| `order.outbox.events` | `status=failed` gauge | Current failed-event backlog. |
| `order.outbox.oldest.unpublished.age` | seconds gauge | Age of the oldest unpublished event. |

Default monitor thresholds are failed-event count `1` and oldest unpublished age `300000 ms` (5 minutes). On transition into a breach the monitor emits a `WARN` log for `outbox_failed` or `outbox_oldest_unpublished`; on transition back below the threshold it emits an `INFO` recovery log. The default monitor interval is `60000 ms`.

External alert-system rules, routing, and notification delivery are deliberately deferred to an infrastructure follow-up. Until then, operations should collect these meters and threshold-transition logs with the existing platform observability tooling.

## Delivery guarantee and recovery boundary

The Relay is at-least-once, not exactly-once. A Kafka publish can succeed just before the database status update fails or the process stops; after the lease expires, the event can be published again. Downstream consumers must continue using the event ID for idempotency. The lease only prevents concurrent active claims; it does not eliminate this database/Kafka failure boundary.
