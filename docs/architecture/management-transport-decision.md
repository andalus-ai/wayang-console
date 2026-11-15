# Management Transport Decision

- Status: Accepted for the current M7 migration sequence
- Decision date: 2026-11-15
- Scope: Wayang Console runtime management (enrollment, heartbeat, inventory, command delivery and acknowledgement)
- Supersedes: No prior transport decision
- Revisit when: A real Wayang runtime management client and Console server adapter are proposed for gRPC, or a shared Core management contract is ready for release

## Decision

**Keep the existing REST/JSON API as the canonical, implemented management transport for this migration.**

The protobuf schema under `contracts/proto/wayang/management/v1/management.proto` remains a separately versioned protocol design/compatibility artifact. It is not currently the wire contract used by the Console runtime-management endpoints, and it must not be presented as interoperable with them.

Do not generate or publish a shared Core management-contract artifact, remove the Console-owned schema, or claim runtime/Console interoperability until the semantic and authentication gaps below are resolved and an actual runtime client/server adapter passes cross-repository integration tests.

This is a sequencing decision, not a permanent rejection of gRPC. A future gRPC transport may be added behind the same application use cases after its contract is reconciled. Do not implement a partial adapter for only polling or acknowledgements.

## Evidence in the current implementation

The Console exposes these JSON endpoints:

| Operation | Implemented endpoint | Authentication | Current behavior |
|---|---|---|---|
| Enroll | `POST /api/v1/runtime/enroll` | Enrollment token in JSON body | Consumes a one-time, environment-scoped enrollment token; returns registration ID, bearer registration token, registration expiry, lease expiry and heartbeat interval |
| Heartbeat | `POST /api/v1/runtime/heartbeat` | `Authorization: Bearer` registration token | Renews the runtime lease and records normalized health |
| Inventory | `POST /api/v1/runtime/inventory` | Bearer registration token | Replaces the full capability/workflow inventory, creates a revision and renews the lease |
| Poll | `POST /api/v1/runtime/commands/poll` | Bearer registration token | Leases up to the requested limit, clamped to 1–50; increments delivery attempts and returns command metadata/payload |
| Acknowledge | `POST /api/v1/runtime/commands/{commandId}/ack` | Bearer registration token | Accepts STARTED, SUCCEEDED, FAILED or SUPERSEDED with command-specific state checks; successful deployment completion requires a matching verified artifact digest |

Relevant implementation:
- `backend/src/main/java/ai/wayang/console/api/RuntimeRegistryResource.java`
- `backend/src/main/java/ai/wayang/console/api/RuntimeCommandResource.java`
- `backend/src/main/java/ai/wayang/console/application/RuntimeRegistryService.java`
- `backend/src/main/java/ai/wayang/console/application/RuntimeInventoryService.java`
- `backend/src/main/java/ai/wayang/console/application/RuntimeCommandService.java`

The registration bearer token is generated only after the enrollment credential has been consumed. The Console persists only its SHA-256 digest. Organization and environment scope are derived from the enrollment record and authenticated runtime, not trusted from caller-supplied tenant identifiers.

## Contract reconciliation

| Concern | Implemented REST/JSON | Current protobuf schema | Required resolution before unification |
|---|---|---|---|
| Enrollment proof | `EnrollRequest.enrollmentToken` in body | `RegisterRuntimeRequest` has no enrollment credential | Define how a one-time enrollment credential is conveyed securely |
| Runtime identity | Instance ID, runtime version and deployment ID; environment is derived from the enrollment token | `RuntimeIdentity` also carries environment ID | Specify whether environment is asserted, derived, or checked against the enrollment grant |
| Registration response | Returns registration bearer token and registration expiry as well as lease details | Returns registration ID and lease details, but no bearer token or registration expiry | Define credential delivery/rotation and expiry behavior; registration ID is not a substitute for a secret |
| Subsequent authentication | Bearer token in HTTP Authorization header | Instance ID and registration ID in each message | Choose a transport-neutral credential mechanism; do not treat public identifiers as authentication |
| Protocol negotiation | No negotiation field in the current REST response | Supported versions in request and negotiated version in response | Define supported-version ranges and downgrade behavior before claiming negotiation |
| Heartbeat | Health in body; server timestamps receipt and renews lease | Health and client `observed_at`; instance and registration IDs | Define timestamp trust/skew policy and whether client observation time is informational |
| Inventory | Full replacement of capabilities and workflows; response includes revision, counts and report time | Same basic collections; response only has accepted revision | Define replace-vs-patch semantics, empty-list meaning, revision idempotency and limits |
| Poll | Optional limit, default 10, clamped to 1–50; response includes attempt count | `max_commands`; response command has no attempt count | Standardize default/maximum and delivery-attempt visibility |
| Payload encoding | `typedPayload` is a JSON string in the command view | `typed_payload` is protobuf `bytes` | Define canonical payload media type, encoding, schema/version and size limit; never silently reinterpret arbitrary bytes as UTF-8 |
| Acknowledgement | Status, message and verified artifact digest; returns accepted, resulting status and completion data | Status and message only; response has accepted boolean | Add verified-artifact evidence and define acknowledgement result/status semantics |
| Error model | HTTP status plus stable JSON error code/message | No common error envelope defined | Define transport-neutral error codes and retryability mapping |
| Tenant boundary | Organization/environment are bound by enrollment and registration credential | IDs occur in some messages but no tenant scope is carried | Preserve server-side credential binding; never authorize solely from request identity fields |

The REST command payload is currently represented as a JSON string and includes deployment-specific data. The protobuf field is `bytes`. Their apparent names do not establish equivalent encoding or semantics.

## Canonical contract boundaries

1. **Application semantics:** command lifecycle, lease rules, inventory replacement, enrollment consumption, tenant/environment authorization and audit behavior remain in Console application services.
2. **REST adapter:** remains the production adapter and canonical implemented transport while it is the only end-to-end management path.
3. **Protobuf artifact:** remains an independently tested schema compatibility artifact. The ten immutable binary goldens verify schema serialization, not endpoint interoperability.
4. **Future Core ownership:** Core may own transport-neutral value types and a released management contract only after both runtime and Console consume the same versioned source. Console database entities, tenant-scoped persistence, REST resources and orchestration policy remain local.
5. **Runtime ownership:** Wayang owns its management client adapter and runtime-side execution behavior. It must not depend on Console implementation classes.

## Versioning and compatibility policy

- Treat the current REST API as version `v1`; additive optional JSON fields may be introduced compatibly, but changing credential meaning, required fields, command payload encoding or lifecycle semantics requires a versioned migration.
- Treat protobuf package `wayang.management.v1` as its own schema version. Field numbers and established wire meanings are immutable; removed fields must be reserved, never reused.
- Unknown additive protobuf fields must remain safely forwardable, as verified by the compatibility test.
- A shared artifact version must pin the exact schema/runtime compatibility range. Do not infer compatibility from matching package names or copied files.
- Document a rollback path to the last mutually supported contract version before changing either consumer.

## M7 migration gates

Before extracting a canonical management contract to Wayang Core:

1. Approve a field-level mapping for every operation, including credentials, tenant binding, payload encoding and terminal acknowledgement evidence.
2. Decide whether protobuf/gRPC is a supported production transport or remains a schema-only artifact. If gRPC is selected, implement all five operations, not a partial subset.
3. Add a Wayang runtime management client that consumes the selected released contract.
4. Add Console transport adapter integration tests against that real client (or shared conformance harness) for enrollment, heartbeat, inventory, poll, acknowledgement, expired/revoked credentials, retries and unknown additive fields.
5. Verify mixed supported versions and rollback to the previous compatible version.
6. Only then publish the Core artifact, migrate both consumers, and remove the local schema copy.

## Consequences

- The current Console runtime-management behavior remains stable; this ADR does not change API behavior or persistence.
- M7 can proceed with schema characterization and compatibility tooling without falsely declaring cross-process interoperability.
- Protobuf extraction to Core is intentionally blocked until the credential and semantic contract is complete.
- A future gRPC implementation will require explicit design and integration work rather than generated stubs alone.
