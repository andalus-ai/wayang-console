# ADR-009: Identity provider boundary for Wayang Console

- Status: Proposed implementation decision
- Date: 2026-11-06
- Scope: Wayang Console M1 operator authentication

## Context

Wayang Console needs verified operator identity, organization/environment scope, and permissions. It must be independently deployable and must not require Numou. It should support Keycloak and standards-compliant OIDC providers, while allowing a future Numou integration.

Numou currently has a substantial identity service with its own application/domain model, PostgreSQL persistence, federation, tenant membership, RBAC/ABAC, token issuance, and Kafka-backed identity event relay. It is not a small neutral library. Directly importing its deployable identity adapter would couple Wayang Console to Numou's service and persistence topology.

## Decision

1. Wayang Console owns its operator principal contract and identity-provider port.
2. The default adapter validates OIDC access tokens through Quarkus OIDC, using issuer discovery/JWKS from the configured authorization server.
3. Operator scope is derived only from verified token claims:
   - `sub`: stable actor identity
   - `organization_id`: organization boundary
   - `environment_ids`: authorized environment allow-list
   - `groups`: role names mapped to Quarkus roles
4. The Console application layer receives a `ConsoleOperatorPrincipal`, not raw JWT claims.
5. A future `wayang-console-adapter-numou` may map Numou-issued access tokens or an authenticated Numou principal into this port. It must remain optional and separately deployable.
6. Wayang Console does not import Numou's identity entities, repositories, database migrations, Kafka relay, or business installation lifecycle.
7. Runtime registration credentials remain a separate machine identity and are not OIDC operator credentials.

## Consequences

- Community Console can run with Keycloak or another OIDC provider and does not require Numou.
- OIDC issuer, signature, and audience validation are deployment configuration, not custom cryptographic code.
- Role mapping is explicit and deny-by-default.
- Organization/environment claims must be minted by a trusted identity provider after membership/authorization checks; caller-supplied headers or request fields are not trusted.
- Numou integration can be added without changing the Console application service contracts.

## Numou reuse assessment

Inspected Numou modules include:

- `identity/numou-identity-adapter`: complete Quarkus identity service adapter, Postgres repositories, OIDC federation, RBAC/ABAC, service accounts, token issuance, and Kafka event relay.
- `core/framework/foundation`: tenant identifier and tenant-aware contracts.
- `core/framework/aggregate/aggregate-persistence`: reactive tenant transaction and transactional outbox patterns.
- `application-platform`: application installation authorization and outbox contracts.

These are useful implementation references, but direct dependencies are not justified for this Console slice. In particular, Numou's reactive persistence and identity schema are not interchangeable with this repository's Quarkus ORM/Liquibase model.

## Security requirements

- Validate JWT signature, issuer, and audience using OIDC provider metadata/JWKS.
- Configure TLS at the ingress or application boundary.
- Map only explicitly approved role claims.
- Reject missing/empty organization or environment scope.
- Do not allow environment scope to be supplied by API request payloads.
- Keep enrollment and runtime credentials separate from operator JWTs.
- Never log raw access tokens or enrollment-token plaintext.
