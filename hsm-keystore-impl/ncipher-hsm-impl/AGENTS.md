# AGENTS.md — hsm-keystore-impl/ncipher-hsm-impl/

Parent guide: [`../../AGENTS.md`](../../AGENTS.md)

## Purpose

nCipher / Thales nShield HSM implementation of
`io.mosip.kernel.core.keymanager.spi.KeyStore`, backed by nCipher's
`nfkm`/`nCipherKM` JCE provider. Talks to a real (or vendor test)
nShield Security World — there is no local/simulated mode. This is the
only one of the three modules with an extra MOSIP dependency,
`io.mosip.kernel:kernel-core`, beyond `kernel-keymanager-service`.

## Layout

```text
ncipher-hsm-impl/
├── pom.xml
└── src/main/java/io/mosip/keymanager/hsm/impl/
    └── NCipherHSMKeyStoreImpl.java   # the KeyStore implementation (~410 lines) — no separate callback handler class, unlike aws-cloudhsm-impl
```

## `NCipherHSMKeyStoreImpl` structure

Constructor (`NCipherHSMKeyStoreImpl(Map<String, String> params)`) reads
config from `params`, then `initKeystore()`: instantiate `nCipherKM`
provider → `addProvider()` (register with `java.security.Security`,
removing any stale registration first) → read the card protection
password → `nCipherKM.getSW()` to get the current
`SecurityWorld` → `loadFIPSAuth(secWorld)` → `getKeystoreInstance()`
(load/create the local key-reference file at `keyStoreFilePath`,
protected by the card password).

Notable behavior not obvious from the interface alone:

- **`loadFIPSAuth`** iterates every `Module` in the Security World and,
  for each module's slot 0 (skipping modules with no slot 0), calls
  `secWorld.loadFipsAuth(slot)`. This is nCipher-specific FIPS
  authentication setup required before the provider can use the module
  — it has no equivalent in the AWS or SafeNet Luna modules.
- **`System.setProperty(NFAST_PROTECT, PROTECT_TYPE)`** — sets the JVM
  system property `protect=cardset` before creating the `KeyStore`
  instance. This tells the nCipher provider that keys are protected by
  an Operator Card Set (as opposed to, e.g., a softcard). If a
  deployment uses a different protection method, this line needs to
  change — don't assume `cardset` is universal across all nCipher
  deployments.
- **No retry/reload logic**: unlike `aws-cloudhsm-impl`, this module
  does not retry or reload the provider on `KeyStoreException` in
  `getAsymmetricKey` — a single failure propagates immediately as a
  `KeystoreProcessingException`. Don't assume the two modules have
  matching resilience behavior.
- **No key reference cache**: unlike `aws-cloudhsm-impl`, there is no
  in-memory caching of fetched keys — every `getAsymmetricKey`/
  `getSymmetricKey` call re-reads the local key-reference file.
- **Key generation uses standard JCE, not vendor-specific attribute
  maps**: `generateKeyPair`/`generateSymmetricKey` call plain
  `KeyPairGenerator.getInstance(alg, providerName)` /
  `KeyGenerator.getInstance(alg, providerName)` with a `SecureRandom`,
  unlike `aws-cloudhsm-impl`'s CloudHSM-specific
  `KeyAttributesMapBuilder` API.

## Build & Test Commands

```bash
cd hsm-keystore-impl/ncipher-hsm-impl
mvn clean install
```

Requires `com.ncipher.km:nfkm:1.0.0`,
`io.mosip.kernel:kernel-keymanager-service:1.2.1-SNAPSHOT` (classifier
`lib`), and `io.mosip.kernel:kernel-core:1.2.1-SNAPSHOT` to already be
resolvable — see the parent guide's Configuration section for installing
the vendor JAR locally. No unit tests exist; verification is
manual/integration against a real or vendor-test nShield HSM.

## Configuration

Constructor `params` keys this module reads (see parent guide's
Configuration section for the shared `KeymanagerConstant` keys used by
all three modules):

| Key | Purpose |
|---|---|
| `keyStoreType` | JCE keystore type string passed to `KeyStore.getInstance` |
| `keyStoreFile` | Path to the local key-reference file |
| `cardProtection` | The Operator Card Set password — **not** a generic "keystore password"; the provider/property setup (`protect=cardset`) assumes card-set protection specifically |

## Agent rules

### Do

1. Keep `loadFIPSAuth` and the `protect=cardset` system property set
   before any `KeyStore.getInstance` call — both are required setup
   steps specific to this vendor's provider.
2. Verify any change against this module's actual (simpler, no
   retry/cache) error-handling behavior rather than assuming it matches
   `aws-cloudhsm-impl`.

### Do not

1. Do not assume `cardset` protection is the only valid nCipher
   protection mode — it's what this module currently hard-codes via
   `System.setProperty`, not a documented constraint from the vendor SDK.
2. Do not port `aws-cloudhsm-impl`'s retry/reload or key-reference-cache
   logic here without discussing it — this module's simpler
   fail-immediately behavior may be intentional for this vendor's
   connection characteristics.
3. Do not hard-code `cardProtection` — it must keep flowing in via the
   constructor's `params` map.
