# M1.10 — Runtime desired-state reconciliation

Status: partially implemented. Desired-state persistence, authenticated runtime observations, monotonic report sequencing, generation-aware dispatch, and generation-safe observation validation exist. Retry scheduling, automated remediation, and worker recovery remain future work.

## Purpose

The Console must distinguish the deployment lifecycle requested by an operator from the state observed by a runtime. A runtime may be offline, restart, lose a command response, or continue executing while the control plane is unavailable. Reconciliation makes those cases visible and recoverable without treating Console as the workflow execution plane.

## State model

Keep the existing deployment lifecycle status for compatibility. Add separate desired and observed fields:

- `desired_state`: `PRESENT`, `ABSENT`, or `ROLLBACK`.
- `observed_state`: `UNKNOWN`, `PENDING`, `APPLYING`, `RUNNING`, `FAILED`, `ABSENT`, or `ROLLING_BACK`.
- `observed_artifact_digest`: digest reported by runtime, nullable.
- `observed_command_id`: last command runtime says it applied, nullable.
- `observed_at`: runtime report timestamp.
- `reconciliation_state`: `IN_SYNC`, `DRIFTED`, `WAITING_RUNTIME`, `RETRYING`, or `MANUAL_INTERVENTION`.
- `reconciliation_reason`: bounded diagnostic code/message.
- `reconciliation_attempt` and `next_attempt_at`: bounded retry tracking.

Do not derive desired state solely from the existing lifecycle status. Migrations must backfill existing deployments conservatively: desired `PRESENT` for deployment records that are not terminally cancelled; observed `UNKNOWN`; reconciliation `WAITING_RUNTIME`. Do not infer that a deployment is running just because a command was acknowledged.

## Runtime report contract

Add an authenticated runtime report endpoint, scoped by runtime identity and organization/environment. Reports include:

- deployment record ID and deployment identifier;
- the current desired-state `generation` (required; a missing or mismatched generation is rejected with HTTP 409);
- observed state;
- artifact digest actually loaded (or null when absent);
- command ID and idempotency key last applied;
- runtime-generated observation time and monotonically increasing report sequence;
- optional bounded diagnostic code/message.

The server must use the authenticated runtime's identity for scope checks, not caller-supplied organization/environment. Reject reports for deployments assigned to another runtime. Make repeated reports idempotent; older sequence numbers must not overwrite newer observed state. The observation generation must exactly match the current desired-state generation. Reject an observation from an older or unknown future generation with HTTP 409 before mutating observed state or audit state. Validate digest format and bound diagnostic fields.

Runtime reports are evidence from the authenticated runtime, not independent attestation. Console should preserve the report timestamp and receipt timestamp separately.

## Reconciliation behavior

A reconciliation pass computes drift using the latest desired state and observed report:

- desired PRESENT + observed RUNNING + matching artifact digest => IN_SYNC;
- desired PRESENT + no report / expired report => WAITING_RUNTIME;
- desired PRESENT + wrong digest or failed state => DRIFTED;
- desired ABSENT + observed ABSENT => IN_SYNC;
- desired ABSENT + observed RUNNING => DRIFTED and enqueue an idempotent stop/remove command only when policy explicitly permits it;
- desired ROLLBACK => issue rollback only when a verified previous artifact/version exists; completion requires runtime report of the expected previous digest.

Do not automatically stop or remove workloads in the first slice. Drift detection and status reporting should ship before destructive reconciliation actions.

## Durability and concurrency

- Use a transactional outbox for commands created by reconciliation; command row and outbox row commit together.
- Stable idempotency key: deployment ID + desired-state generation + action.
- Lock deployment rows during generation changes and reconciliation decisions.
- Increment desired-state generation on operator change; apply command payloads and runtime observations carry generation.
- Reject observations from older/future generations for current-state decisions; stale reports are not allowed to mutate the current observed-state row.
- Bounded retry policy with exponential backoff and jitter; cap attempts. After exhaustion set MANUAL_INTERVENTION and preserve the last diagnostic.
- Reconciliation must be safe to run repeatedly and after process restart.
- Never hold a database transaction while making network calls to Runtime.
- Runtime remains functional when Console is unavailable; it can queue reports locally and retry after reconnect.

## APIs

Operator-scoped read endpoint:

`GET /api/v1/console/deployments/{deploymentId}/reconciliation`

Returns desired generation/state, observed state/digest/time, freshness, reconciliation status, diagnostic, and retry metadata. Apply the same organization/environment visibility rules as deployment detail.

Runtime report endpoint:

`POST /api/v1/runtime/deployments/{deploymentId}/observed-state`

Authenticated by runtime bearer credential. The path and body must be checked against the authenticated runtime's assigned deployment. Return the accepted report sequence and whether it was ignored as stale.

Operator mutation endpoint for desired state should be introduced only after authorization, audit events, and rollback target semantics are defined. Avoid exposing arbitrary state transitions through a generic patch.

## Audit events

Append audit events for desired-state changes, accepted/ignored runtime reports, drift detection, reconciliation command creation, retry exhaustion, and manual intervention. Never log bearer tokens or artifact contents.

## Implementation sequence

1. Migration and persistence model for desired/observed/reconciliation fields and generation.
2. Runtime report DTO/resource/service with scope validation, ordering, generation matching, and audit. (Implemented.)
3. Reconciliation evaluator (pure decision function) with unit tests.
4. Reuse the durable management-command queue and idempotent generation-aware command scheduling; do not introduce a duplicate outbox without a separate durability requirement. (Apply dispatch implemented.)
5. Scoped operator status endpoint.
6. Retry worker, bounded backoff, restart recovery, and integration tests. (M1.10.3 implemented.)
7. Only then add opt-in apply/rollback remediation actions.

## Acceptance criteria

- Existing lifecycle endpoints and status behavior remain backward compatible.
- Runtime cannot report for another runtime's deployment.
- Stale report sequence cannot overwrite newer state.
- Console restart does not lose pending reconciliation commands.
- Duplicate worker execution does not enqueue duplicate commands.
- Missing/stale runtime observation is shown as waiting, not falsely healthy.
- Artifact mismatch is surfaced as drift.
- Rollback is not complete until runtime reports the expected previous digest.
- Tests cover scope isolation, stale reports, duplicate delivery, restart recovery, retry cap, and digest mismatch.

## Explicitly out of scope

No runtime process execution in Console, no mandatory Kafka/Numou dependency, no automatic destructive stop, no independent remote attestation, and no claim of exactly-once delivery. Commands remain at-least-once and require runtime idempotency.


## M1.10.4 — operator-controlled recovery

Runtime-reported `FAILED` observations now enter `MANUAL_INTERVENTION` rather than being treated as an ordinary drift signal. An operator with `console:runtime:admin` permission can request a scoped retry through:

`POST /api/v1/console/deployments/{deploymentId}/reconciliation/retry`

The request requires a non-empty reason. The endpoint derives organization, environment scope, and actor from the authenticated Console operator; clients cannot supply or override those values. The retry request resets the automatic retry counter, schedules an immediate retry, and appends a tamper-evident audit record. The existing retry worker performs the dispatch and re-evaluates runtime compatibility before queuing an apply command.

Only deployments whose desired state is `PRESENT` and whose observed state is `FAILED`, or whose reconciliation state is `MANUAL_INTERVENTION`, are eligible. `ABSENT` and `ROLLBACK` are never retried by this endpoint. A newer desired generation clears scheduled retry metadata; stale observations remain rejected by the generation check. The runtime must also enforce generation fencing when applying commands, because a command already delivered before supersession cannot be recalled reliably by the Console alone.

The operator retry integration test covers the FAILED-to-MANUAL_INTERVENTION transition and the audited retry scheduling path. Authorization and organization/environment isolation are enforced by the existing authenticated operator context and scoped service lookup.

## M1.10.5 — reconciliation operations

The Console exposes a read-only, tenant/environment-scoped recovery queue:

`GET /api/v1/console/deployments/reconciliation?state=WAITING_RUNTIME&page=0&size=25`

Supported `state` filters are `IN_SYNC`, `DRIFTED`, `WAITING_RUNTIME`, `RETRYING`, and `MANUAL_INTERVENTION`. The default page size is 25 and the maximum is 100. Results are ordered by most recently updated and include deployment identity, environment/runtime, workflow metadata, desired generation, observed state, retry schedule, and reconciliation diagnostics. Scope is derived from the authenticated operator; organization/environment identifiers are never accepted from query parameters.

A deployment's immutable audit timeline is available through:

`GET /api/v1/console/deployments/{deploymentId}/reconciliation/audit?page=0&size=25`

It reads the existing audit chain and returns the sequence, timestamp, actor, action, outcome, and record hash. It does not create a second retry-history store.

Operator retry requests are rate-limited using the durable audit chain: at least 30 seconds between retries and no more than five operator retry requests per deployment in a rolling hour. The deployment row is locked while checking the policy and appending the audit event, so concurrent requests cannot bypass the per-deployment guard. A rejected request returns HTTP 429 without changing reconciliation state.

The Console does not cancel already-delivered or running runtime commands as part of queue visibility. Generation supersession remains visible through the current desired generation and runtime-observed generation checks; runtime-side fencing is still required for commands already delivered before a newer desired state.

## M1.10.6 — generation-aware command supersession

Management commands now persist their desired-state generation as a first-class column, with the generation backfilled from the existing typed payload during migration. The Console exposes paginated deployment command history at:

`GET /api/v1/console/deployments/{deploymentId}/commands?page=0&size=25`

The history includes command generation, status, delivery/acknowledgement/completion timestamps, supersession generation/time, attempts, and bounded result message. Access uses the authenticated operator's organization/environment scope.

When desired state changes and its generation increments, commands from older generations are handled according to delivery state:

- `PENDING`: atomically marked `SUPERSEDED`; it is no longer eligible for runtime polling.
- `LEASED` or `RUNNING`: marked `SUPERSESSION_REQUESTED`; the Console does not claim it can recall work already delivered or executing. The runtime must cooperatively stop/discard it and acknowledge `SUPERSEDED`.
- If the runtime reports success/failure after the supersession request, the command is recorded as `STALE_SUCCEEDED` or `STALE_FAILED`. That late result is auditable but cannot mutate the current deployment lifecycle.
- If the command lease expires without a runtime acknowledgement, it becomes `SUPERSESSION_UNCONFIRMED` and the current reconciliation generation moves to `MANUAL_INTERVENTION`. The Console does not automatically dispatch over an unconfirmed older operation.

The runtime acknowledgement endpoint accepts `SUPERSEDED` only when supersession was requested. A `STARTED` acknowledgement after that request is rejected with HTTP 409. Existing runtimes must implement this cooperative acknowledgement to complete the supersession handshake. Generation is also checked before applying terminal acknowledgement effects, so an older command cannot mark a newer generation deployed or failed.

The generation change and command-state transition share the desired-state transaction. Runtime acknowledgements acquire locks in the same deployment → reconciliation → command order to avoid lock-order inversion. This is still at-least-once command delivery; runtime-side generation fencing and idempotent execution remain required.

An administrator can resolve an `SUPERSESSION_UNCONFIRMED` command only after independently verifying that the old runtime operation is no longer executing:

`POST /api/v1/console/deployments/{deploymentId}/commands/{commandId}/resolve-supersession`

The request requires a reason and is appended to the audit chain. This is an explicit human assertion, not a remote cancellation signal. Until the command is runtime-confirmed or operator-resolved, retry dispatch remains blocked.

## M1.10.7 — continuous observation convergence

The scheduled reconciliation worker now re-evaluates the freshness of previously IN_SYNC and DRIFTED PRESENT deployments. When the last authenticated runtime observation is older than the two-minute observation window, the worker transitions the record to WAITING_RUNTIME, records the previous state in a bounded diagnostic, and appends DEPLOYMENT_OBSERVATION_EXPIRED to the existing audit chain.

The observation read model exposes a derived observationFresh flag based on the server receipt timestamp. It is false when no runtime observation has ever been received or when the last report has expired. Runtime-provided timestamps remain evidence and are not used as the freshness clock.

The worker processes a bounded batch and locks each deployment before its reconciliation row. It changes state only after rechecking the desired state, current reconciliation state, and receipt timestamp under lock. Repeated worker runs therefore do not emit duplicate expiry events or repeatedly reset the retry schedule. The existing timeout/backoff worker subsequently handles WAITING_RUNTIME; no second command queue or outbox is introduced.

This milestone is observational and non-destructive. It does not stop workloads for ABSENT, auto-apply rollback, or infer that an unreachable runtime has terminated. Existing generation fencing, command supersession, bounded retries, and operator escalation remain authoritative.


## M1.10.8 — fleet reconciliation summary

The operator-facing fleet summary is available at:

`GET /api/v1/console/deployments/reconciliation/summary`

It aggregates reconciliation states, due retries, unresolved command supersessions, and the oldest unresolved deployment for the authenticated organization/environment scope. It is read-only and does not dispatch commands or mutate deployment state. The existing paginated queue remains the drill-down surface.

## M1.10.9 — age-based reconciliation health

The Console exposes an age-based operational health view at:

`GET /api/v1/console/deployments/reconciliation/health`

The endpoint classifies unresolved reconciliation records by time since their last state update: fresh (under five minutes), warning (five to under fifteen minutes), and critical (fifteen minutes or more). The response includes the explicit thresholds, counts for each band, an overall HEALTHY/WARNING/CRITICAL status, and the oldest unresolved deployment. Counts and details are restricted to the authenticated operator's organization/environment scope; empty scope returns a healthy zero-count response.

This is a derived, read-only signal. It does not mutate reconciliation state, create another queue, dispatch retries, or claim that a runtime process has stopped. Age is based on the Console's persisted `updatedAt`, not a runtime-provided timestamp. Thresholds are fixed defaults in this milestone and can be made configurable through deployment policy in a later change.


## M1.10.10 — configurable reconciliation SLA policy

The age-band thresholds used by `GET /api/v1/console/deployments/reconciliation/health` are configuration-backed:

- `wayang.console.reconciliation.sla.warning-seconds` (default `300`)
- `wayang.console.reconciliation.sla.critical-seconds` (default `900`)

They can be set through `WAYANG_CONSOLE_RECONCILIATION_SLA_WARNING_SECONDS` and `WAYANG_CONSOLE_RECONCILIATION_SLA_CRITICAL_SECONDS`. Startup validation requires a positive warning threshold, a critical threshold strictly greater than warning, and a critical threshold no greater than 30 days. Invalid configuration fails startup rather than silently producing misleading classifications.

The health response reports the effective thresholds and `policyVersion=config-v1`. This is deployment-level configuration, not tenant-controlled input, so individual operators cannot weaken shared operational alerting. The endpoint remains read-only and organization/environment scoped.


## M1.10.11 — durable reconciliation SLA transitions

The scheduled reconciliation worker evaluates SLA transitions after observation expiry and retry scheduling. Each reconciliation row stores its last evaluated SLA status (HEALTHY, WARNING, or CRITICAL) and evaluation time. A transition is persisted only when the status changes; repeated worker evaluations do not emit duplicate audit records.

The transition is appended to the existing per-organization/environment tamper-evident audit chain as a RECONCILIATION_SLA_<FROM>_TO_<TO> action. The first evaluation uses UNINITIALIZED as the source status. Returning to HEALTHY is recorded when an unresolved deployment falls below the warning threshold or when reconciliation reaches IN_SYNC.

Operators can inspect the newest transitions through:

GET /api/v1/console/deployments/reconciliation/sla/history?page=0&size=25

The endpoint derives organization/environment scope from the authenticated operator, is read-only, and returns paginated transitions from the existing audit chain. No new event queue or independent history store is introduced. Evaluation is bounded to 100 candidates per worker pass and rechecks each candidate while holding locks in deployment -> reconciliation order.


## M1.10.12 — scoped SLA metrics and Prometheus exposition

The Console exposes aggregate SLA metrics for the authenticated operator's organization and permitted environments:

- JSON: `GET /api/v1/console/deployments/reconciliation/sla/metrics`
- Prometheus text format: `GET /api/v1/console/deployments/reconciliation/sla/metrics/prometheus`

The exposition includes unresolved, fresh, warning, and critical gauges; configured warning and critical thresholds; and append-only transition counters by destination state. The endpoint is protected by the same authenticated operator access check as the reconciliation health and history endpoints. A Prometheus scraper must use a dedicated read-authorized operator identity and bearer token.

Metrics are aggregated across the caller's permitted environments. No organization, environment, deployment, runtime, workflow, or user identifiers are emitted as labels; only the fixed destination-state and policy-version labels are used. This avoids tenant disclosure and unbounded label cardinality. Empty environment scope returns zero-valued metrics. The endpoint is read-only and does not trigger SLA evaluation, append audit records, dispatch commands, or mutate reconciliation state.

The transition counters are derived from the existing append-only audit chain, not from a second metrics event store. Current-state gauges use the same Console timestamps and configurable SLA thresholds as the reconciliation health endpoint.


## M1.10.13 — SLA alert policy and notification SPI

SLA transitions can publish opt-in alert signals after the transaction commits. The policy is configured independently for warning, critical, and recovery transitions:

- `wayang.console.reconciliation.sla.alerts.warning.enabled`
- `wayang.console.reconciliation.sla.alerts.critical.enabled`
- `wayang.console.reconciliation.sla.alerts.recovery.enabled`

All default to `false`; equivalent environment variables are documented in `application.properties`. The authenticated operator can inspect the effective policy at:

`GET /api/v1/console/deployments/reconciliation/sla/alert-policy`

Extensions provide `ReconciliationSlaAlertSink` CDI beans. The core has no dependency on email, Slack, or webhook vendors. Alert intents are written only for actual persisted SLA transitions whose corresponding alert category is enabled. Delivery is delegated to provider extensions; the audit-backed replay and provider acceptance contract is defined in M1.10.14. No notification destination or secret is accepted from operator request payloads.


## M1.10.14 — durable SLA alert delivery contract

Enabled WARNING, CRITICAL, and recovery transitions now append a separate `RECONCILIATION_ALERT_<FROM>_TO_<TO>` intent record to the same per-organization/environment tamper-evident audit chain and in the same transaction as the SLA state transition. Disabled alert categories do not create delivery intents. This is an audit-backed replay source, not a second outbox or queue.

The existing scheduled reconciliation worker replays committed alert intents in sequence order. Each `ReconciliationSlaAlertSink` implementation must:

- expose a stable `sinkId()`;
- persist and return its per-organization/environment `lastAcceptedSequence`;
- make `accept(transition, expectedLastAcceptedSequence)` idempotent on organization, environment, audit sequence, and record hash;
- atomically compare-and-set its cursor, persist the accepted event, and advance the cursor; concurrent Console workers must not be able to skip or reorder events;
- own its durable provider delivery lifecycle, retry/backoff, cooldown, and terminal failure state;
- expose aggregate delivery status for the requested organization/environment scope.

The core invokes providers outside a database transaction. A failed acceptance does not advance the provider cursor, so the next worker pass retries the same audit record. Provider acceptance is deliberately separate from final delivery: the provider's own durable store must track pending, delivered, and failed outcomes. The core does not claim that an accepted event has reached an external destination.

Operators can inspect provider status at:

`GET /api/v1/console/deployments/reconciliation/sla/delivery`

The endpoint requires read authorization and passes only the authenticated organization/environment scope to each provider. It returns aggregate counts and bounded failure metadata, never destinations, credentials, or secret material. A provider status exception is represented as `UNAVAILABLE` and does not affect reconciliation.

No new database table, command queue, or generic outbox is introduced. Runtime execution and reconciliation do not wait for external notification delivery.


## M1.10.15 — secure webhook extension boundary

The webhook extension primitives provide a vendor-neutral request contract without placing destinations or credentials in operator API payloads:

- `ReconciliationSlaWebhookSecretResolver` resolves an opaque secret reference through an external secret manager. Implementations must return a caller-owned character array and never log secret values.
- `ReconciliationSlaWebhookTransport` is the only network boundary. A production implementation must enforce TLS hostname/certificate verification, disable redirects, apply destination-IP egress checks after DNS resolution (including loopback, private, link-local, and DNS-rebinding defenses), bound response consumption, and honor the request timeout. A plain unrestricted HTTP client is not compliant.
- `ReconciliationSlaWebhookSupport` accepts HTTPS only, rejects user-info, query strings, fragments, non-standard ports, IP literals, and local hostnames, and requires an exact DNS-host allowlist match.
- `ReconciliationSlaWebhookClient` serializes the persisted audit transition, signs the exact request bytes using HMAC-SHA256 over `timestamp + "." + body`, and supplies a stable `Idempotency-Key` derived from organization, environment, audit sequence, and record hash. The signing header is `X-Wayang-Webhook-Signature: v1=<hex>`; timestamp is supplied separately in `X-Wayang-Webhook-Timestamp`.
- HTTP response classification is exposed as 2xx success and retryable 408/425/429/5xx. The durable sink/provider still owns scheduling, retry/backoff, cooldown, and terminal failure state.

Configuration:

- `wayang.console.reconciliation.sla.webhook.allowed-hosts` / `WAYANG_CONSOLE_SLA_WEBHOOK_ALLOWED_HOSTS`: exact comma-separated DNS host allowlist; empty by default.
- `wayang.console.reconciliation.sla.webhook.timeout-seconds` / `WAYANG_CONSOLE_SLA_WEBHOOK_TIMEOUT_SECONDS`: request timeout, 1–30 seconds; defaults to 5.

**M1.10.15 boundary (superseded by M1.10.16):** this milestone intentionally introduced only the extension SPIs and did not ship a default unrestricted HTTP client or plaintext secret storage. The concrete secret resolver, restricted HTTPS transport, and provider-owned durable lifecycle are implemented in M1.10.16 below.


## M1.10.16 — durable HTTPS webhook provider

The built-in `wayang-https-webhook` sink now implements the M1.10.14 durable acceptance contract. The audit chain remains the replay source; the provider owns its separate delivery lifecycle in `wc_reconciliation_webhook_cursor` and `wc_reconciliation_webhook_delivery`.

- Acceptance stores the audit-backed transition and advances the organization/environment cursor in one transaction. A unique scope/sequence constraint and pessimistic cursor lock protect concurrent workers; duplicate acceptance of the same audit hash is idempotent.
- Delivery claims use a short database lease. Network I/O occurs after the claim transaction commits, and completion is guarded by the claim attempt and lease so stale workers cannot overwrite a newer attempt.
- The provider retries transport failures and HTTP 408/425/429/5xx with exponential backoff (30 seconds up to one hour), up to 10 attempts. Other HTTP statuses become terminal failures. A worker crash leaves a lease that can be reclaimed after expiry.
- Delivery status aggregates accepted, pending, delivered, and failed counts within the authenticated organization/environment scope. Failure codes are bounded identifiers; endpoint URLs and secret references are not returned.
- `MicroProfileConfigWebhookSecretResolver` resolves either an opaque MicroProfile Config property name or an `env:VARIABLE_NAME` reference. Production should source these values from an external ConfigSource or orchestrator-injected secret, not source control.
- `RestrictedHttpsWebhookTransport` resolves DNS once per attempt, rejects loopback/private/link-local/reserved destinations, connects to the selected resolved address (avoiding a second DNS lookup), preserves TLS SNI and hostname verification, disables redirects, enforces request timeouts, and bounds response headers. It supports HTTPS on port 443 only.

### Per-tenant destination configuration

Each organization/environment pair has its own destination:

- `wayang.console.reconciliation.sla.webhook.destinations.<organization-uuid>.<environment-uuid>.endpoint`
- `wayang.console.reconciliation.sla.webhook.destinations.<organization-uuid>.<environment-uuid>.secret-reference`

The endpoint host must also appear exactly in `wayang.console.reconciliation.sla.webhook.allowed-hosts`. The secret reference may be `env:WAYANG_SECRET_<NAME>` or the name of a property supplied by a trusted external MicroProfile ConfigSource. The resolved secret must contain at least 32 characters. The timeout remains configurable from 1 to 30 seconds.

Do not place secret values in `application.properties`, source control, operator API requests, destination URLs, or logs. Inject them from an orchestrator secret or a dedicated secret-manager ConfigSource and rotate them using that system's controls. Destination configuration is organization/environment scoped; no destination is shared implicitly between tenants.

The provider delivery tables are extension-owned persistence, not a second Console outbox: they exist only to satisfy this sink's durable acceptance, retry, lease, and status contract. Runtime execution and reconciliation remain independent of webhook delivery.


## M1.10.17 — webhook operator recovery and delivery history

The built-in HTTPS SLA webhook provider now exposes a paginated, authenticated operator view of delivery records:

`GET /api/v1/console/deployments/reconciliation/sla/webhook/deliveries?page=0&size=25`

The response is scoped to the authenticated operator's organization and visible environments. It includes the original audit sequence, deployment, transition, delivery state, attempt count, timestamps, and bounded failure code. It does not expose destination URLs, secret references, secret material, or raw transport exception messages. Page size is capped at 100.

A terminally failed event can be retried by an administrator:

`POST /api/v1/console/deployments/reconciliation/sla/webhook/deliveries/{deliveryId}/retry`

The request requires a reason (1–500 characters). The Console locks and scopes the delivery row, accepts only `FAILED`, and appends `RECONCILIATION_WEBHOOK_RETRY_REQUESTED` to the existing tamper-evident audit chain in the same transaction. It returns the same event to `PENDING`, clears the prior lease/failure state, and resets the attempt counter so the existing worker can retry immediately. The original audit sequence, record hash, delivery ID, and provider acceptance cursor are unchanged; the operation does not manufacture a new alert event or bypass the existing worker/transport policy. Non-terminal events return HTTP 409, and out-of-scope or unknown event IDs return HTTP 404.
