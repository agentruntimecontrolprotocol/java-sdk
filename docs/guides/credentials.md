---
title: "Credentials"
sdk: java
spec_sections: ["9.8", "14"]
kind: guide
since: "1.1.0"
---

# Credentials (§9.8 / §14)

Provisioned credentials let the runtime inject short-lived secrets (API
keys, database passwords, OAuth tokens) into the agent context without
exposing them in the `job.submit` payload or event stream.

> **Since** v1.1. Requires the `credentials` feature to be negotiated in
> the session.

## CredentialProvisioner SPI

Implement
[`CredentialProvisioner`](../../arcp-runtime/src/main/java/dev/arcp/runtime/credentials/CredentialProvisioner.java)
to generate and revoke credentials:

```java
public class MyCredentialProvisioner implements CredentialProvisioner {
    @Override
    public Credential provision(CredentialBinding binding, JobContext ctx) {
        String apiKey = myVault.generateKey(binding.purpose(), ctx.principal());
        return Credential.of(binding.id(), apiKey, binding.purpose());
    }

    @Override
    public void revoke(Credential credential, JobContext ctx) {
        myVault.revokeKey(credential.id());
    }
}
```

Register with the runtime:

```java
ArcpRuntime runtime = ArcpRuntime.builder()
    .credentialProvisioner(new MyCredentialProvisioner())
    .credentialRevocationStore(new FileCredentialRevocationStore(
        Path.of("/var/arcp/revoked")))
    .agent("my-agent", "1.0.0", handler)
    .build();
```

## Using credentials inside an agent

```java
(input, ctx) -> {
    Credential apiKey = ctx.credentials().get("openai-key");
    // Use the credential value:
    String key = apiKey.value();   // never logged — see confidentiality below
    // Rotate mid-job:
    ctx.rotateCredential("openai-key", myVault.generateKey("openai"));
    return JobOutcome.Success.inline(result);
}
```

`JobContext.credentials()` returns the current credential map. Credentials
are provisioned before the agent starts and revoked when the job reaches a
terminal state.

## Lifecycle

```
job.submit received
  → CredentialProvisioner.provision() called for each CredentialBinding
  → credentials injected into JobContext
  → agent runs
  → job reaches terminal state (SUCCESS / ERROR / CANCELLED / TIMED_OUT)
  → CredentialProvisioner.revoke() called for each Credential
```

If the process crashes before `revoke` is called, the
`CredentialRevocationStore` persists outstanding credential IDs. On
startup, the runtime reads the store and calls `revoke` for any
credentials that were not cleaned up.

## Client side

Declare which credentials the agent will need in `job.submit`:

```java
JobSubmit submit = ArcpClient.jobSubmit("my-agent", payload)
    .credential(new CredentialBinding("openai-key", "openai-api-key"))
    .credential(new CredentialBinding("db-password", "postgres"))
    .build();
```

`JobHandle.credentials()` returns the provisioned credentials visible to
the client (value is redacted — see below).

## Confidentiality (§14)

`Credential.toString()` **redacts** the `value` field:

```java
Credential c = Credential.of("openai-key", "sk-abc123", "openai-api-key");
System.out.println(c);  // → Credential[id=openai-key, purpose=openai-api-key, value=***]
```

This prevents accidental logging of secrets. Access the raw value via
`Credential.value()` only when needed for the actual API call.

The `value` field is also absent from `job.event` payloads — it is never
transmitted on the wire after provisioning.

## Runnable example

[`examples/provisioned-credentials/`](../../examples/provisioned-credentials/) —
demonstrates the full lifecycle with an in-memory provisioner.
