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


## M1.10.8 — fleet reconciliation summary

The read-only fleet summary is available at `GET /api/v1/console/deployments/reconciliation/summary`. It returns counts for `IN_SYNC`, `DRIFTED`, `WAITING_RUNTIME`, `RETRYING`, and `MANUAL_INTERVENTION`, plus any other states, due retry count, unresolved command supersession count, and the oldest unresolved deployment with its age. Counts are computed as scoped database aggregates; only the oldest unresolved row is hydrated for the age indicator.

The endpoint derives organization and environment scope from the authenticated operator and accepts no tenant identifiers from the caller. An empty environment scope returns an all-zero summary. The endpoint is read-only: it does not dispatch commands, mutate reconciliation state, or infer that a runtime has stopped. The existing paginated reconciliation queue remains the drill-down surface for individual deployments.
