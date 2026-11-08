# Repository Agent Instructions

These instructions apply to the entire andalus-ai/wayang-console repository.

## Product and ownership boundaries
- Wayang Console is a standalone management/control-plane product.
- Wayang Core owns shared component/plugin models, portable schemas, and versioned management/worker contracts.
- Wayang owns workflow authoring, BPMN/DMN/EIP/agent semantics, compilation, execution, local plugin loading and runtime Management Agent implementation.
- Console owns fleet registry, enrollment, deployment orchestration, central plugin approval/distribution, audit, governance, digital-twin projections and Console UI.
- Console must not become a workflow engine and must not include Wayang workflow execution libraries.
- Numou is optional. Console must remain independently deployable without Numou.

## Shared Core rules
- Before defining a cross-repository DTO, enum, component/plugin descriptor, manifest, capability vocabulary, or protocol message, check https://github.com/andalus-ai/wayang-core.
- Core is the shared contract source; Console implements its own persistence, repositories, application services and API adapters.
- Console may depend on released compatible Core artifacts; Core must never depend on Console.
- Do not copy Wayang runtime implementation classes into Console or move Console JPA entities, repositories, REST resources, OIDC principals or application services into Core.
- Existing management protobuf under contracts/proto is the current compatibility baseline until its canonical source is migrated to Core. Do not independently change field numbers or wire semantics.
- Keep Console-specific operator/admin APIs local unless they are explicitly cross-process contracts.
- Do not add a dependency on Wayang's workflow/runtime modules.

## Architecture and security
- Runtime execution must continue during Console outages.
- Desired and observed state are distinct; reconciliation is explicit and auditable.
- Runtime identity is independent of address. Authenticated enrollment determines organization/environment scope.
- Commands are typed, authenticated, authorized, idempotent, expiring and auditable.
- Never expose generic shell or arbitrary code execution commands.
- Plugin manifests and runtime-reported inventory are untrusted input; validate and bound all payloads.
- Plugin permission declarations are not authorization grants.
- Console approves/distributes plugins but does not load or execute plugin code.
- Keep identity-provider integrations behind ports; Numou remains optional.

## Implementation workflow
1. Inspect existing modules, tests, POMs, migrations and docs before editing.
2. Preserve current API and protobuf compatibility unless a migration is explicitly planned.
3. Add tests for authorization scope, replay/idempotency, lease expiry, compatibility and failure paths.
4. Keep persistence and Quarkus concerns in adapters/bootstrap layers.
5. Update docs and roadmap status only when backed by implementation and tests.
6. Record exact verification commands and outcomes; do not claim unrun builds or production verification.

## Java conventions
- Follow the JDK baseline and style of the affected module.
- Prefer immutable records/value types where appropriate; avoid Lombok unless already used.
- Keep domain/application layers independent of Quarkus, HTTP, persistence and external identity APIs.
- Never log bearer tokens, enrollment credentials, secret values or sensitive workflow payloads.
