# AGENTS.md — hsm-keystore-impl/aws-cloudhsm-impl/

Parent guide: [`../../AGENTS.md`](../../AGENTS.md)

## Purpose

AWS CloudHSM implementation of `io.mosip.kernel.core.keymanager.spi.KeyStore`,
backed by AWS's `cloudhsm-jce` provider. Talks to a real (or vendor test)
CloudHSM cluster — there is no local/simulated mode.

## Layout

```text
aws-cloudhsm-impl/
├── pom.xml
└── src/main/java/io/mosip/keymanager/hsm/impl/
    ├── AWSCloudHSMKeyStoreImpl.java     # the KeyStore implementation (~560 lines)
    └── ApplicationCallBackHandler.java   # JAAS CallbackHandler used to supply the HSM login PIN
```

## `AWSCloudHSMKeyStoreImpl` structure

Constructor (`AWSCloudHSMKeyStoreImpl(Map<String, String> params)`) does,
in order: read config from `params` → `setupProvider()` (instantiate
`CloudHsmProvider`) → `addProvider()` (register it with
`java.security.Security`, removing any stale registration first) →
`loginHSM(cuUserName, cuPassword)` (via `ApplicationCallBackHandler`) →
`initKeystore()` (load/create the local `KeyStore` reference file at
`keyStoreFilePath`) → `initKeyReferenceCache()` if
`enableKeyReferenceCache` is set. Any failure at any of these steps
throws immediately — there is no partial/degraded-mode construction.

Notable behavior not obvious from the interface alone:

- **Local keystore reference file**: `keyStoreFilePath` is a local file
  that AWS's JCE provider uses to track key handles/labels — CloudHSM
  itself holds the actual key material. `persistKeyInHSM()` rewrites
  this file after every key create/store. Losing this file does **not**
  lose the keys in the HSM, but does break this implementation's ability
  to find them via the standard `KeyStore` API without extra recovery
  steps.
- **Retry + provider reload on `getAsymmetricKey`**: if the underlying
  `KeyStoreException` is thrown while fetching a private key entry, the
  code calls `reloadProvider()` (re-instantiates and re-registers the
  CloudHsmProvider, reloads the keystore) and retries, up to
  `NO_OF_RETRIES` (3) times. `reloadProvider()` itself is
  rate-limited via `lastProviderLoadedTime` +
  `PROVIDER_ALLOWED_RELOAD_INTERVEL_IN_SECONDS` (60s) — it silently
  no-ops (just logs a warning) if called again within that window. Keep
  this rate-limit in mind if you change retry behavior: removing it
  could hammer the HSM connection on a persistent failure.
- **Optional in-memory key reference cache**: this is the only one of
  the three modules with this feature (`enableKeyReferenceCache`,
  driven by the `KeymanagerConstant.FLAG_KEY_REF_CACHE` param).
  `privateKeyReferenceCache`/`secretKeyReferenceCache` are plain
  `ConcurrentHashMap`s with no eviction/TTL — every key ever fetched
  stays cached for the life of the JVM instance when this flag is on.
- **RSA key generation attributes**: `generateKeyPair()` builds explicit
  `KeyAttributesMap`s for public (`VERIFY`, `ENCRYPT`, `MODULUS_BITS`,
  fixed public exponent `65537`) and private (`SIGN`, `DECRYPT`) key
  halves via AWS's `KeyAttributesMapBuilder`/`KeyPairAttributesMapBuilder`
  — this is CloudHSM-SDK-specific API, not standard JCE
  `AlgorithmParameterSpec`.

## `ApplicationCallBackHandler`

A minimal `javax.security.auth.callback.CallbackHandler`: constructed
with the `cuUser:cuPassword` string, and on `handle()` sets that value
as the password for any `PasswordCallback` it receives. This is how the
constructor's `cloudHSMProvider.login(null, loginHandler)` call actually
supplies credentials to AWS's JCE provider — there's no other login path
in this module.

## Build & Test Commands

```bash
cd hsm-keystore-impl/aws-cloudhsm-impl
mvn clean install
```

Requires `com.amazonaws.cloudhsm:cloudhsm-jce:5.14.0` and
`io.mosip.kernel:kernel-keymanager-service:1.2.1-SNAPSHOT` (classifier
`lib`) to already be resolvable — see the parent guide's Configuration
section for installing the vendor JAR locally. No unit tests exist;
verification is manual/integration against a real or vendor-test
CloudHSM cluster.

## Configuration

Constructor `params` keys this module reads (see parent guide's
Configuration section for the shared `KeymanagerConstant` keys used by
all three modules):

| Key | Purpose |
|---|---|
| `keyStoreType` | JCE keystore type string passed to `KeyStore.getInstance` |
| `keyStoreFile` | Path to the local key-reference file (see above) |
| `localKeyStorePwd` | Password protecting that local reference file — **not** the HSM crypto-user password |
| `cuUserName` | CloudHSM crypto user (CU) username |
| `cuPassword` | CloudHSM crypto user password |
| `KeymanagerConstant.FLAG_KEY_REF_CACHE` | Enables the in-memory key reference cache described above |

## Agent rules

### Do

1. Preserve the retry + rate-limited-reload pattern in
   `getAsymmetricKey`/`reloadProvider` if you touch error handling there
   — it exists to tolerate transient CloudHSM connection issues without
   hammering the provider on persistent failure.
2. Keep `ApplicationCallBackHandler` as the only login path — don't
   introduce a second way to authenticate to the provider.
3. Treat `keyStoreFilePath` as a local reference cache, not the source
   of truth for key material — the HSM is.

### Do not

1. Do not remove or shorten `PROVIDER_ALLOWED_RELOAD_INTERVEL_IN_SECONDS`
   without understanding it's a deliberate rate limit on provider
   reloads, not an arbitrary constant.
2. Do not assume `enableKeyReferenceCache` behavior applies to the other
   two modules — it's AWS-CloudHSM-specific.
3. Do not hard-code `cuUserName`/`cuPassword`/`localKeyStorePwd` — they
   must keep flowing in via the constructor's `params` map.
