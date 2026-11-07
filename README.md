# Wayang Console

Wayang Console is the standalone management and control-plane product for the Wayang automation platform.

It manages independently deployed Wayang runtimes across development, staging, production, cloud, Kubernetes, and edge environments. It does not execute workflows and does not require the full Wayang Compose or runtime libraries.

## Product boundaries

- **Wayang Studio** authors, validates, tests, and packages workflow definitions.
- **Wayang Runtime** executes a deployment package using only its selected runtime components.
- **Wayang Console** registers, discovers, deploys, observes, governs, and manages runtime instances.
- **Numou** may provide optional shared platform capabilities through explicit adapters. Wayang Console remains independently deployable without Numou.

## Architecture principles

1. Standalone deployment: no mandatory Numou, Kafka, Kubernetes, or external identity dependency.
2. Control plane is not the execution plane: runtime execution continues during Console outages.
3. Contract-first integration: runtime communication uses versioned HTTP/gRPC contracts.
4. Desired and observed state are distinct; reconciliation is explicit and auditable.
5. Runtime identity is independent of network address and deployment location.
6. Management commands are authenticated, authorized, idempotent, expiring, and auditable.
7. Workflow definitions are inspected as metadata/artifacts; Console does not execute them.
8. Community deployments retain a complete basic registration and management path.

## Implemented backend slices

- Quarkus backend foundation and PostgreSQL/Liquibase persistence.
- One-time runtime enrollment with SHA-256 credential digests and one-time plaintext delivery.
- Runtime heartbeat leases and bounded capability/workflow inventory reporting.
- Scoped fleet APIs for runtime listing/detail, enrollment-token issuance/revocation, runtime credential revocation, and audit history.
- Operator identity port with an OIDC/JWKS-backed Quarkus adapter; organization and environment scope come from verified access-token claims.
- Optional Numou integration boundary documented in [ADR-009](docs/architecture/ADR-009-identity-provider-boundary.md); no Numou runtime dependency.
- Management commands remain disabled; the Console does not remotely stop runtime workloads.

See [operator authentication and fleet API](docs/security/operator-authentication-and-fleet-api.md) for issuer, audience, claim, and role configuration.

## Status

Early implementation. Production use still requires configured OIDC/TLS, gateway rate limiting, and successful database-backed CI. Deployment management, digital twin reconciliation, tamper-evident audit, and remote management commands remain later milestones.

## Development

Backend requirements and commands are documented in [backend/README.md](backend/README.md). The frontend setup is documented in [console-web/README.md](console-web/README.md).


### M1.5 security hardening

- Runtime administrators can rotate a runtime registration credential; the old credential is invalidated atomically and plaintext is returned only once.
- Audit records are hash-chained per organization/environment and can be checked with the scoped audit verification endpoint.
- Legacy audit history is retained as a pre-chain baseline; verification covers records appended under the versioned chain format.
- Hash chaining does not replace external signed checkpoints or immutable audit export for protection against privileged database rewrites.


### M1.6 deployment lifecycle

- Console now tracks scoped deployment records, artifact/manifest digests, and guarded lifecycle transitions.
- Deployment lifecycle APIs are management metadata only; runtime command delivery, artifact signature verification, and desired/observed reconciliation remain subsequent work.
- See [deployment lifecycle](docs/deployment/deployment-lifecycle.md).
