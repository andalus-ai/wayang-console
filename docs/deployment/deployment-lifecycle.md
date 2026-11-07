# M1.6 — Deployment lifecycle foundation

This milestone adds Console-owned deployment records and guarded lifecycle transitions. It does not execute workflow packages; deployment commands and runtime-side reconciliation are future protocol work.

## API

- `POST /api/v1/console/deployments` — create a scoped deployment record (admin).
- `GET /api/v1/console/deployments?page=0&size=25` — list deployments within the operator's organization/environment scope (read).
- `POST /api/v1/console/deployments/{deploymentId}/transition` — record a validated lifecycle transition (admin).

Create requests require a runtime ID, deployment ID, workflow ID/version, and SHA-256 artifact and manifest digests. Digests use `sha256:<64 lowercase or uppercase hex chars>`. Deployment IDs are unique within organization/environment.

## State machine

```text
REQUESTED -> VALIDATING -> READY -> DEPLOYING -> DEPLOYED
    |           |          |          |
    +-> FAILED  +-> FAILED +-> FAILED +-> FAILED
    +-> CANCELLED       +-> CANCELLED
                           DEPLOYING -> ROLLING_BACK -> ROLLED_BACK
                           DEPLOYED  -> ROLLING_BACK
```

Terminal states are `DEPLOYED`, `FAILED`, `CANCELLED`, and `ROLLED_BACK`. Invalid transitions return HTTP 409. Each accepted transition is written to the scoped audit chain.

## Important boundary

The Console currently records lifecycle state; it does not yet send a signed deployment artifact to a runtime, verify runtime-side artifact signatures, or reconcile desired and observed deployment state. A deployment is not considered operationally deployed merely because an operator transitions the record to `DEPLOYED`. M1.7 should introduce runtime command delivery, idempotency, acknowledgement, and reconciliation before this API is presented as a full deployment orchestrator.

## Security and integrity

- All operations are organization/environment scoped from the verified operator context.
- Artifact and manifest digests are recorded, not artifact bytes.
- Failure details are bounded to 1000 characters; callers must not include secrets.
- Deployment creation and accepted transitions append audit records in the same transaction.
- Runtime remains the execution authority; Console remains management plane.
