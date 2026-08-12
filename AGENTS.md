# AGENTS.md

## Repository Overview

`hsm-ref-impl` holds MOSIP's reference implementations of the
`io.mosip.kernel.core.keymanager.spi.KeyStore` interface for three real,
commercial Hardware Security Modules (HSMs):

- **AWS CloudHSM** (`hsm-keystore-impl/aws-cloudhsm-impl`)
- **nCipher / Thales nShield HSM** (`hsm-keystore-impl/ncipher-hsm-impl`)
- **SafeNet Luna HSM** (`hsm-keystore-impl/safenet-luna-impl`)

These are not a software simulator. Each module is a thin JCE (Java
Cryptography Extension) provider wrapper around a real vendor SDK, used by
MOSIP's `kernel-keymanager-service` to generate, store, and use signing/
encryption keys that live inside a physical or cloud HSM. There is no
SoftHSM, PKCS#11 simulator, or dev-mode key store in this repository — every
module here talks to actual HSM hardware or a cloud HSM service and expects
that hardware/service to be reachable and provisioned.

Each module is an independent, standalone Maven project (there is no
aggregator/parent `pom.xml` at the repository root). They are built and
deployed one at a time, matching whichever HSM vendor a given MOSIP
deployment uses.

## Technology Stack

- **Language**: Java, compiled for Java 21 (`maven.compiler.source` /
  `maven.compiler.target` = `21` in each module's `pom.xml`).
- **Build tool**: Apache Maven.
- **Shared MOSIP dependency**: `io.mosip.kernel:kernel-keymanager-service`
  (version `1.2.1-SNAPSHOT`, classifier `lib`) in all three modules, plus
  `io.mosip.kernel:kernel-core` in the nCipher and SafeNet Luna modules.
  These are MOSIP kernel artifacts, not vendor artifacts.
- **Vendor SDK dependencies** (one per module, proprietary — see
  Configuration section below):
  - `com.amazonaws.cloudhsm:cloudhsm-jce:5.14.0` (AWS CloudHSM JCE provider)
  - `com.ncipher.km:nfkm:1.0.0` (nCipher/Thales `nfkm` Java library)
  - `com.safenetinc:luna:1.10.0` (SafeNet Luna JCE provider)
- **License**: Mozilla Public License 2.0 (`LICENSE`).

## Build & Test Commands

There are no automated tests in this repository (no `src/test` directories
in any module) and no CI workflows (no `.github/workflows` directory).
Building means compiling and packaging one module at a time from inside
that module's directory, because there is no root `pom.xml`:

```shell
cd hsm-keystore-impl/aws-cloudhsm-impl
mvn clean install
```

```shell
cd hsm-keystore-impl/ncipher-hsm-impl
mvn clean install
```

```shell
cd hsm-keystore-impl/safenet-luna-impl
mvn clean install
```

Each `mvn clean install` will fail unless the vendor SDK dependency for that
module (see Configuration) and the `io.mosip.kernel` snapshot artifacts are
already available in your local Maven repository or a reachable Maven
repository/mirror — none of these are on Maven Central.

## Configuration

- **Vendor SDKs are not bundled and are not on public Maven Central.**
  Before building a module you must obtain the matching vendor SDK from the
  HSM vendor (AWS CloudHSM client SDK, nCipher/Thales Security World client
  software, or SafeNet Luna client software) and install the corresponding
  JAR into your local Maven repository, for example:

  ```shell
  mvn install:install-file -Dfile=cloudhsm-jce-5.14.0.jar -DgroupId=com.amazonaws.cloudhsm -DartifactId=cloudhsm-jce -Dversion=5.14.0 -Dpackaging=jar
  ```

  Substitute the group ID, artifact ID, version, and JAR path shown in the
  target module's `pom.xml` for the other two vendors.

- **Runtime credentials/parameters are passed in code, not in a config
  file checked into this repo.** Each implementation class is constructed
  with a `Map<String, String> params` (see the constructors in
  `AWSCloudHSMKeyStoreImpl.java`, `NCipherHSMKeyStoreImpl.java`, and
  `SafenetLunaKeyStoreImpl.java`). The caller (MOSIP's keymanager service)
  supplies these keys at runtime — this repo has no properties/YAML file
  defining them:
  - AWS CloudHSM: `keyStoreType`, `keyStoreFile`, `localKeyStorePwd`,
    `cuUserName`, `cuPassword`, plus the shared `KeymanagerConstant` keys
    for symmetric/asymmetric key algorithm, key size, and certificate sign
    algorithm.
  - nCipher: `keyStoreType`, `keyStoreFile`, `cardProtection`, plus the
    same shared `KeymanagerConstant` keys.
  - SafeNet Luna: `keyStoreType`, `slotNo`, `partitionPwd`, plus the same
    shared `KeymanagerConstant` keys.
- **Never hard-code or commit real HSM credentials** (crypto user
  password, card protection password, partition password) anywhere in this
  repository. The source intentionally marks the places passwords are used
  with `@SuppressWarnings("findsecbugs:HARD_CODE_PASSWORD")` because the
  password strings are passed in from external configuration at
  construction time, not hard-coded — keep it that way in any change.

## Project Structure Notes

```text
hsm-ref-impl/
  LICENSE
  README.md
  hsm-keystore-impl/
    aws-cloudhsm-impl/       # AGENTS.md
      pom.xml
      src/main/java/io/mosip/keymanager/hsm/impl/
        AWSCloudHSMKeyStoreImpl.java
        ApplicationCallBackHandler.java
    ncipher-hsm-impl/        # AGENTS.md
      pom.xml
      src/main/java/io/mosip/keymanager/hsm/impl/
        NCipherHSMKeyStoreImpl.java
    safenet-luna-impl/       # AGENTS.md
      pom.xml
      src/main/java/io/mosip/keymanager/hsm/impl/
        SafenetLunaKeyStoreImpl.java
```

- All three modules use the same Java package name
  (`io.mosip.keymanager.hsm.impl`) but live in separate Maven modules —
  only one implementation is ever on the classpath of a given deployment.
- Each implementation class implements the same MOSIP kernel interface
  (`io.mosip.kernel.core.keymanager.spi.KeyStore`) with the same method set
  (`getAllAlias`, `getKey`, `getAsymmetricKey`, `getPrivateKey`,
  `getPublicKey`, `getCertificate`, `getSymmetricKey`, `deleteKey`,
  `generateAndStoreAsymmetricKey`, `generateAndStoreSymmetricKey`,
  `storeCertificate`, `getKeystoreProviderName`), so behavior should stay
  consistent across modules unless a vendor's SDK genuinely requires
  otherwise.
- The AWS CloudHSM module is the only one with an optional in-memory key
  reference cache (`enableKeyReferenceCache`, controlled by the
  `KeymanagerConstant.FLAG_KEY_REF_CACHE` param) — the other two modules do
  not cache key entries.
- Each of the three modules also has its own `AGENTS.md`
  (`hsm-keystore-impl/<vendor>-impl/AGENTS.md`) documenting that
  module's implementation-class internals (constructor flow, vendor-SDK-
  specific behavior, resilience/caching differences between modules) in
  more depth than is useful to repeat here. Start with this root file
  for what's shared (build tool, Java target, interface contract,
  credential handling); go to the module guide for the rest.

## Development Workflow

1. Fork the repository and clone your fork.
2. Branch from `develop`, unless repository settings or a maintainer
   specify another target branch.
3. Keep code changes scoped to the relevant module directory
   (`hsm-keystore-impl/<vendor>-impl/`) where practical — modules are
   built and released independently. Root documentation, shared
   contract updates, and repository-level workflow changes are
   naturally repo-wide and are not bound by this.
4. Build that module with Maven as shown above and fix any compile errors.
   Because there are no unit tests, manual/integration verification against
   real (or vendor-provided test) HSM hardware is required to confirm HSM
   provider, authentication, and key-operation integration — do not assume
   a clean `mvn compile` means the HSM integration works.
5. Keep the three implementations' method behavior consistent with each
   other and with the shared `io.mosip.kernel.core.keymanager.spi.KeyStore`
   contract unless a vendor SDK genuinely forces a difference; note any such
   difference in the code and in your PR description.

## Pull Request Guidelines

- Target the `develop` branch.
- Keep changes scoped to one module per PR where practical, since each
  module is built and released independently.
- Do not add real HSM hostnames, crypto user names, passwords, PINs, or
  partition/slot identifiers to source, `pom.xml`, or commit history.
- Reference the tracking issue/ticket in the PR description.
- Since there is no CI in this repository, describe in the PR how you
  validated the change (module built successfully, and if possible, tested
  against real or vendor test HSM hardware).

## Repository-Specific Considerations

- **These are real HSM integrations, not a dev simulator.** Do not treat
  this repo like a local-only test double — every module here is meant to
  be built and deployed against genuine HSM hardware/service (AWS CloudHSM,
  nCipher nShield, or SafeNet Luna). Never point these implementations at
  production HSM partitions/slots from a development or test context.
- **Vendor SDKs are proprietary and not redistributable from this repo.**
  Expect `mvn install` to fail on a clean machine until the matching vendor
  JAR is installed locally (see Configuration).
- **Passwords/PINs flow through the `params` map at construction time**,
  sourced from MOSIP's keymanager configuration outside this repo — never
  add a default, fallback, or example password inside any of the three
  `*KeyStoreImpl.java` files.
- **No aggregator `pom.xml`, no tests, no CI**: verify any build/test claim
  yourself by running the module-level `mvn` commands above; do not assume
  a repository-level build exists.

## Agent rules

### Do

1. Verify any new build, test, or config claim against the actual
   `pom.xml` and `.java` files in the relevant module before writing it
   down.
2. Prefer working inside a single module directory
   (`hsm-keystore-impl/<vendor>-impl`) per change, and build that module
   with Maven to confirm it compiles.
3. Keep the three vendor implementations' public method behavior aligned
   with the shared `io.mosip.kernel.core.keymanager.spi.KeyStore` interface
   contract.
4. Treat passwords, PINs, slot numbers, and partition names as runtime
   configuration passed in via the `params` map — never hard-code them.
5. Target the `develop` branch for any change, and reference the tracking
   issue in commits/PRs.

### Do not

1. Do not describe this repository as a software HSM simulator, SoftHSM
   setup, or dev-only mock — it integrates with real HSM hardware/services.
2. Do not commit vendor SDK JARs (`cloudhsm-jce`, `nfkm`, `luna`) into this
   repository — they are proprietary and must be installed locally by each
   developer/CI environment.
3. Do not hard-code or commit real HSM credentials, hostnames, slot
   numbers, or partition names anywhere in source or history.
4. Do not invent a root-level Maven build command — there is no aggregator
   `pom.xml`; each module must be built from its own directory.
5. Do not claim CI validates changes here — there is no
   `.github/workflows` directory in this repository.
