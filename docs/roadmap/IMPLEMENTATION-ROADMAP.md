# Wayang Console implementation roadmap

## M0 — Foundation
- [x] Establish standalone repository and product boundary.
- [x] Define initial management protocol and domain boundaries.
- [ ] Add persistence adapters and migrations.
- [ ] Add contract tests and local development stack.

## M1 — Runtime registry
- One-time enrollment token issuance and redemption.
- Runtime registration, heartbeat lease, and health reporting.
- Organization/environment-scoped inventory APIs.
- Capability and workflow inventory snapshots.
- Stale/offline detection distinct from unhealthy status.

## M2 — Management operations
- Typed command catalog and lifecycle.
- Idempotency, authorization, expiry, retry, and audit.
- Runtime-initiated outbound connection for edge environments.

## M3 — Console web
- Fleet overview, instance detail, environment filters.
- Capability and deployed workflow inventory.
- Enrollment flow, command status, and audit timeline.

## M4 — Deployment control
- Artifact metadata and digest verification.
- Desired deployment revisions and rollout strategies.
- Capability compatibility preflight.
- Rollback as a new desired revision.

## M5 — Observability and digital twin
- OTLP integration and event ingestion.
- Topology projection and dependency graph.
- Desired/observed comparison and drift explanations.
- Retention and cardinality limits.

## M6 — Integrations and enterprise
- Optional Numou identity, tenancy, security, and audit adapters.
- Optional Iket gateway integration.
- HA, multi-region, delegated administration, and advanced policy.

## M1 acceptance criteria
1. Runtime enrolls using a one-time credential.
2. Credential is never stored or returned in plaintext after issuance.
3. Runtime registers and heartbeats over outbound connection.
4. Runtime reports capabilities and workflow references.
5. Reads and writes are organization/environment scoped.
6. Repeated registration and heartbeat are idempotent.
7. Stale instances are distinct from unhealthy instances.
8. Runtime execution is unaffected by Console outages.


## M7 — Shared contract extraction
- [ ] Add golden fixtures for the current runtime management protobuf and API behavior.
- [ ] Consume the canonical management contract from released wayang-core artifacts after migration.
- [ ] Replace duplicated shared component/plugin metadata models with Core contracts where applicable.
- [ ] Keep Console persistence entities, repositories, application services and operator APIs local.
- [ ] Add cross-repository compatibility CI with Wayang runtime.

See [shared Core integration](../architecture/shared-core-integration.md).

### M7 acceptance criteria

M7 is complete only when all of the following are true:

- The current management protobuf has golden compatibility fixtures covering registration, heartbeat, inventory, command polling and acknowledgements.
- Core publishes a versioned management-contract artifact/schema from a single canonical source; Console does not maintain a parallel copy.
- Wayang Runtime and Console consume the same released contract version (or a documented overlapping compatibility range).
- Existing runtime-to-Console interoperability is verified by contract/integration tests, including unknown additive fields and mixed supported versions.
- Console persistence entities and internal REST/admin DTOs remain local unless they are deliberately designated public cross-process contracts.
- Rollback to the previous compatible contract version is documented and tested.

Do not mark M7 complete when only a protobuf file has been copied or a Maven dependency has been added. The release gate is proven wire compatibility between the actual runtime and Console consumers.
