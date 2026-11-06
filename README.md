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

## Initial implementation

This repository starts with the control-plane foundation:

- Management API contract for registration, heartbeat, capabilities, inventory, and commands.
- Domain model for organizations, environments, runtime instances, deployments, and commands.
- Quarkus backend foundation.
- PostgreSQL-ready persistence boundary with a development-friendly local profile.
- Explicit adapter boundaries for identity, tenancy, audit, secrets, and event transport.
- Console frontend boundary, independently deployable from the backend.

See [docs/architecture/README.md](docs/architecture/README.md) and [docs/roadmap/IMPLEMENTATION-ROADMAP.md](docs/roadmap/IMPLEMENTATION-ROADMAP.md).

## Status

Early foundation. APIs and schemas are versioned proposals until implementation and compatibility tests establish them as stable. Do not treat the initial contracts as a production-ready remote command channel.

## Development

Backend requirements and commands are documented in [backend/README.md](backend/README.md). The frontend setup is documented in [console-web/README.md](console-web/README.md).
