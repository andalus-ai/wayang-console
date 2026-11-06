# Wayang Console architecture

## Three planes
- Design: Wayang Studio composes, validates, tests, compiles, and packages workflows.
- Execution: Wayang Runtime executes immutable deployment packages.
- Management: Wayang Console registers, discovers, deploys, observes, and governs runtime fleets.

Console is not a workflow engine and must not load workflow execution libraries.

## Bounded contexts
Registry owns stable runtime identity, enrollment, heartbeat, and reachability. Inventory owns runtime capabilities and deployed workflow references. Environment owns organization/environment grouping. Deployment owns desired revisions and rollout state. Operations owns authenticated commands and acknowledgements. Observability owns telemetry ingestion/query contracts. Digital twin owns derived topology and desired-versus-observed projections. Governance owns authorization, audit, policy, and secret-provider integration.

Start with Registry, Inventory, Environment, and Operations. Deployment orchestration, twin reconciliation, and telemetry aggregation build on these contracts.

## Security and identity
Runtime identity is stable across restarts; registration leases are separate. Never trust organization or environment IDs supplied by an unauthenticated runtime payload. The authenticated enrollment credential determines permitted scope. Store only hashes of one-time enrollment tokens. Commands require target authorization, idempotency keys, expiry, acknowledgement, and audit. Never expose generic shell or arbitrary code execution commands.

## Availability
Runtime is authoritative for in-flight execution. Console outages must not stop local workflow execution. Agents retry registration and heartbeat with backoff and bounded local telemetry buffering. Expired commands are rejected.

## Persistence
Use repository ports in domain/application layers. PostgreSQL is recommended for production. Keep database-specific types out of domain contracts and enforce organization scope in every repository query and unique constraint.

## Numou integration
Numou is optional. Define ports for identity, tenancy resolution, authorization, audit, secret references, and event publishing. Standalone Community deployments use local implementations. Numou adapters implement the same ports without importing Numou domain entities into Console core.

## Compatibility
Management APIs and events carry explicit versions. Runtime capability negotiation includes supported protocol versions. Breaking changes require a new major contract or documented compatibility window.
