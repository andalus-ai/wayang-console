# Runtime deployment observation and reconciliation (M1.10)

Runtime agents report observed deployment state to the Console; the Console does not execute or terminate workloads.

## Runtime report

`POST /api/v1/runtime/deployments/{deploymentId}/observed-state`

Authenticate with the runtime registration bearer credential. The deployment must belong to the authenticated runtime and its organization/environment scope.

```json
{
  "state": "RUNNING",
  "artifactDigest": "sha256:<64-hex-digest>",
  "commandId": "00000000-0000-0000-0000-000000000000",
  "sequence": 3,
  "observedAt": "2026-11-08T10:00:00Z",
  "diagnostic": null
}
```

Supported states: `PENDING`, `APPLYING`, `RUNNING`, `FAILED`, `ABSENT`, `ROLLING_BACK`. Sequence numbers are monotonic per deployment; duplicate or older observations receive HTTP 409. Runtime observation time and Console receipt time are stored separately.

## Desired state and operator view

- `PUT /api/v1/console/deployments/{deploymentId}/desired-state` with `{"desiredState":"PRESENT"}` (also `ABSENT` or `ROLLBACK`).
- `GET /api/v1/console/deployments/{deploymentId}/reconciliation` returns desired/observed state, generation, artifact digest, command ID, sequence, timestamps, and reconciliation state.

Operator routes use the existing OIDC organization/environment scope. Runtime identity is derived only from the authenticated bearer credential, never from request fields.

## Initial evaluation policy

- PRESENT + RUNNING + matching artifact digest => IN_SYNC.
- ABSENT + ABSENT => IN_SYNC.
- Unknown/in-progress observations => WAITING_RUNTIME.
- Failed, missing, or digest-mismatched observations => DRIFTED.

This slice records and evaluates state only. It does not automatically dispatch corrective commands, stop workloads, or claim exactly-once delivery. Runtime reports are authenticated self-reports, not independent attestation.
