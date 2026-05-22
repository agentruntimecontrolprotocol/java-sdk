---
title: "Provisioned credentials"
sdk: java
spec_sections: ["9.8", "14"]
order: 10
kind: feature
since: "1.1.0"
---

# Provisioned credentials — §9.8

**Feature flag:** `provisioned_credentials`.

Provisioned credentials let a runtime mint short-lived, lease-bound upstream
credentials for a job. The Java SDK ships the SPI and lifecycle wiring; vendor
adapters live outside core.

## Wire shape

```json
{
  "id": "cred_123",
  "scheme": "bearer",
  "value": "secret-token",
  "endpoint": "https://llm-gateway.example.test/v1",
  "profile": "fast",
  "constraints": {
    "cost.budget": ["USD:5.00"],
    "model.use": ["tier-fast/*"],
    "expires_at": "2026-05-21T12:00:00Z"
  }
}
```

## Java surface

Implement
[`CredentialProvisioner`](../../arcp-runtime/src/main/java/dev/arcp/runtime/credentials/CredentialProvisioner.java)
and configure it on the runtime:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .credentialProvisioner(provisioner)
    .credentialRevocationStore(new FileCredentialRevocationStore(path))
    .build();
```

The runtime calls `issue(...)` after the final lease is known and includes
the returned credential wire shape in `job.accepted.payload.credentials` for
the submitting client. `JobHandle.credentials()` exposes that field as an
`Optional<List<Credential>>`.

## Lifecycle

[`CredentialBinding`](../../arcp-runtime/src/main/java/dev/arcp/runtime/credentials/CredentialBinding.java)
records issued credentials before the job starts and revokes them on success,
error, cancellation, or timeout. Revocation retries transient failures three
times, then leaves the credential in the revocation store for later cleanup.

Agents can rotate a value through `JobContext.rotateCredential(id, newValue)`.
The runtime emits a `status` event with `phase: "credential_rotated"` and
revokes the previous value.

## Confidentiality

[`Credential.toString()`](../../arcp-core/src/main/java/dev/arcp/core/credentials/Credential.java)
redacts the secret `value`. Job summaries and subscription acknowledgements do
not carry credentials, so `session.list_jobs` and subscribers do not receive
job bearer values.

## Example

[`examples/provisioned-credentials/`](../../examples/provisioned-credentials/)
uses an in-memory provisioner and asserts the issued credential is revoked after
a successful job.
