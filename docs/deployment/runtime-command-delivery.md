# M1.7 — Runtime command delivery and reconciliation

M1.7 adds a durable, runtime-authenticated command queue for deployment requests. The Console remains a management plane; it never executes workflow code.

## Operator API

- `POST /api/v1/console/deployments/{deploymentId}/dispatch` — queue one idempotent deployment command for a READY deployment (admin).
- `GET /api/v1/console/deployments` — existing scoped inventory API.

Dispatch atomically changes the deployment from READY to DEPLOYING and records an audit event. Repeated dispatch requests return the existing command.

## Runtime API

All endpoints require the runtime registration bearer token:

- `POST /api/v1/runtime/commands/poll` with `{"limit":10}` — leases up to 50 commands.
- `POST /api/v1/runtime/commands/{commandId}/ack` with `{"status":"STARTED","message":"..." }` — acknowledges start and renews the execution lease.
- The same acknowledgement endpoint accepts `SUCCEEDED` or `FAILED` to complete the command. A `SUCCEEDED` request **must** include `verifiedArtifactDigest` in `sha256:<64 lowercase hex>` form.

A command is scoped to the runtime that enrolled with the Console. A runtime cannot poll or acknowledge another runtime's command. Error details are bounded; do not include credentials or secrets.

## Delivery guarantees

- At-least-once delivery; runtimes must deduplicate by `commandId` or `idempotencyKey`.
- Dispatch is idempotent per deployment using a stable key.
- Poll leases expire after 60 seconds; a RUNNING command becomes eligible for redelivery after a 2-minute execution lease expires.
- Commands expire after 30 minutes and stop being delivered; a scheduled sweeper marks overdue commands and associated in-flight deployments failed even if the runtime never polls again.
- Delivery is capped at 12 attempts; exhausted or expired commands fail the associated deployment.
- A successful runtime acknowledgement is the only path that marks a deployment DEPLOYED. Operator transition APIs cannot set DEPLOYED directly.

## Current boundary

The command carries deployment identity, workflow/version, and artifact/manifest digests. This milestone establishes durable delivery and acknowledgement. Artifact hosting, transfer, and runtime-side signature verification are handled by the artifact registry and runtime verification contract; compatibility evaluation is now enforced before dispatch. The runtime must verify artifact and manifest digests against its trusted package source before reporting success.

## Verified deployment completion (M1.9)

A runtime must send the digest of the artifact it actually verified and applied:

```json
{
  "status": "SUCCEEDED",
  "message": "Artifact verified and activated",
  "verifiedArtifactDigest": "sha256:<64-hex-digest>"
}
```

The Console compares this value with the deployment's registered artifact digest before completing the command or changing the deployment to `DEPLOYED`. Missing, malformed, or mismatched evidence is rejected with HTTP 409; the command remains in its leased/running state so the runtime can submit a corrected acknowledgement before expiry. The accepted digest is persisted on the command for audit and reconciliation. Failed and STARTED acknowledgements do not require an artifact digest.

This is evidence supplied by the authenticated runtime, not independent remote attestation. The runtime implementation must still verify the artifact bytes and signed manifest locally before reporting success.


## Authenticated runtime inventory snapshots

POST /api/v1/runtime/inventory replaces the reporting runtime's complete capability and workflow snapshot. The bearer credential determines runtime identity and tenant/environment scope; clients cannot select another runtime id.

- Each collection is limited to 1,000 entries.
- Required identifiers and versions are trimmed and length-checked against persistence limits.
- Duplicate capability tuples and duplicate workflow ids are rejected.
- Workflow definition digests must use sha256:<64 hex chars>.
- Replacement is transactional: invalid input leaves the previous snapshot intact.
- A successful report persists a new inventory revision and report timestamp, refreshes the runtime's two-minute lease, and marks it online.
- The response returns the revision, item counts, and report timestamp. The audit stream records the report.

A normal heartbeat does not refresh inventory freshness. Consumers that need capability-aware scheduling must distinguish a live runtime lease from a recently reported inventory snapshot. Existing runtimes with no snapshot remain discoverable, but their capability inventory is unknown. This change does not yet infer compatibility or automatically approve deployments.


## Runtime compatibility gate (M1.9)

Deployment creation accepts an optional `requiredCapabilities` array. Each entry contains:

- `minimumRuntimeVersion`: optional minimum Wayang runtime version (numeric dotted form)\n- `componentId`: runtime component identifier
- `capability`: capability identifier
- `minimumVersion`: optional numeric dotted version (for example `1.4.0`)

Example:

```json
{
  "runtimeId": "…",
  "deploymentId": "release-2026-11",
  "workflowId": "orders.process",
  "workflowVersion": "2.1.0",
  "artifactDigest": "sha256:<64-hex-digest>",
  "manifestDigest": "sha256:<64-hex-digest>",\n  "minimumRuntimeVersion": "1.4.0",
  "requiredCapabilities": [
    { "componentId": "wayang.runtime", "capability": "workflow.execute", "minimumVersion": "1.4.0" },
    { "componentId": "wayang.connector.postgresql", "capability": "database.postgresql" }
  ]
}
```

The operator can inspect `GET /api/v1/console/deployments/{deploymentId}/compatibility` for a structured evaluation. Dispatch re-evaluates compatibility inside its transaction and returns HTTP 409 when the target is offline, its lease has expired, its inventory is missing or older than five minutes, or a required capability/version is absent.

Capability matching is case-insensitive by component and capability identifier. Version requirements currently use numeric dotted versions (optional leading `v`); non-numeric runtime versions do not satisfy a minimum-version constraint. Deployments with no explicit capability requirements still require a live runtime and fresh inventory. The evaluator is a Console-side policy boundary, not remote attestation: capability declarations remain runtime-reported evidence.
