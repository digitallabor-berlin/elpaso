# Adopting CMWallet's OpenID4VP 1.0 Matcher

**Date:** 2026-08-21
**Status:** Approved design, not yet implemented
**Scope:** DC API *presentation* matcher only. Issuance matcher unchanged.

## Goal

Replace the wallet's custom Rust DC API presentation matcher
(`app/src/main/assets/dcapi_matcher.wasm`, built from the sibling `../dcapi-matcher`
crate) with the reference C matcher from CMWallet (`matcher/openid4vp1_0.c`), compiled
in-house from vendored source using a containerised wasi-sdk toolchain.

**Motivation: feature parity with the platform default matcher.** Credential sets,
signed and multisigned request handling, and inline issuance entries are already
implemented upstream. Reimplementing them in the Rust matcher is work we would be doing
against a moving target; adopting the reference implementation makes upstream's DCQL
semantics the source of truth. Everything else in this document — the container, the
vendoring policy, the blob rewrite — follows from that single goal.

Secondary consequences, not goals in themselves: the binary shrinks from 447 KB to
roughly 70 KB, and the wallet stops depending on a sibling checkout that can change what
ships without producing a diff in this repository.

## Non-goals

- **The issuance matcher stays as-is.** `dc_issuance_matcher.wasm` remains CMWallet's
  prebuilt `provision_hardcoded.wasm`. The container gains the ability to build the
  `issuance_provision` target so a future swap is a one-liner, but that swap is not part
  of this change. It is not a no-op: per
  `docs/superpowers/specs/2026-08-21-dc-api-issuance-design.md`, the prebuilt binary and
  a locally-built `provision.wasm` differ in which protocol identifiers they recognise,
  so it needs its own verification pass.
- **No Gradle-integrated wasm build.** The binary is a committed artifact.
- **No changes to the presentation flow itself.** `PresentationClient`, `PresentScreen`,
  and the consent/authorisation path are untouched.

## Findings that constrain the design

These were verified against the actual artifacts and sources, not from memory. Each one
would invalidate part of the design if it were wrong, so they are recorded with their
evidence.

### 1. `supported_protocols` does not exist before registry alpha05

`matcher/openid4vp1_0.c::main` reads `supported_protocols` from the registry blob and
loops over it, calling `process_request` only for requests whose `protocol` matches an
entry. When the key is absent, `supported_protocols_size == 0`, the loop body never
executes, and **nothing ever matches**.

Byte-level inspection of the published artifacts:

| JSON key | `registry-digitalcredentials-openid:1.0.0-alpha04` | `:1.0.0-alpha05` |
| --- | --- | --- |
| `supported_protocols` | absent | present |
| `metadata_display_text` | absent | present |
| `paths`, `verification`, `display_value` | present | present |

alpha05 adds a 5-argument `OpenId4VpRegistry` constructor taking
`List<String> supportedProtocols`, plus `PROTOCOL_OPENID4VP_1_0_UNSIGNED`,
`_SIGNED`, `_MULTISIGNED` constants. alpha04 has no such parameter. CMWallet itself
builds against `1.0.0-SNAPSHOT`, which is why its matcher assumes the key is there.

**Consequence:** the registry dependency bump is not optional polish. Without alpha05 the
adopted matcher cannot match anything.

### 2. The blob serialiser is androidx's, and is reachable

`matcher/index.md` states that the registry blob's source of truth is the Jetpack
`OpenId4VpRegistry` class, and describes the layout: a 4-byte little-endian offset to the
JSON, raw icon bytes between header and JSON, then JSON with `{start, length}` icon
addressing.

`OpenId4VpRegistry` is `final` and takes no `matcher` parameter, so it cannot be
subclassed to inject our wasm. But it extends `DigitalCredentialRegistry`, so its
inherited `credentials: ByteArray` **is** the stock blob. Building one and handing its
bytes plus our own matcher to a `DigitalCredentialRegistry` subclass is exactly the
pattern sitting commented out in CMWallet's `CmWalletApplication.kt:91-94`.

**Consequence:** we do not hand-roll the binary format. `MatcherPackageBuilder`'s
bespoke pure-JSON `PackageConfig` (no binary header, base64-inlined icons) is replaced
rather than adapted.

### 3. Credman allowlists wasm exports to `{memory, _start, main}`

Recorded in the existing `scripts/build-matcher.sh`: anything else surfaces as
`IllegalArgumentException: Unknown export` at request time and the wallet silently
disappears from the picker. That script already carries a Python wasm export-section
filter for this reason. A wasi-sdk link emits `__heap_base`, `__data_end`,
`__indirect_function_table` and friends, so the new build needs the same treatment.

### 4. PaSO payment rendering is a real regression unless the C is patched

`matcher/openid4vp1_0.c` hardcodes three `transaction_data.type` cases:

- `urn:eudi:sca:payment:1` → `payload.payee.name`, `payload.amount_display`, else
  `payload.amount` (number) + `payload.currency`
- `payment_details` → top-level `payee_name`, `payment_amount`, `payment_currency`
- anything else → top-level `merchant_name`, `amount`, `additional_info`

The wallet's PaSO type is `urn:paso:sca:global:payment:1`
(`TransactionData.PasoPayment.TYPE`), which falls into the third branch where none of
those top-level keys exist — yielding a payment entry with a null merchant name and null
amount.

It cannot simply be folded into the EUDI branch either. `TransactionData.parsePasoPayment`
shows PaSO carries `payload.amount` as a **single display-ready string** (`"56.66 EUR"`,
split on the last space by `splitIsoCurrencyAmount`), with no `amount_display` and no
separate `payload.currency`. The EUDI branch would call `cJSON_GetNumberValue` on that
string (yielding `0.0`) and then `sprintf("%s %f", currency, amount)` with
`currency == NULL` — undefined behaviour on a NULL `%s`.

**Consequence:** a distinct six-line branch, carried as a local patch.

### 5. Host has Podman, not Docker

`docker` is not on `PATH`; Podman 5.7.1 with a running `libkrun` arm64 machine is. A
plain `Dockerfile` built via `podman build` satisfies the requirement. wasi-sdk 33.0 (the
latest stable release; 34 is still RC) publishes an `arm64-linux` tarball, so the
toolchain runs natively in that machine.

### 6. Selected-entry plumbing is unaffected

`DcPresentationActivity` consumes only `getRequest.selectedEntryId`, never the matcher's
entry metadata JSON. The C matcher passes the credential store's `id` to
`AddEntryToSet`, so the selected id remains `credential.id`. Credential ids are
`UUID.randomUUID().toString()` (36 chars), inside `DigitalCredentialEntry`'s 64-char cap.

## Architecture

Three units, each independently reviewable. The first two both live under `matcher/`:

```text
matcher/                  vendored upstream C + local patches + container toolchain
scripts/build-matcher.sh  host wrapper: build image, run build, install asset, verify
dcapi/DcRegistryBlobBuilder.kt   Credential -> androidx entries -> stock blob bytes
dcapi/DcRegistrySync.kt   unchanged structure; new asset name, new builder
```

### Unit 1: the toolchain container

```text
matcher/
  Dockerfile             toolchain image; no host toolchain required
  build.sh               runs inside container: patch -> compile -> test -> strip -> hash
  strip_exports.py       wasm export-section filter and assertion
  upstream/              CMWallet matcher sources, byte-identical, @ 9407802
  patches/               local deltas, applied at build time
  UPSTREAM.md            provenance and delta rationale, in prose
  PROVENANCE             machine-readable: upstream SHA, wasi-sdk version, output sha256
```

**Image.** `debian:bookworm-slim` pinned by digest, plus:

- wasi-sdk **33.0**, selected by `ARG TARGETARCH` (`arm64-linux` / `x86_64-linux`) and
  verified against a sha256 baked into the Dockerfile. Debian packages no wasi-sdk;
  pinning the tarball is what makes the build reproducible.
- `make`, `g++`, `doctest-dev`, `nlohmann-json3-dev` — upstream's `Makefile` builds a
  **native** doctest runner and its include flags (`-I/usr/include/doctest`,
  `-I/usr/include/nlohmann`) match Debian's layout exactly. The same image therefore
  cross-compiles the wasm and runs upstream's test suite.
- `python3`, for the export filter.

Network is used only at image-build time; compilation runs offline.

**Why not upstream's CMake.** `CMakeLists.txt` declares
`add_library(openid4vp1_0 openid4vp1_0.c dcql.c ${COMMON_LIB_SRCS})` with
`SUFFIX ".wasm"` — an archive target, not a loadable module with a `_start`/`main` entry
point. The build links an executable module explicitly:

```text
"$WASI_SDK_PATH"/bin/clang --target=wasm32-wasi -Os -flto -Wl,--strip-all \
  -o openid4vp1_0.wasm \
  openid4vp1_0.c dcql.c base64.c credentialmanager.c cJSON/cJSON.c
```

The source list is taken from the CMake target (`openid4vp1_0.c dcql.c` plus
`COMMON_LIB_SRCS` = `cJSON/cJSON.c credentialmanager.c base64.c`), so upstream's
composition is tracked even though its build system is not used. The flag set is a
starting point; the import/export surface diff decides the final flags.

**Export filtering.** `build.sh` runs `strip_exports.py` (lifted unchanged in behaviour
from `scripts/build-matcher.sh`) and then asserts the surviving export set equals
`{memory, _start, main}`, failing the build rather than shipping a binary that would make
the wallet vanish from the picker.

**Host wrapper.** `scripts/build-matcher.sh` is rewritten: no more cargo or
`../dcapi-matcher`, a `MATCHER_ENGINE` variable defaulting to `podman` and accepting
`docker`, and a `--verify` mode that rebuilds and compares the sha256 against
`matcher/PROVENANCE`, exiting non-zero on mismatch.

**Limitation, stated plainly.** Byte-identical rebuilds hold only for a fixed image.
That is why the base image is pinned by digest and the wasi-sdk tarball by checksum. If
either moves, the sha256 changes for reasons unrelated to the C source and `--verify`
reports it. Noisy is the intended failure mode.

### Unit 2: vendoring and the local delta

**What is vendored.** Everything under CMWallet's `matcher/` except `pnv/` (the
person-not-verified variant, irrelevant here) and `.DS_Store`. `issuance/` comes along
because the deferred issuance target needs it and it costs nothing. `index.md` (the
registry format spec) and `test_plan.md` come along because they are what makes this code
reviewable by someone who did not do this work.

Upstream's `CMakeLists.txt` is vendored verbatim but is not used, and dropping `pnv/`
means it no longer configures cleanly. `UPSTREAM.md` says so explicitly.

**Verbatim plus patches, not edit-in-place.** `build.sh` copies `upstream/` to a scratch
directory and applies every patch in `patches/` in order, checking each with
`git apply --check` first and **failing the build on a patch that does not apply
cleanly**. Re-vendoring a newer upstream is then: replace `upstream/`, re-run, and either
it applies or the failure names precisely where upstream moved. `git diff` against
CMWallet stays literally the patch set.

The trade-off: a patch file can rot in a way an in-tree edit cannot, because a reviewer
reading `openid4vp1_0.c` will not see our change. Mitigations are that `UPSTREAM.md`
documents the delta in prose and the build refuses to proceed on a failed apply — it rots
loudly at build time, not silently at request time.

**`patches/0001-paso-sca-payment.patch`** adds a branch beside the EUDI one:

```c
} else if (transaction_data_type != NULL &&
           strcmp(transaction_data_type, "urn:paso:sca:global:payment:1") == 0) {
    cJSON *payload = cJSON_GetObjectItem(transaction_data, "payload");
    merchant_name = cJSON_GetStringValue(
        cJSON_GetObjectItem(cJSON_GetObjectItem(payload, "payee"), "name"));
    /* PaSO carries amount and currency in one display-ready string: "56.66 EUR". */
    transaction_amount = cJSON_GetStringValue(cJSON_GetObjectItem(payload, "amount"));
    additional_info = cJSON_GetStringValue(cJSON_GetObjectItem(payload, "additional_info"));
}
```

No `malloc`, no `sprintf` — strictly safer than the branch beside it, because PaSO's
amount needs no formatting. `additional_info` reads from `payload` (where
`MatcherPackageBuilder` declares it today) with a top-level fallback for verifiers that
place it beside `type`.

**`patches/0002-paso-test-case.patch`** adds `TC41_ExtractPasoPayment_{request,expected}.json`
and its registration in `test_runner.cc`, modelled on `TC32_ExtractPaymentSca1`. Code
delta and its test travel together and are both checked at build time.

**Sync policy.** `PROVENANCE` records the CMWallet remote, commit `9407802`, the vendored
file list, wasi-sdk `33.0`, and the output sha256. Re-vendoring is a deliberate committed
act with its own diff, never an implicit `git pull` of a sibling checkout.

**Upstreaming.** The PaSO branch is a clean candidate for a CMWallet pull request.
`UPSTREAM.md` records that as the intended end state, so the delta is understood as
temporary rather than permanent divergence.

### Unit 3: the Kotlin blob builder

**Dependency changes** (`gradle/libs.versions.toml`, `app/build.gradle.kts`):

| Artifact | From | To |
| --- | --- | --- |
| `credentialsRegistry` (`registry-provider`, `registry-provider-play-services`) | `1.0.0-alpha04` | `1.0.0-alpha05` |
| `registry-digitalcredentials-{mdoc,sdjwtvc,openid}` | — | `1.0.0-alpha05` (new) |
| `androidx.credentials:credentials` | `1.6.0-rc01` | `1.7.0-alpha03` |

`registry-provider:1.0.0-alpha05` depends on `credentials:1.7.0-alpha03`. That bump is
pinned **explicitly** in the version catalog so it appears in a diff rather than being
resolved silently. It is the riskiest part of this change: it moves the library beneath
`DcPresentationActivity` (`PendingIntentHandler`, `signingInfoCompat`,
`biometricPromptResult`, `ExperimentalDigitalCredentialApi`) and `DcIssuanceActivity`.

**`DcRegistryBlobBuilder` replaces `MatcherPackageBuilder`**, at the same call site in
`DcRegistrySync.register`:

```text
Credential(SdJwtVc) -> SdJwtEntry(
    verifiableCredentialType = SdJwtVctExtractor.extract(payload),
    claims = flatten(SdJwtClaimsReconstructor.reconstruct(payload)),
    entryDisplayPropertySet = setOf(VerificationEntryDisplayProperties(
        title = displayName, subtitle = issuerId, icon = launcherIcon())),
    id = credential.id)

Credential(MsoMdoc) -> MdocEntry(
    docType = credential.configurationId,
    fields = IssuerNamespaces.fromDataItem(..).flatMap { MdocField(ns, id, cborToJson(v), display) },
    entryDisplayPropertySet = same,
    id = credential.id)

OpenId4VpRegistry(entries, REGISTRY_ID, supportedProtocols =
    [PROTOCOL_OPENID4VP_1_0_UNSIGNED, _SIGNED, _MULTISIGNED]).credentials
  -> CustomMatcherRegistry(id, credentialsJson = those bytes, matcherWasm = openid4vp1_0.wasm)
```

The valuable parts of `MatcherPackageBuilder` survive as helpers: the
`SdJwtClaimsReconstructor` usage, the strict `IssuerNamespaces` decode (including its
deliberate refusal to advertise a credential whose `IssuerSignedItem.random` is not a
`bstr`, because the presentation path could not produce a `DeviceResponse` for it), and
`cborToJson`.

Deleted: the bespoke `PackageConfig` scaffolding — `default_id_prefix`, `openid4vp`,
`dcql.credential_set_option_mode`, `dcql.ts12_prefixes`, `payment_sca`, `log_level`,
per-entry `transaction_data_types`, `holder_binding`, `vcts`, `fields`. The stock blob has
no counterpart for any of it; the C matcher reads transaction-data types from hardcoded
comparisons. That configurability is the price of this approach, paid once in
`patches/0001`.

**Known unknowns, resolved by inspection rather than assumption:**

1. `SdJwtClaim(path, value: Any, ..)` and `MdocField(.., fieldValue: Any, ..)` accept
   `Any`, and which runtime types the serialiser handles for non-scalars is unverified.
   Today's builder treats arrays as leaves. Plan: pass scalars natively, decode the blob
   we actually produce, and fall back to a stringified value only if arrays or nested
   objects are rejected or mangled. The blob decode test settles this.
2. `SdJwtClaim.isSelectivelyDisclosable` has no counterpart in `index.md`'s `paths`
   schema, so its blob-level effect is unclear. Set it truthfully from the disclosure
   scan rather than hardcoding, and confirm from the decoded blob whether it changes
   anything.

**Unchanged.** `DcPresentationActivity` needs no edit (finding 6). `CustomMatcherRegistry`
stays as-is. `DcRegistrySync` keeps its eager initial push, 250 ms debounce, and
`registerNow()`; only `MATCHER_ASSET` and the builder call change.

**Debug dump.** `dumpPackageForDebug` currently writes UTF-8 JSON and chunk-logs it. The
blob is now binary, so it writes `dcapi_package.bin` and logs the JSON tail decoded from
the little-endian offset header. This is the tool that explains *why* a credential did not
match, so it must keep working.

**Testability.** Entry construction is pure apart from `Bitmap` and `android.util.Base64`,
so the mapping lives in a helper taking already-decoded inputs, unit-tested on the JVM per
the `isReturnDefaultValues` guidance in AGENTS.md. The byte assembly is androidx's code,
verified by decoding rather than re-implementation.

## Verification

Cheapest first; each rung gates the next.

1. **Upstream's test suite on the patched tree.** `make test` in the container runs the
   doctest runner (38 cases per upstream's `test_plan.md`, driven by the
   request/expected pairs in `testdata/`). Gate: all green *after* patches apply —
   that is what proves the PaSO branch did not disturb the EUDI or `payment_details`
   paths. Includes the new `TC41` PaSO case.
2. **Wasm surface diff.** Compare our output's imports (`credman`, `credman_v2`,
   `credman_v5`, `wasi_snapshot_preview1`) and post-strip exports
   (`memory`, `_start`, `main`) against CMWallet's committed `openid4vp1_0.wasm`. Build
   fails on divergence. **Size is deliberately not a gate** — upstream's 70 KB comes from
   an unknown wasi-sdk and flag set. The surface is what Credman contracts on.
3. **JVM unit tests.** The mapping helper (vct extraction, claim-path flattening, mdoc
   namespace and element mapping), plus a **blob decode test**: read the little-endian
   offset, slice the icon bytes, parse the JSON, assert that
   `credentials["dc+sd-jwt"][vct][0].paths` nests as `index.md` specifies and that
   `supported_protocols` is present. If the serialiser touches the `Bitmap`, this becomes
   an `androidTest` instead; the implementation reports which it landed as.
4. **On-device smoke** — the only rung that proves the platform accepts the binary.
   Install debug, confirm the registration log line, then drive real verifiers
   (`https://digital-credentials.dev/` is CMWallet's demo verifier; the local
   `../cli-verifier` and `eudipay-merchant-mock` cover PaSO). Checks: the wallet appears
   in the picker; SD-JWT match; mdoc match; claim names rendered; selection returns the
   expected `selectedEntryId`; PaSO renders merchant and amount. Failure signatures to
   watch by name: `IllegalArgumentException: Unknown export` means the export filter
   regressed; silent absence from the picker means a blob or protocol mismatch.
5. **`gradle :app:assembleRelease`** — the R8 gate for three new artifacts, per AGENTS.md.

Expected JVM unit-test baseline remains the documented one: a green run is 56 tests with
the two `TransactionDataTest` `android.util.Base64` failures, plus whatever this change
adds.

## Assets and documentation

- Add `app/src/main/assets/openid4vp1_0.wasm`; delete `app/src/main/assets/dcapi_matcher.wasm`.
- `DcRegistrySync.MATCHER_ASSET` becomes `openid4vp1_0.wasm`. Its KDoc currently explains
  why we avoid `OpenId4VpRegistry`; that rationale inverts and must be rewritten.
- `CustomMatcherRegistry` and `MatcherPackageBuilder` KDoc references to
  `aptitude_consortium_dcapi_matcher.wasm` and `PackageConfig` are removed with the code.
- README: update the asset list and the presentation feature bullet.
- AGENTS.md: add a note covering `matcher/`, the podman invocation, the export allowlist,
  and **the alpha05 requirement** — the last is the trap most likely to cost the next
  person a day.
- Remove `../dcapi-matcher` references from README, AGENTS.md, and `scripts/`.

## Rollback

The dependency bump, the blob builder, the asset swap, and the `MATCHER_ASSET` flip must
land together. An alpha04 blob against the C matcher matches nothing (finding 1), and the
Rust matcher against an alpha05 blob also matches nothing. **There is no valid
intermediate state**, so this ships as one coherent commit series on a branch, revertable
as a unit. Given it touches the DC API core, the work belongs in a git worktree.

## The retired Rust matcher

`../dcapi-matcher` is simply no longer referenced. That repository is not modified. README
and `matcher/UPSTREAM.md` record that the Rust matcher was retired for parity, and that
its PaSO configurability now lives in `patches/0001`.
