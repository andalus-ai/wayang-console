# Plugin catalog contract

## Purpose

The plugin catalog is the Console's **portable metadata view** of publishable Wayang plugins. It is intentionally separate from four other concerns:

| Concern | Meaning | Authority |
|---|---|---|
| Plugin catalog | What a package declares it provides | Publisher/package manifest, validated before publication |
| Runtime inventory | What a particular runtime currently reports as installed/available | Runtime instance |
| Artifact registry | Which immutable bytes are stored, their digest and signature evidence | Console supply-chain service |
| Deployment lifecycle | Which package revision is desired on which target and rollout state | Console deployment service |

A catalog entry is not proof that an artifact exists, is trusted, is approved, is compatible with a target runtime, or is installed.

## Contract source

The initial protobuf contract is:

`contracts/proto/wayang/management/v1/plugin_catalog.proto`

It is a versioned, non-executable projection. It carries plugin identity/version, plugin API version, publisher, runtime compatibility, declared components, dependencies, permissions, and structured projection diagnostics.

The initial `catalog_schema_version` is `1.0`. Consumers must reject unsupported major versions and may ignore unknown annotations. Existing protobuf field numbers are immutable; incompatible wire changes require a new major contract.

## Ownership and trust

- Publisher/package manifest is the source of declared metadata.
- Wayang validates manifest structure, component ownership, dependency constraints and execution declarations before producing a projection.
- Core metadata projection is read-only and may report lossy mapping diagnostics.
- Console stores and serves metadata; it does not load classes, instantiate extensions, or execute plugin code.
- Console artifact intake independently verifies artifact digest and configured signature evidence. The catalog's `manifest_digest` may correlate the manifest to the artifact record; artifact digest, signature, signing-key identity, storage location and upload state remain in the artifact registry.
- Runtime independently verifies package bytes and manifest before applying a deployment.

## Governance lifecycle is separate from publisher metadata

Approval, deprecation, revocation, rollout, deployment, installation and health are mutable Console/runtime state. They are not written into the immutable publisher metadata projection. Console stores governance in separate current-state and append-only event tables keyed to the immutable catalog entry.

Likewise, per-runtime installed plugin versions belong to runtime inventory, not this catalog. A runtime inventory report must never overwrite publisher metadata.

## Projection rules

1. Preserve the original plugin ID, version and API version.
2. Preserve component IDs and versions exactly; do not infer missing components.
3. Preserve dependency constraints and optionality.
4. Preserve execution modes only when explicitly declared. Isolation level alone is not evidence of remote/cloud execution support.
5. Carry config schema and UI hints as JSON strings in this first wire contract; validate their JSON and size at the boundary.
6. Return diagnostics for unsupported/lossy fields rather than silently dropping them.
7. Do not fabricate an artifact digest, signature, approval state or runtime health.
8. Do not deserialize extension annotations into executable types.

## Initial API boundary

The contract, immutable metadata persistence, and initial operator-scoped catalog API are implemented in the catalog backend milestone. The backend keeps metadata separate from `RuntimeInventoryService` and `ArtifactRegistryService`.

Implemented initial resource shape:

- `PUT /api/v1/console/plugin-catalog/{pluginId}/versions/{version}` — publish/update validated metadata for an immutable version; repeated identical content is idempotent, conflicting content requires a new version.
- `GET /api/v1/console/plugin-catalog` — list catalog entries in the caller's authorized scope.
- `GET /api/v1/console/plugin-catalog/{pluginId}/versions/{version}` — fetch one immutable entry.
- `GET /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/governance` — current lifecycle status.
- `POST /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/governance` — transition with an explicit action and reason.
- `GET /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/governance/history` — append-only transition history.

Governance actions are `DEPRECATE`, `REACTIVATE` and `REVOKE`. The allowed transitions are ACTIVE → DEPRECATED → ACTIVE, and ACTIVE or DEPRECATED → REVOKED. REVOKED is terminal. Every transition requires a reason, records the actor and timestamp, writes an append-only event, and appends to the Console audit chain. State changes are serialized on the catalog entry to avoid concurrent first-transition races. Governance does not mutate publisher metadata, install or uninstall a plugin, or dispatch runtime commands. Artifact registration remains a separate supply-chain fact.
