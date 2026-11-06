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
- JWT-authenticated operator APIs for scoped fleet listing, runtime detail, enrollment-token issuance/revocation, runtime credential revocation, and audit history.
- Environment and organization boundaries resolved from verified JWT claims.
- Management commands remain disabled; the Console does not remotely stop runtime workloads.

Operator identity and claim requirements are documented in [Operator authentication and fleet API](docs/security/operator-authentication-and-fleet-api.md).

## Status

Early implementation. The APIs are not yet a production-ready control plane: configure JWT verification, TLS, gateway rate limiting, and database-backed integration tests before production use. Deployment management, digital twin reconciliation, and remote management commands remain later milestones.

## Development

Backend requirements and commands are documented in [backend/README.md](backend/README.md). The frontend setup is documented in [console-web/README.md](console-web/README.md).
