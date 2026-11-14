# Runtime management contract baseline

## Scope and status

This document records the Console repository's current runtime-management contract boundary for M7. It is a characterization baseline, not a declaration that a shared artifact has been published or that a native management gRPC transport is deployed.

Canonical source today:
contracts/proto/wayang/management/v1/management.proto

Adjacent metadata schema:
contracts/proto/wayang/management/v1/plugin_catalog.proto

Reviewable examples and executable checks:
contracts/fixtures/management/v1/ and backend/src/test/java/ai/wayang/console/contracts/ManagementContractCompatibilityTest.java

## RPC inventory

| RPC | Request fields | Response fields | Semantic responsibility |
|---|---|---|---|
| Register | identity (instance/runtime/deployment/environment), hostname, platform, supported protocol versions | registration ID, lease expiry, heartbeat interval, negotiated protocol version | Establish runtime identity and protocol negotiation |
| Heartbeat | instance ID, registration ID, health, observed timestamp | lease expiry, heartbeat interval | Refresh liveness lease and report health |
| ReportInventory | instance ID, registration ID, component capabilities, workflow references | accepted revision | Replace/report the runtime's observed inventory snapshot |
| PollCommands | instance ID, registration ID, max command count | commands (ID, idempotency key, type, typed payload bytes, expiry) | Runtime-initiated command delivery |
| AcknowledgeCommand | instance ID, registration ID, command ID, status, message | accepted | Report command lifecycle outcome |

Field numbers and wire types remain defined exclusively by the .proto source. This inventory is explanatory and must not become a second schema.

## Current field-number baseline

### Shared nested messages

| Message | Field number | Field name | Type |
|---|---:|---|---|
| RuntimeIdentity | 1 | instance_id | string |
| RuntimeIdentity | 2 | runtime_version | string |
| RuntimeIdentity | 3 | deployment_id | string |
| RuntimeIdentity | 4 | environment_id | string |
| ComponentCapability | 1 | component_id | string |
| ComponentCapability | 2 | version | string |
| ComponentCapability | 3 | capability | string |
| WorkflowReference | 1 | workflow_id | string |
| WorkflowReference | 2 | workflow_version | string |
| WorkflowReference | 3 | definition_digest | string |
| WorkflowReference | 4 | deployment_id | string |

### RPC messages

| Message | Field number | Field name | Type |
|---|---:|---|---|
| RegisterRuntimeRequest | 1 | identity | RuntimeIdentity |
| RegisterRuntimeRequest | 2 | hostname | string |
| RegisterRuntimeRequest | 3 | platform | string |
| RegisterRuntimeRequest | 4 | supported_protocol_versions | repeated string |
| RegisterRuntimeResponse | 1 | registration_id | string |
| RegisterRuntimeResponse | 2 | lease_expires_at | google.protobuf.Timestamp |
| RegisterRuntimeResponse | 3 | heartbeat_interval_seconds | uint32 |
| RegisterRuntimeResponse | 4 | negotiated_protocol_version | string |
| HeartbeatRequest | 1 | instance_id | string |
| HeartbeatRequest | 2 | registration_id | string |
| HeartbeatRequest | 3 | health | string |
| HeartbeatRequest | 4 | observed_at | google.protobuf.Timestamp |
| HeartbeatResponse | 1 | lease_expires_at | google.protobuf.Timestamp |
| HeartbeatResponse | 2 | heartbeat_interval_seconds | uint32 |
| InventoryReportRequest | 1 | instance_id | string |
| InventoryReportRequest | 2 | registration_id | string |
| InventoryReportRequest | 3 | capabilities | repeated ComponentCapability |
| InventoryReportRequest | 4 | workflows | repeated WorkflowReference |
| InventoryReportResponse | 1 | accepted_revision | string |
| PollCommandsRequest | 1 | instance_id | string |
| PollCommandsRequest | 2 | registration_id | string |
| PollCommandsRequest | 3 | max_commands | uint32 |
| ManagementCommand | 1 | command_id | string |
| ManagementCommand | 2 | idempotency_key | string |
| ManagementCommand | 3 | command_type | string |
| ManagementCommand | 4 | typed_payload | bytes |
| ManagementCommand | 5 | expires_at | google.protobuf.Timestamp |
| PollCommandsResponse | 1 | commands | repeated ManagementCommand |
| AcknowledgeCommandRequest | 1 | instance_id | string |
| AcknowledgeCommandRequest | 2 | registration_id | string |
| AcknowledgeCommandRequest | 3 | command_id | string |
| AcknowledgeCommandRequest | 4 | status | string |
| AcknowledgeCommandRequest | 5 | message | string |
| AcknowledgeCommandResponse | 1 | accepted | bool |

## Observed consumer boundary

- Console application-level integration tests cover enrollment/registry, inventory replacement, command polling and acknowledgement. These exercise Console services and persistence behavior.
- The management .proto files are present under contracts/proto and are compiled into generated Java message classes for contract tests. This does not create a native gRPC server/client.
- The current Console command transport inspected at `backend/src/main/java/ai/wayang/console/api/RuntimeCommandResource.java` is REST/JSON: `POST /api/v1/runtime/commands/poll` and `POST /api/v1/runtime/commands/{commandId}/ack`. The current HTTP resource is not an adapter for the five protobuf RPCs.
- Wayang's `wayang-api-rest` module has Quarkus gRPC enabled and `WayangGrpcService` implements the application API for workflow runs, tasks, messages and run watching. Its RPC surface is not the `RuntimeManagement` service defined here. The Wayang gRPC service therefore must not be treated as a management-protocol consumer.
- The inspected Wayang runtime/API module does not declare a dependency on a shared management-contract artifact. Existing gRPC support is a separate API surface.
- Wayang Core currently contains the `wayang-core-model` module only. There is no management-contract module or released artifact to consume yet.

### Consequence for M7

The present protobuf schema is a target contract, not an already interoperable cross-repository transport. Before extraction, the project must make an explicit choice between (a) implementing the five RPCs as a real runtime-to-Console gRPC protocol or (b) defining a versioned HTTP/JSON management contract and treating protobuf as a future/alternate transport. Do not silently equate REST resources with protobuf RPCs or introduce a partial adapter that only covers poll/ack.

The first implementation should also define authentication and tenant binding, registration credential issuance/rotation, lease semantics, idempotency, command payload versioning, retry/backoff, and protocol negotiation consistently across both sides.

## Ownership constraints

- Core should own only portable management message definitions, generated contract artifact and compatibility policy after extraction.
- Wayang should own the runtime-side management agent and transport adapter.
- Console should own enrollment, authorization, fleet persistence, command lifecycle, audit and server application services.
- Console REST DTOs, JPA entities and repositories remain local unless explicitly promoted to a cross-process contract.
- The plugin catalog protobuf is a metadata projection; it does not authorize plugin execution or distribution.

## Required migration gates

1. Freeze current field numbers and wire types; reserve removed fields and never reuse field numbers.
2. Add immutable historical binary goldens for every request/response fixture using the pinned protoc/runtime toolchain.
3. Keep additive-field compatibility tests that parse new messages with an old descriptor and forward them without losing unknown fields.
4. Specify the management transport and implement/test the actual Wayang runtime management agent and Console adapter end-to-end.
5. Create the Core management-contract module and publish a versioned artifact only after the schema and compatibility tests are authoritative there.
6. Migrate Wayang and Console consumers to the same released artifact/version range.
7. Remove the Console-local schema only after both consumers pass cross-repository interoperability CI.

## Compatibility rules

- Add fields with new numbers; never change an existing field's wire type or meaning.
- Never reuse deleted field numbers or names; reserve them in the schema.
- Keep protocol version negotiation explicit. Do not equate the runtime distribution version with the management protocol version.
- Unknown additive fields must not break older consumers.
- Treat changes to command payload semantics as versioned application-contract changes even though the payload is represented as bytes.
- Keep timestamps in UTC and define lease/expiry semantics independently of wall-clock serialization.

The focused test verifies TextFormat parsing, protobuf binary round-trips, RPC descriptor names, selected field numbers/types, unknown-field preservation, and forwarding from a new descriptor through an older descriptor. It does not yet compare immutable historical binary goldens or exercise actual runtime/Console transport adapters. See contracts/fixtures/management/v1/README.md.
