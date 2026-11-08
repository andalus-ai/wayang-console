# M1.8 — Artifact supply chain

Wayang Console stores immutable artifact bytes and verifies SHA-256 digests plus Ed25519 detached signatures before registration.

## Upload

Operator admins upload raw bytes to `POST /api/v1/console/artifacts` using `application/octet-stream` and:
- `X-Artifact-Digest: sha256:<64 hex>`
- `X-Manifest-Digest: sha256:<64 hex>`
- `X-Signing-Key-Id: <key identifier>`
- `X-Artifact-Signature: <base64 Ed25519 signature>`

The signature covers UTF-8 bytes of `artifactDigest + "\\n" + manifestDigest + "\\n" + keyId`. The manifest is embedded in the signed artifact bundle; the runtime extracts it and verifies its digest against the signed manifest digest before applying the package. The private key remains in the release-signing pipeline, never in Console.

Uploads require exactly one selected environment. Bytes are size-limited, SHA-256 checked, and atomically published. A mismatch or invalid signature rejects registration and removes the staged object.

## Publisher signing-key trust

Keys are managed independently for each organization/environment through:
- `GET /api/v1/console/publisher-signing-keys?environmentId=...`
- `POST /api/v1/console/publisher-signing-keys?environmentId=...` with `{ "keyId": "...", "publicKeyDerBase64": "..." }`
- `POST /api/v1/console/publisher-signing-keys/{keyId}/revoke?environmentId=...` with `{ "reason": "..." }`

The public key must be an Ed25519 SubjectPublicKeyInfo DER value encoded with Base64. Console computes and exposes a SHA-256 fingerprint; it never accepts or stores private key material. Key IDs and fingerprints are unique within a scope. A key ID cannot be rebound to another key, and a revoked key cannot be reactivated; rotation means registering a new key ID and then revoking the old key.

Key registration and revocation are append-only lifecycle events and are also written to the existing audit integrity chain. Dedicated roles are required: `console:publisher-trust:read` and `console:publisher-trust:admin`. Admin implies read. All operations derive organization and environment scope from the verified operator principal.

When at least one managed key has been registered for an environment, artifact verification uses only that environment's managed keys; an unknown or revoked key is rejected. For backward compatibility, environments with no managed keys continue to use the legacy single-key configuration:
- `wayang.console.artifacts.ed25519-public-key`
- `wayang.console.artifacts.signing-key-id`

This is a migration path, not a recommended long-term multi-tenant trust configuration.

## Runtime download

`GET /api/v1/runtime/artifacts/{digest}` requires the runtime registration bearer credential. The lookup is organization/environment scoped and returns content length and verified digest headers. Runtime command consumers can derive this stable path from the digest; no storage credentials are issued. Runtime must independently verify digest and signature before applying a deployment.

## Storage

- `wayang.console.artifacts.root` — local root, default `./data/artifacts`
- `wayang.console.artifacts.max-size-bytes` — default 256 MiB
- `wayang.console.artifacts.require-registered` — enabled in production; deployment creation requires an uploaded artifact and matching manifest.

The `ArtifactStorage` port allows S3-compatible adapters without changing application contracts. S3-compatible storage, publisher identity verification/attestation, approval policy, and deployment-time trust revalidation remain follow-up work.
