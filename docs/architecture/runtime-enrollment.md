# Runtime enrollment and credential lifecycle

## Provisioning
An operator-side provisioning workflow creates a one-time enrollment credential scoped to exactly one organization and environment. Only its SHA-256 digest is persisted. The plaintext is displayed once and must be delivered to the runtime through a secure provisioning channel. The current application service exposes token issuance internally; a properly authenticated Console administrator API/CLI is a subsequent task. Do not expose token issuance as an unauthenticated HTTP endpoint.

## Redemption
The runtime submits the enrollment token and its self-description to `POST /api/v1/runtime/enroll`. The service:
1. Hashes the supplied token and locks the matching enrollment row for update.
2. Rejects unknown, consumed, or expired tokens.
3. Takes organization/environment scope from the enrollment row.
4. Consumes the one-time token and creates the runtime identity.
5. Generates a 256-bit random registration credential.
6. Stores only its SHA-256 digest and returns the plaintext credential once.

## Runtime authentication
Heartbeat and inventory endpoints require `Authorization: Bearer <registration-token>`. The token is never accepted in query parameters. TLS is mandatory in production. Registration credentials expire and should be rotated before expiry; revocation/rotation endpoints are planned.

## Lease semantics
The initial lease is two minutes and the suggested heartbeat interval is 30 seconds. Lease expiry indicates loss of contact, not proof that the process is unhealthy. The runtime remains responsible for workflow execution if Console is unreachable.

## Known M1 hardening still required
- Rate limiting and abuse detection at the API gateway.
- Authenticated operator workflow for enrollment token issuance/revocation.
- Registration credential rotation and runtime revocation.
- Tenant-scoped operator APIs for listing and inspecting runtimes.
- Scheduled lease expiry projection and audit events.
- Integration tests with PostgreSQL and concurrent redemption.
