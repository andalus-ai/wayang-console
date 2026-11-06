# Wayang Console backend

Quarkus application. Domain and application services remain independent of Quarkus, HTTP, persistence, and Numou APIs.

## Requirements

- JDK 21+
- Maven 3.9+
- PostgreSQL 16 for local development and integration testing

## Run

Start the database from the repository root:

```bash
docker compose up -d postgres
```

Then start Quarkus from the repository root:

```bash
mvn -f backend/pom.xml quarkus:dev
```

The datasource can be configured with `DB_USERNAME`, `DB_PASSWORD`, and `DB_JDBC_URL`.

## Authentication

Runtime endpoints use the opaque runtime registration bearer credential. Operator endpoints under `/api/v1/console/**` require a verified JWT. Configure `mp.jwt.verify.publickey.location` and `mp.jwt.verify.issuer` using deployment environment configuration. The JWT must include `sub`, `organization_id`, `environment_ids`, and `groups` claims as described in [the operator security contract](../docs/security/operator-authentication-and-fleet-api.md).

Roles:

- `console:runtime:read`
- `console:runtime:admin`

Do not expose operator routes until the issuer, signing-key validation, TLS, role mapping, and environment scope claims have been configured.

## Current API slices

- Runtime: `POST /api/v1/runtime/enroll`, `POST /api/v1/runtime/heartbeat`, `POST /api/v1/runtime/inventory`
- Operator: `POST /api/v1/console/environments/enrollment-tokens`, `DELETE /api/v1/console/enrollment-tokens/{tokenId}`
- Fleet: `GET /api/v1/console/runtimes`, `GET /api/v1/console/runtimes/{runtimeId}`, `POST /api/v1/console/runtimes/{runtimeId}/revoke`
- Audit: `GET /api/v1/console/audit`

Management commands remain disabled. Runtime revocation invalidates the credential; it does not terminate workflows already executing on the runtime.

## Integration tests

The backend integration suite starts an isolated PostgreSQL 16 container through Testcontainers, applies the Liquibase changelog, and exercises enrollment, revocation, inventory replacement, scope isolation, and pagination. Docker must be available to run the suite:

```bash
mvn -f backend/pom.xml verify
```

## Package boundaries

Keep domain, application, adapter, and bootstrap concerns separate. Do not include workflow execution engines or the full Wayang component catalog.
