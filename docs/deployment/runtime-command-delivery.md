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
- The same acknowledgement endpoint accepts `SUCCEEDED` or `FAILED` to complete the command.

A command is scoped to the runtime that enrolled with the Console. A runtime cannot poll or acknowledge another runtime's command. Error details are bounded; do not include credentials or secrets.

## Delivery guarantees

- At-least-once delivery; runtimes must deduplicate by `commandId` or `idempotencyKey`.
- Dispatch is idempotent per deployment using a stable key.
- Poll leases expire after 60 seconds; a RUNNING command becomes eligible for redelivery after a 2-minute execution lease expires.
- Commands expire after 30 minutes and stop being delivered; a scheduled sweeper marks overdue commands and associated in-flight deployments failed even if the runtime never polls again.
- Delivery is capped at 12 attempts; exhausted or expired commands fail the associated deployment.
- A successful runtime acknowledgement is the only path that marks a deployment DEPLOYED. Operator transition APIs cannot set DEPLOYED directly.

## Current boundary

The command carries deployment identity, workflow/version, and artifact/manifest digests. This milestone establishes durable delivery and acknowledgement, not artifact hosting, artifact transfer, runtime-side signature verification, or automatic compatibility evaluation. Those remain follow-up work. The runtime must verify artifact and manifest digests against its trusted package source before reporting success.
