# Plugin distribution approval

Wayang Console owns central approval and distribution eligibility. It never loads or executes plugin code; the Wayang runtime remains responsible for local installation and execution.

## Eligibility model

A catalog version is distributable only when all of the following hold:

1. The catalog entry exists in the requesting organization and environment.
2. At least one artifact is registered in the same scope with the exact catalog `manifest_digest`.
3. The artifact signature is valid at the time of approval, using the currently active managed Ed25519 publisher key or the configured legacy trust key when no managed key has ever been registered in that scope.
4. The catalog governance lifecycle is `ACTIVE` (or has no explicit governance row).
5. The approval state is `APPROVED`.

A key revoked after artifact registration invalidates the artifact for new approvals. Existing approvals are not automatically rewritten; distribution eligibility must be re-evaluated at the distribution boundary. The artifact registry's verification method is therefore called again when an approval is requested and when it is granted.

## Approval lifecycle

```text
UNREQUESTED -> PENDING -> APPROVED
                       -> REJECTED
```

A decision is final for an immutable catalog version. To resubmit after rejection, publish a new catalog version. The requester and publisher cannot approve or reject their own submission. Every transition is recorded in the approval event table and the hash-chained audit log.

## API

All routes require an authenticated Console operator JWT and an authorized organization/environment scope.

- `POST /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/approval/request?environmentId=...`
- `GET /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/approval?environmentId=...`
- `GET /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/approval/history?environmentId=...`
- `POST /api/v1/console/plugin-catalog/{pluginId}/versions/{version}/approval/decision?environmentId=...`

Decision body:

```json
{ "action": "APPROVE", "reason": "Security and compatibility review completed" }
```

Roles:

- `console:plugin-approval:read` — read current approval and history.
- `console:plugin-approval:admin` — make approval decisions; also grants read access.
- `console:plugin-catalog:admin` — submit a catalog version for approval.

## Operational notes

- Approval is scoped to one catalog version, organization and environment.
- Manifest matching is exact; artifact digest and manifest digest are distinct values.
- Signature verification is repeated at approval time so a revoked managed signing key cannot be used for a new approval.
- Runtime downloads are gated through both the plugin-specific route and the generic artifact route whenever an artifact manifest is referenced by a catalog entry. Current governance, approval state, and publisher signature trust are rechecked at download time.
- Database migration `014-plugin-distribution-approval` creates the current state and append-only event tables.
