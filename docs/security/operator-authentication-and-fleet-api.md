# Operator authentication and fleet API

## Trust boundary

Runtime credentials and operator credentials are separate security principals.

- Runtime credentials are high-entropy opaque bearer tokens issued during one-time enrollment. Only SHA-256 digests are stored.
- Operator access tokens are verified by Quarkus OIDC using provider discovery and the provider's JWKS endpoint.
- Operator identity and scope are never accepted from request bodies, query parameters, or headers other than the bearer token.

## OIDC token contract

The Console expects a verified access token to contain:

| Claim | Type | Meaning |
|---|---|---|
| `sub` | string | Stable actor identity, written to audit records |
| `organization_id` | UUID string | The organization boundary |
| `environment_ids` | array of UUID strings | Environments the operator may access |
| `groups`, or Keycloak `realm_access/roles` / `resource_access/{client-id}/roles` | array of strings | Permissions mapped to Quarkus roles |

Quarkus OIDC maps the standard `groups` claim and Keycloak realm/client role claims to `SecurityIdentity` roles. The identity provider must issue the exact Console role names; do not grant broad administrator roles by default.

Supported roles:

- `console:runtime:read`: read runtime fleet, inventory, and audit history
- `console:runtime:admin`: issue/revoke enrollment credentials and revoke runtime registrations; also grants read access

## Production configuration

The production profile validates JWT signatures through OIDC discovery/JWKS, checks the expected issuer and audience, and requires a subject. Configure:

```properties
%prod.quarkus.oidc.application-type=service
%prod.quarkus.oidc.auth-server-url=\${WAYANG_CONSOLE_OIDC_AUTH_SERVER_URL}
%prod.quarkus.oidc.client-id=\${WAYANG_CONSOLE_OIDC_CLIENT_ID:wayang-console}
%prod.quarkus.oidc.token.issuer=\${WAYANG_CONSOLE_OIDC_ISSUER}
%prod.quarkus.oidc.token.audience=\${WAYANG_CONSOLE_OIDC_AUDIENCE:wayang-console}
quarkus.oidc.roles.source=accesstoken
quarkus.oidc.token.subject-required=true
```

The values are supplied through deployment configuration. Use the issuer/realm base URL; Quarkus discovers the OIDC metadata and JWKS URL. Do not configure a static development key in production. Never commit private keys, client secrets, or production issuer values.

In development, Quarkus Dev Services can provide a local OIDC provider when Docker is available. Tests disable the OIDC Dev Service unless a test explicitly configures an issuer. Production traffic must use TLS at a trusted ingress or at the application boundary. Restrict CORS and apply gateway-level rate limits to enrollment and operator endpoints.

## Operator API

All routes below require a verified JWT and a scoped role.

| Method | Path | Permission | Purpose |
|---|---|---|---|
| POST | `/api/v1/console/environments/enrollment-tokens` | runtime admin | Issue a one-time enrollment token |
| DELETE | `/api/v1/console/enrollment-tokens/{tokenId}` | runtime admin | Revoke an unconsumed token |
| GET | `/api/v1/console/runtimes` | runtime read | List scoped runtimes; supports status, page, size |
| GET | `/api/v1/console/runtimes/{runtimeId}` | runtime read | Runtime details and inventory |
| POST | `/api/v1/console/runtimes/{runtimeId}/revoke` | runtime admin | Revoke a runtime credential |
| GET | `/api/v1/console/audit` | runtime read | Scoped audit history; supports page and size |

The issuance response includes the enrollment token ID (used for revocation) and plaintext token. Plaintext is returned only in that issuance response. Deliver it through a secure provisioning channel; the database stores only its digest.

## Scope and information disclosure

Runtime and audit queries are constrained by both the verified JWT organization and its environment allow-list. A runtime outside the operator's scope is reported as not found rather than disclosing its existence. Environment IDs in request bodies are accepted only when present in the verified JWT's `environment_ids` claim.

## Audit model

Audit records are append-only at the application layer and include actor, organization, optional environment, action, resource type/id, timestamp, and outcome. Current events include:

- `ENROLLMENT_TOKEN_ISSUED`
- `ENROLLMENT_TOKEN_REVOKED`
- `RUNTIME_ENROLLED`
- `RUNTIME_INVENTORY_REPORTED`
- `RUNTIME_REVOKED`

Database-level immutability, retention policies, export, and tamper-evident chaining are later hardening tasks. Audit access is tenant/environment scoped.

## Operational notes and remaining hardening

- Configure the OIDC authorization server URL, expected issuer, client ID, and audience before enabling operator traffic.
- Configure issuer-side claim mapping so the token contains the exact claims above.
- Add rate limits and abuse detection at Iket or the ingress gateway.
- Validate signing-key rotation behavior with the selected OIDC provider, including old-token overlap and JWKS cache refresh.
- Add PostgreSQL integration tests for scope isolation, pagination, token revocation, and concurrent redemption.
- Add audit retention/export and tamper-evidence.
- Add operator UI only after the API contract is accepted.

The Console remains a management plane. Revoking a registration credential blocks future authenticated management traffic from that runtime; it does not terminate workflows already executing on the runtime.


## Runtime credential rotation

Runtime credentials are 256-bit opaque bearer tokens. The Console stores only SHA-256 digests. A runtime administrator can rotate a credential using:

`POST /api/v1/console/runtimes/{runtimeId}/rotate-credential`

The response contains the new token exactly once, its expiry, and the incremented credential version. Rotation is atomic: the previous token stops authenticating as soon as the transaction commits. The runtime must receive and persist the new token securely before discarding its old local copy. If the response is lost, an administrator must rotate again; the Console does not retain recoverable plaintext credentials. Revoked or expired runtimes cannot be rotated.

## Tamper-evident audit chain

Audit records are sequenced and hash-chained independently for each organization/environment scope. Each record binds the scope, sequence, prior hash, actor, action, resource, timestamp, outcome, and hash schema version. The chain head is updated in the same database transaction as the audit append, under a row lock for that scope. Legacy audit rows are preserved and marked as legacy; the new chain starts after the legacy sequence boundary.

Administrators can verify a chain using:

`GET /api/v1/console/audit/verify?environmentId={environmentId}`

The verifier reports the number of verified records, validity, the first invalid sequence when detected, and stored/computed heads. The hash chain is tamper-evident, not tamper-proof: a database administrator able to rewrite both records and chain head could reconstruct it. Production deployments should periodically export signed checkpoints to an independent immutable store. Audit payloads must not contain secrets, bearer tokens, or raw credentials.
