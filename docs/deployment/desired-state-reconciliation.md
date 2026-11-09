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
