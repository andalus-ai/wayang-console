# M1.8 — Artifact supply chain

Adds an artifact storage SPI, local filesystem adapter, scoped registry, digest verification, and Ed25519 detached-signature verification.

## Upload
Operator admins upload raw bytes to `POST /api/v1/console/artifacts` using `application/octet-stream` and:
- `X-Artifact-Digest: sha256:<64 hex>`
- `X-Manifest-Digest: sha256:<64 hex>`
- `X-Signing-Key-Id: <key identifier>`
- `X-Artifact-Signature: <base64 Ed25519 signature>`

The signature covers UTF-8 bytes of `artifactDigest + "\\n" + manifestDigest + "\\n" + keyId`. The trust key is Base64-encoded Ed25519 SubjectPublicKeyInfo DER configured by `WAYANG_CONSOLE_ARTIFACT_SIGNING_PUBLIC_KEY`. The private key remains in the release-signing pipeline, never in Console.

Uploads require exactly one selected environment. Bytes are streamed to a temporary file, size-limited, SHA-256 checked, and atomically published. A mismatch or invalid signature rejects registration and removes the staged object.

## Runtime download
`GET /api/v1/runtime/artifacts/{digest}` requires the runtime registration bearer credential. The lookup is organization/environment scoped and returns content length and verified digest headers. Runtime command consumers can derive this stable path from the digest; no storage credentials are issued.

## Configuration
- `wayang.console.artifacts.root` — local root, default `./data/artifacts`
- `wayang.console.artifacts.max-size-bytes` — default 256 MiB
- `wayang.console.artifacts.ed25519-public-key` — trusted public key (required for upload)
- `wayang.console.artifacts.require-registered` — enabled in production; deployment creation requires an uploaded artifact and matching manifest.

The `ArtifactStorage` port allows S3-compatible adapters without changing application contracts. This milestone ships the local adapter; S3 adapter, multi-key rotation/revocation, runtime-side signature verification, and compatibility policy remain follow-ups. Runtime must independently verify digest and signature before applying a deployment.
