# AGENTS.md — hsm-keystore-impl/safenet-luna-impl/

Parent guide: [`../../AGENTS.md`](../../AGENTS.md)

## Purpose

SafeNet Luna HSM implementation of
`io.mosip.kernel.core.keymanager.spi.KeyStore`, backed by SafeNet's
`com.safenetinc.luna.provider.LunaProvider`. Talks to a real (or vendor
test) Luna partition — there is no local/simulated mode. Like
`ncipher-hsm-impl`, this module depends on both
`io.mosip.kernel:kernel-core` and `kernel-keymanager-service`.

## Layout

```text
safenet-luna-impl/
├── pom.xml
└── src/main/java/io/mosip/keymanager/hsm/impl/
    └── SafenetLunaKeyStoreImpl.java   # the KeyStore implementation (~370 lines)
```

## `SafenetLunaKeyStoreImpl` structure

Constructor (`SafenetLunaKeyStoreImpl(Map<String, String> params)`)
reads config from `params`, then `initKeystore()`: instantiate
`LunaProvider` → `addProvider()` (register with `java.security.Security`,
removing any stale registration first) → read the partition password →
`getKeystoreInstance()`.

Notable behavior specific to this module:

- **No local key-reference file.** `getKeystoreInstance()` loads the
  `KeyStore` from a `ByteArrayInputStream` built from the literal string
  `"slot:" + slotNumber` — it points the Luna provider at a specific
  HSM slot directly, not at a local file. Correspondingly,
  `persistKeyInHSM()` calls `keyStore.store(null, partitionPwdCharArr)`
  — a **null output stream** — because there's nothing local to write
  to; the Luna provider persists key operations straight to the HSM
  slot. This is different from both `aws-cloudhsm-impl` and
  `ncipher-hsm-impl`, which maintain a local key-reference file at a
  `keyStoreFile` path — do not add a `keyStoreFile`/local-file pattern
  here without understanding this module doesn't need one.
- **No retry/reload logic and no key reference cache** — same as
  `ncipher-hsm-impl`, a single `KeyStoreException` during
  `getAsymmetricKey` propagates immediately; nothing is cached in
  memory across calls. Do not assume this module matches
  `aws-cloudhsm-impl`'s resilience/caching behavior.
- **Inconsistent provider argument in key generation**: asymmetric key
  generation calls `KeyPairGenerator.getInstance(asymmetricKeyAlgorithm)`
  (no explicit provider name — relies on whichever provider is first in
  the JVM's provider list for that algorithm), while symmetric key
  generation calls `KeyGenerator.getInstance(symmetricKeyAlgorithm,
  lunaProvider.getName())` (explicit provider). This asymmetry exists in
  the current code — verify which provider actually services the
  asymmetric call in your environment before assuming it's always
  `LunaProvider`, and flag it in review if you touch this method.

## Build & Test Commands

```bash
cd hsm-keystore-impl/safenet-luna-impl
mvn clean install
```

Requires `com.safenetinc:luna:1.10.0`,
`io.mosip.kernel:kernel-keymanager-service:1.2.1-SNAPSHOT` (classifier
`lib`), and `io.mosip.kernel:kernel-core:1.2.1-SNAPSHOT` to already be
resolvable — see the parent guide's Configuration section for installing
the vendor JAR locally. No unit tests exist; verification is
manual/integration against a real or vendor-test Luna HSM.

## Configuration

Constructor `params` keys this module reads (see parent guide's
Configuration section for the shared `KeymanagerConstant` keys used by
all three modules):

| Key | Purpose |
|---|---|
| `keyStoreType` | JCE keystore type string passed to `KeyStore.getInstance` |
| `slotNo` | The Luna HSM slot number to load the keystore from (no local file involved) |
| `partitionPwd` | The Luna partition password |

## Agent rules

### Do

1. Keep the slot-based `KeyStore` loading (`"slot:" + slotNumber`) and
   the `store(null, ...)` persistence call as-is — there is no local
   file for this module to write to, unlike the other two.
2. Verify which security provider actually resolves
   `asymmetricKeyAlgorithm` before relying on the no-explicit-provider
   `KeyPairGenerator.getInstance` call, since it doesn't pin
   `LunaProvider` explicitly the way the symmetric path does.

### Do not

1. Do not add a `keyStoreFile`/local-reference-file parameter to this
   module by copying the pattern from `aws-cloudhsm-impl` or
   `ncipher-hsm-impl` — this module's `KeyStore` is loaded directly from
   an HSM slot and has no local file to manage.
2. Do not assume this module retries or caches like
   `aws-cloudhsm-impl` — it fails immediately on the first
   `KeyStoreException` and caches nothing.
3. Do not hard-code `partitionPwd`/`slotNo` — they must keep flowing in
   via the constructor's `params` map.
