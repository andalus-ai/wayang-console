# Shared Wayang Core integration

## Status

Architecture and migration guidance. The Console currently owns its local management protocol source and implementation. The shared Core repository has been bootstrapped, but its protocol/model artifacts are not yet published or consumed here.

## Ownership

| Concern | Owner |
|---|---|
| Shared component/plugin descriptors, manifest schemas, capability vocabulary, versioning and protocol definitions | [Wayang Core](https://github.com/andalus-ai/wayang-core) |
| Workflow definitions, BPMN/DMN/EIP/agent semantics, compilation, runtime execution and local plugin lifecycle | [Wayang](https://github.com/andalus-ai/wayang) |
| Runtime enrollment, fleet registry, central plugin catalog/approval/distribution, deployment orchestration, audit, governance and Console UI | This repository |

Core is a library/contract source, not a service. Console must not depend on Wayang execution libraries. Wayang must not depend on Console. Both consume compatible Core artifacts.

## What Console should consume from Core

- Stable component and plugin identifiers, versions and descriptors.
- Plugin manifest schema and contribution inventory.
- Component capability and compatibility vocabulary.
- Canonical runtime management protocol messages.
- Worker protocol metadata only where Console needs to display or govern worker capability.
- Shared error codes and contract fixtures.

## What remains local to Console

- RuntimeInstance, EnrollmentToken, Deployment and Audit persistence entities.
- Repositories, application services, REST resources and OIDC/operator security principal.
- Organization/environment authorization enforcement.
- Plugin approval workflow, distribution policy, rollout state, deployment orchestration and audit.
- Digital twin projections, telemetry storage/query and Console UI.

A shared descriptor does not imply shared persistence entities. Console maps Core contracts into its own domain model and database representation.

## Current protocol baseline

The current management protocol source is at contracts/proto/wayang/management/v1/management.proto. It defines runtime registration, heartbeat, inventory reporting, command polling and acknowledgements.

This source remains the compatibility baseline until a deliberate move to Core. Do not create a second independently maintained protobuf definition. The planned migration is to make Core the canonical source and generate or consume the Console server/client types from a released, versioned contract.

During migration:
1. Add golden wire fixtures and tests for current protobuf messages.
2. Move the source schema to Core without changing field numbers or wire meanings.
3. Publish the management contract version.
4. Update Console generation/dependencies and verify existing runtime interoperability.
5. Remove the local source only after consumer tests prove compatibility.

## Plugin governance boundary

Console is responsible for central registry metadata, publisher review, policy approval, distribution, rollout and revocation. Wayang runtime is responsible for local manifest validation, artifact verification, dependency resolution, activation, execution and draining.

A plugin being listed in Console does not mean it is approved, compatible, installed or active on a runtime. Track these states separately. Console must never execute plugin code or use a generic remote shell command to install arbitrary artifacts.

## Migration order

1. Keep Console's current local DTOs and protobuf behavior stable.
2. Extract shared component/plugin model and schemas into Core.
3. Migrate Wayang to Core first, retaining compatibility adapters.
4. Move the canonical management protocol source to Core.
5. Migrate Console to released Core model/protocol artifacts.
6. Add cross-repository compatibility tests and a release matrix.
7. Keep Console persistence and management application services local.

See [Wayang Core architecture](https://github.com/andalus-ai/wayang-core/blob/main/docs/ARCHITECTURE.md), [module strategy](https://github.com/andalus-ai/wayang-core/blob/main/docs/MODULE-STRATEGY.md), and [roadmap](https://github.com/andalus-ai/wayang-core/blob/main/docs/ROADMAP.md).


## M7 contract baseline work

The current Console-side field inventory and consumer-boundary findings are recorded in [management contract baseline](management-contract-baseline.md). Synthetic protobuf TextFormat examples for all five management RPC request/response pairs are under contracts/fixtures/management/v1.

These are characterization inputs only. They are not binary serialization goldens and do not satisfy the M7 wire-compatibility acceptance gate until generated-message serialization, unknown-field tests, and actual runtime/Console adapter interoperability tests execute in CI.
