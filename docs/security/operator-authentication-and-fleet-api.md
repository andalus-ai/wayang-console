# Operator authentication and fleet API

## Trust boundary

Runtime credentials and operator credentials are separate security principals.

- Runtime credentials are high-entropy opaque bearer tokens issued during one-time enrollment. Only SHA-256 digests are stored.
- Operator access tokens are signed JWTs verified by the Quarkus SmallRye JWT extension.
- Operator identity and scope are never accepted from request bodies, query parameters, or headers other than the bearer token.

## JWT contract

The Console expects the verified JWT to contain:

| Claim | Type | Meaning |
|---|---|---|
| `sub` | string | Stable operator identity, written to audit records |
| `organization_id` | UUID string | The organization boundary |
| `environment_ids` | array of UUID strings | Environments the operator may access |
| `groups` or Keycloak `realm_access/roles` / `resource_access/{client-id}/roles` | array of strings | Permissions mapped to Quarkus roles |

Quarkus OIDC maps standard `groups` claims and Keycloak realm/client role claims to `SecurityIdentity` roles. Configure the identity provider to issue these exact Console role names; do not grant broad administrator roles by default.

Supported roles:

- `console:runtime:read`: read runtime fleet, inventory, and audit history
- `console:runtime:admin`: issue/revoke enrollment credentials and revoke runtime registrations; also grants read access

Configure OIDC bearer-token verification using Quarkus OIDC. The Console uses provider discovery and JWKS to verify signatures, and validates the configured issuer and audience. Configure production values:

```properties
mp.jwt.verify.publickey.location=${WAYANG_CONSOLE_JWT_PUBLIC_KEY_LOCATION}
mp.jwt.verify.issuer=${WAYANG_CONSOLE_JWT_ISSUER}
```

Supply the public key and issuer through deployment configuration or a mounted secret. Do not commit private keys or production issuer values. In production, terminate TLS at a trusted ingress or enable HTTPS at the application boundary. Restrict CORS and apply gateway-level rate limits to enrollment and operator endpoints.

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

The issuance response includes the enrollment token ID (used for revocation) and the plaintext token. The enrollment token plaintext is returned only in that issuance response. Persist it only in a secret manager or deliver it through a secure provisioning channel. The database stores only its digest.

## Scope and information disclosure

Runtime and audit queries are constrained by both the JWT organization and its environment allow-list. A runtime outside the operator's scope is reported as not found rather than disclosing its existence. Environment IDs in request bodies are accepted only when present in the verified JWT's `environment_ids` claim.

## Audit model

Audit records are append-only at the application layer and include actor, organization, optional environment, action, resource type/id, timestamp, and outcome. Current events include:

- `ENROLLMENT_TOKEN_ISSUED`
- `ENROLLMENT_TOKEN_REVOKED`
- `RUNTIME_ENROLLED`
- `RUNTIME_INVENTORY_REPORTED`
- `RUNTIME_REVOKED`

Database-level immutability, retention policies, export, and tamper-evident chaining are later hardening tasks. Audit access is tenant/environment scoped.

## Operational notes and remaining hardening

- The OIDC authorization server URL, expected issuer, client ID, and audience must be configured before enabling operator traffic.
- Configure issuer-side claim mapping so the token contains the exact claims above.
- Add rate limits and abuse detection at Iket or the ingress gateway.
- OIDC discovery/JWKS validation and issuer/audience checks are handled by Quarkus OIDC; validate key rotation behavior with the chosen provider and test it during deployment.
- Add PostgreSQL integration tests for scope isolation, pagination, token revocation, and concurrent redemption.
- Add audit retention/export and tamper-evidence.
- Add operator UI only after the API contract is accepted.

The Console remains a management plane. Revoking a registration credential blocks future authenticated management traffic from that runtime; it does not remotely stop workloads already executing on the runtime.
