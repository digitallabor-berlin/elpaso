# CMWallet OpenID4VP 1.0 Matcher Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the wallet's custom Rust DC API presentation matcher with CMWallet's reference C matcher, compiled in-house from vendored source in a Podman-built wasi-sdk container, and feed it the stock androidx registry blob instead of a bespoke JSON payload.

**Architecture:** A new top-level `matcher/` directory holds CMWallet's C sources verbatim plus local deltas as patch files, a `Dockerfile` describing a pinned wasi-sdk toolchain, and a `build.sh` that patches, compiles, runs upstream's native test suite, filters wasm exports, and hashes the artifact. `scripts/build-matcher.sh` drives that container from the host and installs the result into `app/src/main/assets/`. On the Kotlin side, a pure mapper turns each `Credential` into androidx `SdJwtClaim`/`MdocField` lists, and an Android-side builder assembles `OpenId4VpRegistry`, whose `credentials` bytes are handed with our wasm to the existing `CustomMatcherRegistry`.

**Tech Stack:** C (cJSON), wasi-sdk 33.0 clang, Podman, Debian bookworm, doctest + nlohmann/json, Kotlin, androidx.credentials.registry 1.0.0-alpha05, Gradle (no wrapper — use system `gradle`).

**Spec:** `docs/superpowers/specs/2026-08-21-cmwallet-matcher-adoption-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Upstream pin:** CMWallet commit `9407802`, sources at `/Users/senexi/dev/eudiw/CMWallet/matcher/`.
- **Toolchain pin:** wasi-sdk `33.0`. Never a `-rc` release.
- **Wasm export surface must be exactly** `{memory, _start, main}`. Anything else causes `IllegalArgumentException: Unknown export` at request time and the wallet silently disappears from the system picker.
- **Wasm import surface must equal** CMWallet's shipped `openid4vp1_0.wasm` — modules `credman`, `credman_v2`, `credman_v5`, `wasi_snapshot_preview1`.
- **Dependency floors:** `androidx.credentials.registry:*` at `1.0.0-alpha05` (alpha04 emits no `supported_protocols` and the matcher then matches nothing); `androidx.credentials:credentials` at `1.7.0-alpha03`.
- **PaSO transaction_data type string:** `urn:paso:sca:global:payment:1` (exact).
- **Registry id stays** `elpaso-openid4vp-v1`.
- **Container engine:** `podman` by default; `docker` accepted via `MATCHER_ENGINE`. There is no `docker` on this host.
- **No `./gradlew`.** Use the system `gradle` binary. Fastest signal is `gradle :app:compileDebugKotlin`.
- **JVM unit-test baseline:** a green run is 56 tests with exactly 2 failures, both in `TransactionDataTest` (`hashEntry produces a 43-char base64url SHA-256`, `parse PaymentData picks up payee and amount fields`), which fail because they call `android.util.Base64`. Do not "fix" them. New tests add to the 56.
- **`assembleRelease` is the only R8 gate.** Required before this branch is considered done.
- **No new user-facing strings** in this plan. If a task ever adds one, it must go into all three of `values/`, `values-de/`, `values-fr/` `strings.xml` or `StringsParityTest` fails.
- **Escape apostrophes** as `\'` in any Android string resource.
- **KDoc must never contain a literal `*/`.** Write `values-xx`, not the glob form.
- **No partial rollout.** Tasks 5–7 must all land before the branch is functional; an alpha04 blob against the C matcher matches nothing, and the Rust matcher against an alpha05 blob also matches nothing. Work on a branch; land as a unit.

---

## File Structure

**New — container toolchain and vendored source (`matcher/`):**

| Path | Responsibility |
| --- | --- |
| `matcher/upstream/` | CMWallet C sources, byte-identical, never edited |
| `matcher/patches/0001-paso-sca-payment.patch` | PaSO transaction_data branch |
| `matcher/patches/0002-paso-test-case.patch` | PaSO test case + its registration |
| `matcher/Dockerfile` | wasi-sdk toolchain image definition |
| `matcher/build.sh` | in-container: patch → compile → test → strip → hash |
| `matcher/strip_exports.py` | filter wasm export section to the allowlist, assert result |
| `matcher/wasm_surface.py` | dump a wasm's import/export surface as JSON |
| `matcher/reference-surface.json` | upstream's surface, the diff target |
| `matcher/base-image.txt` | resolved `debian:bookworm-slim@sha256:…` pin |
| `matcher/wasi-sdk.sha256` | checksum line for the wasi-sdk tarball |
| `matcher/PROVENANCE` | upstream SHA, toolchain version, artifact sha256 |
| `matcher/UPSTREAM.md` | provenance and delta rationale in prose |

**Modified — host tooling and app:**

| Path | Change |
| --- | --- |
| `scripts/build-matcher.sh` | rewritten: podman instead of cargo, `--verify` mode |
| `app/src/main/assets/openid4vp1_0.wasm` | new committed artifact |
| `app/src/main/assets/dcapi_matcher.wasm` | deleted |
| `gradle/libs.versions.toml` | version bumps + three new artifact aliases |
| `app/build.gradle.kts` | three new `implementation` lines |
| `.../dcapi/DcRegistryEntryMapper.kt` | new, pure: payload → claims/fields/vct/docType |
| `.../dcapi/DcRegistryBlobBuilder.kt` | new, Android: entries → `OpenId4VpRegistry` bytes |
| `.../dcapi/MatcherPackageBuilder.kt` | deleted |
| `.../dcapi/DcRegistrySync.kt` | new asset name, new builder, binary debug dump |
| `.../dcapi/CustomMatcherRegistry.kt` | KDoc only |
| `scripts/verify-registry-blob.py` | new: validate a pulled blob against `index.md` |
| `README.md`, `AGENTS.md` | asset list, build quirks, alpha05 trap |

**Why the Kotlin side is two files.** The claim/field mapping is pure and must be JVM-unit-testable, but `VerificationEntryDisplayProperties` requires a real `Bitmap`, and this project has no `androidTest` source set and no Robolectric. Splitting the pure mapping out of the Android-touching assembly is what makes the valuable half testable at all.

---

### Task 1: Vendor upstream sources

**Files:**

- Create: `matcher/upstream/` (copied tree)
- Create: `matcher/UPSTREAM.md`
- Create: `matcher/PROVENANCE`
- Test: verification is a `diff -r` against the source tree (no code test at this stage)

**Interfaces:**

- Consumes: nothing.
- Produces: `matcher/upstream/{openid4vp1_0.c,dcql.c,dcql.h,base64.c,base64.h,credentialmanager.c,credentialmanager.h,icon.h,cJSON/cJSON.c,cJSON/cJSON.h,test_runner.cc,Makefile,CMakeLists.txt,index.md,test_plan.md,testdata/*,issuance/*}` — every later task compiles or patches these exact paths.

- [ ] **Step 1: Confirm the upstream pin**

```bash
cd /Users/senexi/dev/eudiw/CMWallet && git rev-parse HEAD && git status --short | head
```

Expected: `9407802…` and a clean-enough tree. If HEAD differs, **stop and report** — the plan's pin is wrong and every hash downstream changes.

- [ ] **Step 2: Copy the tree, excluding `pnv/` and macOS cruft**

```bash
cd /Users/senexi/dev/eudiw/elpaso
mkdir -p matcher
rsync -a --delete \
  --exclude 'pnv/' --exclude '.DS_Store' --exclude '.gitignore' \
  /Users/senexi/dev/eudiw/CMWallet/matcher/ matcher/upstream/
find matcher/upstream -type f | sort
```

Expected: `openid4vp1_0.c`, `dcql.c/h`, `base64.c/h`, `credentialmanager.c/h`, `icon.h`, `cJSON/`, `Makefile`, `CMakeLists.txt`, `test_runner.cc`, `testharness.c`, `index.md`, `test_plan.md`, `request.json`, `testcreds.json`, `testdata/` (60+ files), `issuance/`. No `pnv/`.

- [ ] **Step 3: Prove the copy is verbatim**

```bash
diff -r --exclude 'pnv' --exclude '.DS_Store' --exclude '.gitignore' \
  /Users/senexi/dev/eudiw/CMWallet/matcher matcher/upstream && echo VERBATIM_OK
```

Expected: `VERBATIM_OK`. Any diff means the copy is wrong; fix before continuing.

- [ ] **Step 4: Write `matcher/UPSTREAM.md`**

```markdown
# Vendored CMWallet matcher

## What this is

`upstream/` is a byte-identical copy of the `matcher/` directory from CMWallet
(https://github.com/digitalcredentialsdev/CMWallet) at commit `9407802`, excluding
`pnv/` (the person-not-verified variant, which this wallet does not use) and
`.DS_Store`/`.gitignore`.

**Never edit `upstream/` directly.** Local changes live in `patches/` and are applied
to a scratch copy at build time by `build.sh`. That keeps `git diff` against CMWallet
equal to the patch set, so re-vendoring a newer upstream is a mechanical operation:
replace `upstream/`, re-run the build, and either the patches apply or the failure
names exactly where upstream moved.

## Local deltas

- `patches/0001-paso-sca-payment.patch` — adds a `transaction_data.type` branch for
  `urn:paso:sca:global:payment:1`. Upstream handles `urn:eudi:sca:payment:1`,
  `payment_details`, and a generic top-level shape; PaSO matches none of them, so
  without this patch a PaSO payment renders with a null merchant name and null amount.
  PaSO cannot reuse the EUDI branch because it carries amount and currency in one
  display-ready string (`"56.66 EUR"`), whereas the EUDI branch expects a numeric
  `payload.amount` plus a separate `payload.currency`.
- `patches/0002-paso-test-case.patch` — the covering test case for the above, in
  upstream's own doctest harness.

Both are candidates for an upstream pull request. That is the intended end state; the
delta is meant to be temporary.

## What this replaced

The wallet previously shipped a custom Rust matcher built from the sibling
`../dcapi-matcher` crate (447 KB, a bespoke `PackageConfig` JSON payload). It was retired
for parity with the platform default: credential sets, signed and multisigned requests,
and inline issuance entries are all already implemented upstream. Its one capability that
upstream lacked — PaSO payment rendering, which it drove from a configurable
`payment_sca` path mapping — now lives in `patches/0001` instead. That sibling repository
is untouched and simply no longer referenced.

## Things that will surprise you

- **`CMakeLists.txt` is vendored but not used.** Its wasm targets are `add_library`
  declarations, which produce archives rather than loadable modules with a `_start`
  entry point. It also references `pnv/`, which we excluded, so it no longer configures.
  `build.sh` links with `clang` directly, using the same source list as the CMake target.
- **`Makefile` *is* used**, for the native doctest suite (`make test`).
- **`testcreds.json` and `request.json` at the root are stale.** They use an older
  registry shape (`title`/`namespaces`) read by `testharness.c`. The current format is
  `testdata/registry.json` (`display`/`paths`), which `test_runner.cc` uses. Ignore the
  root pair.
- **`testdata/registry.json` carries a top-level `supported_protocols` array.** That key
  is what `openid4vp1_0.c::main` iterates. It only appears in
  `androidx.credentials.registry:registry-digitalcredentials-openid` from
  `1.0.0-alpha05` onward — see the design doc, finding 1.
```

- [ ] **Step 5: Write `matcher/PROVENANCE`**

The artifact hash is deliberately absent here; Task 3 adds it once an artifact exists.

```text
# Provenance of app/src/main/assets/openid4vp1_0.wasm
# Regenerate and verify with: bash scripts/build-matcher.sh --verify

upstream_repo    = https://github.com/digitalcredentialsdev/CMWallet
upstream_commit  = 9407802
upstream_subdir  = matcher/
upstream_excludes= pnv/ .DS_Store .gitignore
patches          = 0001-paso-sca-payment.patch 0002-paso-test-case.patch
wasi_sdk_version = 33.0
base_image       = see matcher/base-image.txt
export_allowlist = memory _start main
```

- [ ] **Step 6: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add matcher/upstream matcher/UPSTREAM.md matcher/PROVENANCE
git commit -m "chore(matcher): vendor CMWallet matcher sources at 9407802

Verbatim copy of CMWallet matcher/ minus pnv/. Local deltas will live in
patches/ so git diff against upstream stays equal to the patch set."
```

---

### Task 2: Toolchain container that builds unpatched upstream

Deliverable: a wasm built from unmodified vendored source whose import/export surface matches CMWallet's shipped binary. Nothing about PaSO or Kotlin yet — if this task fails, everything after it is wasted.

**Files:**

- Create: `matcher/wasm_surface.py`
- Create: `matcher/strip_exports.py`
- Create: `matcher/reference-surface.json`
- Create: `matcher/base-image.txt`
- Create: `matcher/wasi-sdk.sha256`
- Create: `matcher/Dockerfile`
- Create: `matcher/build.sh`
- Test: `matcher/reference-surface.json` is the assertion target; `build.sh` fails the build on mismatch

**Interfaces:**

- Consumes: `matcher/upstream/**` from Task 1.
- Produces: `matcher/build.sh` accepting env `TARGETS` (default `openid4vp1_0`) and writing `/out/openid4vp1_0.wasm` plus `/out/openid4vp1_0.wasm.sha256`; `matcher/wasm_surface.py` CLI `python3 wasm_surface.py FILE.wasm` printing JSON `{"imports": [...], "exports": [...]}` with sorted `"module.field"` import strings and sorted export name strings.

- [ ] **Step 1: Write `matcher/wasm_surface.py`**

```python
#!/usr/bin/env python3
"""Dump a wasm module's import and export surface as sorted JSON.

Credman's runtime contracts on exactly two things: which host functions the module
imports, and which symbols it exports (the allowlist is memory/_start/main). This
script is the machine-readable form of both, so the build can diff against upstream's
shipped binary instead of eyeballing `strings` output.
"""
import json
import sys

IMPORT_SECTION = 2
EXPORT_SECTION = 7


def leb_u(buf, i):
    """Decode an unsigned LEB128 at offset i. Returns (value, next_offset)."""
    n = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, i
        shift += 7


def read_name(buf, i):
    length, i = leb_u(buf, i)
    return buf[i:i + length].decode("utf-8"), i + length


def skip_limits(buf, i):
    flags, i = leb_u(buf, i)
    _min, i = leb_u(buf, i)
    if flags & 0x01:
        _max, i = leb_u(buf, i)
    return i


def parse_imports(body):
    i = 0
    count, i = leb_u(body, i)
    out = []
    for _ in range(count):
        module, i = read_name(body, i)
        field, i = read_name(body, i)
        kind = body[i]
        i += 1
        if kind == 0x00:      # function: typeidx
            _t, i = leb_u(body, i)
        elif kind == 0x01:    # table: elemtype + limits
            i += 1
            i = skip_limits(body, i)
        elif kind == 0x02:    # memory: limits
            i = skip_limits(body, i)
        elif kind == 0x03:    # global: valtype + mutability
            i += 2
        else:
            raise ValueError(f"unknown import kind {kind}")
        out.append(f"{module}.{field}")
    return out


def parse_exports(body):
    i = 0
    count, i = leb_u(body, i)
    out = []
    for _ in range(count):
        name, i = read_name(body, i)
        i += 1               # kind
        _idx, i = leb_u(body, i)
        out.append(name)
    return out


def surface(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x00asm":
        raise ValueError(f"{path} is not a wasm module")
    i = 8
    imports, exports = [], []
    while i < len(data):
        sid = data[i]
        i += 1
        size, i = leb_u(data, i)
        body = data[i:i + size]
        i += size
        if sid == IMPORT_SECTION:
            imports = parse_imports(body)
        elif sid == EXPORT_SECTION:
            exports = parse_exports(body)
    return {"imports": sorted(imports), "exports": sorted(exports)}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: wasm_surface.py FILE.wasm", file=sys.stderr)
        raise SystemExit(2)
    print(json.dumps(surface(sys.argv[1]), indent=2))
```

- [ ] **Step 2: Run it against upstream's shipped binary to capture the reference**

```bash
cd /Users/senexi/dev/eudiw/elpaso
python3 matcher/wasm_surface.py \
  /Users/senexi/dev/eudiw/CMWallet/app/src/main/assets/openid4vp1_0.wasm \
  > matcher/reference-surface.json
cat matcher/reference-surface.json
```

Expected: imports include `credman.*`, `credman_v2.*`, `credman_v5.AddMetadataDisplayTextToEntrySet`, and `wasi_snapshot_preview1.*` entries; exports are a short list. **Record what the exports actually are** — if upstream exports something outside `{memory, _start, main}`, the allowlist in this plan is wrong and you must stop and report rather than "fixing" the allowlist.

- [ ] **Step 3: Write `matcher/strip_exports.py`**

Behaviour is lifted from the export-stripping block currently inlined in `scripts/build-matcher.sh`, plus a hard assertion at the end.

```python
#!/usr/bin/env python3
"""Filter a wasm module's export section down to Credman's allowlist.

Play Services' Credman runtime accepts only `memory`, `_start` and `main` as exports.
Anything else surfaces at request time as `IllegalArgumentException: Unknown export`,
and the wallet silently vanishes from the system credential picker. wasi-sdk's linker
emits extra symbols (__heap_base, __data_end, __indirect_function_table), so this runs
on every build. It rewrites the file in place and then asserts the result, so a future
linker change fails the build instead of shipping a dead matcher.
"""
import io
import sys

ALLOW = {"memory", "_start", "main"}
EXPORT_SECTION = 7


def leb_u(buf, i):
    n = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, i
        shift += 7


def leb_enc(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n == 0:
            out.append(b)
            return bytes(out)
        out.append(b | 0x80)


def strip(path):
    data = open(path, "rb").read()
    out = io.BytesIO()
    out.write(data[:8])
    i = 8
    kept_names = None
    while i < len(data):
        sid = data[i]
        i += 1
        size, i = leb_u(data, i)
        body = data[i:i + size]
        i += size
        if sid != EXPORT_SECTION:
            out.write(bytes([sid]))
            out.write(leb_enc(size))
            out.write(body)
            continue
        bi = 0
        count, bi = leb_u(body, bi)
        exports = []
        for _ in range(count):
            nl, bi = leb_u(body, bi)
            name = body[bi:bi + nl].decode()
            bi += nl
            kind = body[bi]
            bi += 1
            idx, bi = leb_u(body, bi)
            exports.append((name, kind, idx))
        kept = [e for e in exports if e[0] in ALLOW]
        dropped = [e[0] for e in exports if e[0] not in ALLOW]
        if dropped:
            print(f"strip: dropped {dropped}")
        new_body = bytearray()
        new_body.extend(leb_enc(len(kept)))
        for name, kind, idx in kept:
            new_body.extend(leb_enc(len(name)))
            new_body.extend(name.encode())
            new_body.append(kind)
            new_body.extend(leb_enc(idx))
        out.write(bytes([EXPORT_SECTION]))
        out.write(leb_enc(len(new_body)))
        out.write(bytes(new_body))
        kept_names = {e[0] for e in kept}
    open(path, "wb").write(out.getvalue())
    if kept_names is None:
        raise SystemExit("strip: no export section found")
    if not kept_names <= ALLOW:
        raise SystemExit(f"strip: unexpected exports survived: {sorted(kept_names)}")
    print(f"strip: kept {sorted(kept_names)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: strip_exports.py FILE.wasm", file=sys.stderr)
        raise SystemExit(2)
    strip(sys.argv[1])
```

- [ ] **Step 4: Pin the base image and the wasi-sdk checksum**

These are generated, not typed, so the pins are real values rather than guesses.

```bash
cd /Users/senexi/dev/eudiw/elpaso
podman pull debian:bookworm-slim
podman image inspect debian:bookworm-slim \
  --format '{{index .RepoDigests 0}}' > matcher/base-image.txt
cat matcher/base-image.txt

V=33.0
curl -sSL -o /tmp/wasi-sdk-sums \
  "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-33/SHA256SUMS"
grep -E "wasi-sdk-${V}-(arm64|x86_64)-linux\.tar\.gz" /tmp/wasi-sdk-sums \
  > matcher/wasi-sdk.sha256
cat matcher/wasi-sdk.sha256
```

Expected: `base-image.txt` holds `docker.io/library/debian@sha256:…`; `wasi-sdk.sha256` holds two lines, one per architecture. If the release has no `SHA256SUMS` asset, compute the checksums by downloading both tarballs and running `sha256sum`, and write the same two-column format (`<hash>  <filename>`).

- [ ] **Step 5: Write `matcher/Dockerfile`**

```dockerfile
# Toolchain image for building the DC API presentation matcher.
#
# Two jobs in one image: cross-compile the wasm with wasi-sdk clang, and run
# upstream's *native* doctest suite (which needs a host g++ plus doctest and
# nlohmann/json headers). Keeping both here means a single `podman run` proves
# the patched source both compiles and behaves.
#
# BASE_IMAGE is passed in from matcher/base-image.txt so the base is digest-pinned
# without a hand-typed hash in this file.
ARG BASE_IMAGE=debian:bookworm-slim
FROM ${BASE_IMAGE}

ARG TARGETARCH
ARG WASI_SDK_VERSION=33.0
ARG WASI_SDK_MAJOR=33

# bookworm is a frozen release, so apt versions are stable enough not to pin
# individually; the base image digest is the real pin.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils make g++ python3 \
        doctest-dev nlohmann-json3-dev git \
    && rm -rf /var/lib/apt/lists/*

# wasi-sdk is not packaged by Debian. Download the release tarball for the build
# architecture and verify it against the committed checksum file.
COPY wasi-sdk.sha256 /tmp/wasi-sdk.sha256
RUN set -eux; \
    case "${TARGETARCH}" in \
      arm64) ARCH=arm64 ;; \
      amd64) ARCH=x86_64 ;; \
      *) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    TARBALL="wasi-sdk-${WASI_SDK_VERSION}-${ARCH}-linux.tar.gz"; \
    curl -sSL -o "/tmp/${TARBALL}" \
      "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK_MAJOR}/${TARBALL}"; \
    grep " ${TARBALL}\$" /tmp/wasi-sdk.sha256 > /tmp/expected.sha256; \
    cd /tmp && sha256sum -c expected.sha256; \
    mkdir -p /opt/wasi-sdk; \
    tar -xzf "/tmp/${TARBALL}" -C /opt/wasi-sdk --strip-components=1; \
    rm -f "/tmp/${TARBALL}"; \
    /opt/wasi-sdk/bin/clang --version

ENV WASI_SDK_PATH=/opt/wasi-sdk

WORKDIR /work
ENTRYPOINT ["/bin/bash", "/work/build.sh"]
```

- [ ] **Step 6: Write `matcher/build.sh`**

```bash
#!/usr/bin/env bash
# Runs INSIDE the toolchain container. Mounts expected:
#   /work        this matcher/ directory (read-only is fine)
#   /out         output directory (writable)
#
# Pipeline: copy upstream -> apply patches -> compile wasm -> run native tests
#           -> strip exports -> diff surface against upstream -> hash.
#
# Every stage is a hard gate. A matcher that compiles but exports the wrong symbols
# makes the wallet vanish from the system picker with no visible error, so we would
# rather fail here.
set -euo pipefail

TARGETS="${TARGETS:-openid4vp1_0}"
SRC=/work/upstream
PATCHES=/work/patches
WORK=/tmp/matcher-src
OUT=/out

echo "== staging source =="
rm -rf "$WORK"
cp -r "$SRC" "$WORK"

echo "== applying patches =="
cd "$WORK"
git init -q .
git config user.email build@localhost
git config user.name "matcher build"
git add -A
git commit -qm "vendored upstream"
if [ -d "$PATCHES" ] && compgen -G "$PATCHES/*.patch" > /dev/null; then
    for p in "$PATCHES"/*.patch; do
        echo "-- $p"
        git apply --check "$p" || { echo "PATCH DOES NOT APPLY: $p" >&2; exit 1; }
        git apply "$p"
    done
else
    echo "-- none"
fi

COMMON_SRCS="base64.c credentialmanager.c cJSON/cJSON.c"

build_wasm() {
    local target="$1" srcs="$2"
    echo "== compiling ${target}.wasm =="
    # Source list mirrors the CMake target of the same name; we link an executable
    # module explicitly because CMake's add_library() would produce an archive with
    # no _start entry point.
    "$WASI_SDK_PATH/bin/clang" \
        --target=wasm32-wasi \
        -Os -flto \
        -Wl,--strip-all \
        -I. -IcJSON \
        -o "$OUT/${target}.wasm" \
        $srcs $COMMON_SRCS

    echo "== stripping exports =="
    python3 /work/strip_exports.py "$OUT/${target}.wasm"
}

echo "== running upstream native test suite =="
make test

for t in $TARGETS; do
    case "$t" in
        openid4vp1_0)      build_wasm openid4vp1_0 "openid4vp1_0.c dcql.c" ;;
        issuance_provision) build_wasm issuance_provision "issuance/provision.c dcql.c" ;;
        *) echo "unknown target: $t" >&2; exit 1 ;;
    esac
done

echo "== surface diff against upstream reference =="
python3 /work/wasm_surface.py "$OUT/openid4vp1_0.wasm" > "$OUT/built-surface.json"
python3 - <<'PY'
import json, sys
ref = json.load(open("/work/reference-surface.json"))
got = json.load(open("/out/built-surface.json"))
ok = True
for key in ("imports", "exports"):
    if ref[key] != got[key]:
        ok = False
        print(f"{key} MISMATCH")
        print(f"  only upstream: {sorted(set(ref[key]) - set(got[key]))}")
        print(f"  only ours:     {sorted(set(got[key]) - set(ref[key]))}")
if not ok:
    sys.exit("surface diff failed")
print("surface matches upstream:", json.dumps(got))
PY

cd "$OUT"
for t in $TARGETS; do
    sha256sum "${t}.wasm" | tee "${t}.wasm.sha256"
done
echo "== done =="
```

- [ ] **Step 7: Build the image**

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
podman build --build-arg BASE_IMAGE="$(cat base-image.txt)" \
  -t elpaso-matcher-toolchain:wasi33 .
```

Expected: image builds; the final `clang --version` line reports a wasi-sdk clang.

- [ ] **Step 8: Run the unpatched build**

At this point `patches/` does not exist, so this compiles pristine upstream.

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
mkdir -p /tmp/matcher-out
podman run --rm \
  -v "$PWD":/work:z \
  -v /tmp/matcher-out:/out:z \
  elpaso-matcher-toolchain:wasi33
ls -l /tmp/matcher-out
```

Expected: `make test` reports all assertions passing; `strip: dropped [...]` lists linker extras; `surface matches upstream: {...}`; a `openid4vp1_0.wasm` plus its `.sha256` in `/tmp/matcher-out`.

**If the surface diff fails**, adjust only the link flags in `build_wasm` (candidates: dropping `-flto`, adding `-Wl,--no-entry` — though that removes `_start` and is probably wrong, `-Wl,--allow-undefined`, `-D_WASI_EMULATED_*` for missing libc pieces) and re-run. Do **not** relax `reference-surface.json` or the allowlist to make it pass. Report what you changed.

**If `make test` fails on pristine upstream**, stop and report — that means the vendored copy or the image is wrong, not our patch.

- [ ] **Step 9: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add matcher/Dockerfile matcher/build.sh matcher/strip_exports.py \
        matcher/wasm_surface.py matcher/reference-surface.json \
        matcher/base-image.txt matcher/wasi-sdk.sha256
git commit -m "build(matcher): containerised wasi-sdk toolchain

Podman-built Debian image with wasi-sdk 33.0 plus doctest/nlohmann so one run
both cross-compiles the wasm and executes upstream's native test suite. Export
filtering and an import/export surface diff against CMWallet's shipped binary
are hard build gates: a matcher with unexpected exports makes the wallet
silently disappear from the system picker."
```

---

### Task 3: Host wrapper and first installed artifact

**Files:**

- Modify: `scripts/build-matcher.sh` (full rewrite)
- Create: `app/src/main/assets/openid4vp1_0.wasm`
- Modify: `matcher/PROVENANCE` (add the artifact hash)
- Test: `bash scripts/build-matcher.sh --verify` is the test

**Interfaces:**

- Consumes: `matcher/build.sh`, `matcher/Dockerfile` from Task 2.
- Produces: `scripts/build-matcher.sh` with two modes — default (build and install) and `--verify` (build and compare against `PROVENANCE`); env `MATCHER_ENGINE` (default `podman`), `MATCHER_IMAGE` (default `elpaso-matcher-toolchain:wasi33`), `TARGETS` (default `openid4vp1_0`).

- [ ] **Step 1: Rewrite `scripts/build-matcher.sh`**

```bash
#!/usr/bin/env bash
# Builds the DC API presentation matcher WASM in a container and vendors it into
# app/src/main/assets/.
#
# The matcher source is vendored under matcher/upstream (CMWallet, see
# matcher/UPSTREAM.md) with local deltas in matcher/patches. Everything about the
# toolchain lives in matcher/Dockerfile, so the only host requirement is a
# container engine — Podman by default, since this project's dev machines do not
# have Docker installed.
#
# Usage:
#   bash scripts/build-matcher.sh            # build and install into assets/
#   bash scripts/build-matcher.sh --verify   # build and compare against PROVENANCE
#
# Env:
#   MATCHER_ENGINE  podman (default) | docker
#   MATCHER_IMAGE   image tag to build/use
#   TARGETS         space-separated build targets (default: openid4vp1_0)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MATCHER_DIR="$APP_ROOT/matcher"
ASSET_DIR="$APP_ROOT/app/src/main/assets"
ASSET_NAME="openid4vp1_0.wasm"
PROVENANCE="$MATCHER_DIR/PROVENANCE"

ENGINE="${MATCHER_ENGINE:-podman}"
IMAGE="${MATCHER_IMAGE:-elpaso-matcher-toolchain:wasi33}"
TARGETS="${TARGETS:-openid4vp1_0}"

VERIFY=0
if [ "${1:-}" = "--verify" ]; then
    VERIFY=1
elif [ -n "${1:-}" ]; then
    echo "unknown argument: $1" >&2
    exit 2
fi

command -v "$ENGINE" >/dev/null 2>&1 || {
    echo "container engine '$ENGINE' not found; set MATCHER_ENGINE" >&2
    exit 1
}

BASE_IMAGE="$(cat "$MATCHER_DIR/base-image.txt")"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT

echo "== building toolchain image ($ENGINE) =="
"$ENGINE" build --build-arg BASE_IMAGE="$BASE_IMAGE" -t "$IMAGE" "$MATCHER_DIR"

echo "== building matcher =="
"$ENGINE" run --rm \
    -e TARGETS="$TARGETS" \
    -v "$MATCHER_DIR":/work:z \
    -v "$OUT_DIR":/out:z \
    "$IMAGE"

BUILT="$OUT_DIR/$ASSET_NAME"
[ -f "$BUILT" ] || { echo "expected $BUILT after build" >&2; exit 1; }
BUILT_SHA="$(shasum -a 256 "$BUILT" | awk '{print $1}')"
echo "built sha256: $BUILT_SHA ($(wc -c <"$BUILT") bytes)"

RECORDED_SHA="$(awk -F'= *' '/^artifact_sha256/ {print $2}' "$PROVENANCE" | tr -d '[:space:]')"

if [ "$VERIFY" = "1" ]; then
    if [ -z "$RECORDED_SHA" ]; then
        echo "PROVENANCE has no artifact_sha256 to verify against" >&2
        exit 1
    fi
    if [ "$BUILT_SHA" != "$RECORDED_SHA" ]; then
        echo "MISMATCH: built $BUILT_SHA != recorded $RECORDED_SHA" >&2
        echo "Either the source changed (update PROVENANCE deliberately) or the" >&2
        echo "toolchain/base image drifted (re-pin base-image.txt)." >&2
        exit 1
    fi
    ASSET_SHA="$(shasum -a 256 "$ASSET_DIR/$ASSET_NAME" | awk '{print $1}')"
    if [ "$ASSET_SHA" != "$RECORDED_SHA" ]; then
        echo "MISMATCH: committed asset $ASSET_SHA != recorded $RECORDED_SHA" >&2
        exit 1
    fi
    echo "verify OK: source, build and committed asset all agree"
    exit 0
fi

mkdir -p "$ASSET_DIR"
cp "$BUILT" "$ASSET_DIR/$ASSET_NAME"
echo "installed: $ASSET_DIR/$ASSET_NAME"
echo
echo "Record this in matcher/PROVENANCE:"
echo "artifact_sha256  = $BUILT_SHA"
```

- [ ] **Step 2: Run it**

```bash
cd /Users/senexi/dev/eudiw/elpaso
bash scripts/build-matcher.sh
ls -l app/src/main/assets/
```

Expected: the asset appears; the script prints an `artifact_sha256` line to record.

- [ ] **Step 3: Record the hash in `matcher/PROVENANCE`**

Append the printed line, so the file's last line reads e.g. `artifact_sha256  = <64 hex chars>`. Use the value the script printed, not a value from this plan.

- [ ] **Step 4: Verify**

```bash
cd /Users/senexi/dev/eudiw/elpaso && bash scripts/build-matcher.sh --verify
```

Expected: `verify OK: source, build and committed asset all agree`.

- [ ] **Step 5: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add scripts/build-matcher.sh app/src/main/assets/openid4vp1_0.wasm matcher/PROVENANCE
git commit -m "build(matcher): drive the container build from the host

scripts/build-matcher.sh no longer shells out to cargo against a sibling
dcapi-matcher checkout. It builds the toolchain image, runs the build, installs
the artifact, and gains a --verify mode that fails when the committed binary,
a fresh build, and PROVENANCE disagree."
```

---

### Task 4: PaSO patch and its covering test

**Files:**

- Create: `matcher/patches/0001-paso-sca-payment.patch`
- Create: `matcher/patches/0002-paso-test-case.patch`
- Modify: `app/src/main/assets/openid4vp1_0.wasm` (rebuilt)
- Modify: `matcher/PROVENANCE` (new hash)
- Test: `TC41_ExtractPasoPayment` inside upstream's doctest runner, plus the existing 38 cases as a regression net

**Interfaces:**

- Consumes: the container pipeline from Tasks 2–3.
- Produces: a matcher that renders `urn:paso:sca:global:payment:1` as a payment entry with merchant name and amount.

- [ ] **Step 1: Create a scratch working copy and a git baseline**

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
rm -rf /tmp/paso-work && cp -r upstream /tmp/paso-work
cd /tmp/paso-work
git init -q . && git add -A && git commit -qm base
```

- [ ] **Step 2: Write the failing test first — add the test case and its request**

Create `/tmp/paso-work/testdata/TC41_ExtractPasoPayment_request.json`. The `transaction_data` entry is base64url of the PaSO payload; generate it rather than hand-copying:

```bash
cd /tmp/paso-work
python3 - <<'PY'
import base64, json
td = {
    "type": "urn:paso:sca:global:payment:1",
    "payload": {
        "transaction_id": "tx-0001",
        "payee": {"name": "Merchant X", "id": "DE89370400440532013000"},
        "amount": "56.66 EUR",
        "additional_info": "Order 4711",
    },
    "credential_ids": ["mdl"],
}
enc = base64.urlsafe_b64encode(json.dumps(td).encode()).decode().rstrip("=")
req = {
    "requests": [
        {
            "data": {
                "dcql_query": {
                    "credentials": [
                        {
                            "format": "mso_mdoc",
                            "id": "mdl",
                            "meta": {"doctype_value": "org.iso.18013.5.1.mDL"},
                        }
                    ]
                },
                "transaction_data": [enc],
            },
            "protocol": "openid4vp-v1-unsigned",
        }
    ]
}
open("testdata/TC41_ExtractPasoPayment_request.json", "w").write(json.dumps(req, indent=2))
print(enc)
PY
```

Then register the case in `test_runner.cc`, immediately after the `TC34_ExtractPaymentGeneric` case (matching upstream's one-liner style):

```cpp
TEST_CASE("TC41_ExtractPasoPayment") {
    RunTest("TC41_ExtractPasoPayment");
}
```

- [ ] **Step 3: Run it to confirm it fails for the right reason**

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
podman run --rm -v /tmp/paso-work:/src:z -v "$PWD":/work:z \
  --entrypoint /bin/bash elpaso-matcher-toolchain:wasi33 \
  -c 'cd /src && make test 2>&1 | tail -40'
```

Expected: `TC41` fails — either because `testdata/TC41_ExtractPasoPayment_expected.json` does not exist yet, or with an entry whose `merchant_name` and `transaction_amount` are empty. **Confirm you see empty merchant/amount rather than a missing-file error**, because the empty values are the bug this task fixes. If the file is simply missing, generate the expected file at this stage anyway (next step's `GENERATE_TESTDATA=1`), inspect it, and confirm the nulls are visible there.

- [ ] **Step 4: Implement the PaSO branch in `openid4vp1_0.c`**

In `/tmp/paso-work/openid4vp1_0.c`, inside `process_request`, the chain reads
`if (… "urn:eudi:sca:payment:1") { … } else if (… "payment_details") { … } else { … }`.
Insert a new branch **between** the `urn:eudi:sca:payment:1` branch and the
`payment_details` branch:

```c
} else if (transaction_data_type != NULL && strcmp(transaction_data_type, "urn:paso:sca:global:payment:1") == 0) {
    /* PaSO (Payments and SCA for OpenID) carries a nested payload like EUDI TS12,
       but its amount is a single display-ready string ("56.66 EUR") rather than a
       number plus a separate currency field. Reusing the EUDI branch above would
       call cJSON_GetNumberValue on that string (yielding 0.0) and then
       sprintf("%s %f", currency, amount) with a NULL currency. */
    cJSON *payload = cJSON_GetObjectItem(transaction_data, "payload");
    merchant_name = cJSON_GetStringValue(
        cJSON_GetObjectItem(cJSON_GetObjectItem(payload, "payee"), "name"));
    transaction_amount = cJSON_GetStringValue(cJSON_GetObjectItem(payload, "amount"));
    additional_info = cJSON_GetStringValue(cJSON_GetObjectItem(payload, "additional_info"));
    if (additional_info == NULL) {
        additional_info = cJSON_GetStringValue(
            cJSON_GetObjectItem(transaction_data, "additional_info"));
    }
```

- [ ] **Step 5: Generate the expected fixture and read it before trusting it**

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
podman run --rm -v /tmp/paso-work:/src:z \
  --entrypoint /bin/bash elpaso-matcher-toolchain:wasi33 \
  -c 'cd /src && make test_runner && GENERATE_TESTDATA=1 ./test_runner -tc=TC41_ExtractPasoPayment'
cat /tmp/paso-work/testdata/TC41_ExtractPasoPayment_expected.json
```

Expected in the generated fixture: entries of `"type": "Payment"` with
`"merchant_name": "Merchant X"`, `"transaction_amount": "56.66 EUR"`,
`"additional_info": "Order 4711"`. **If any of those three is empty, the branch is
not being reached** — check that the new `else if` sits in the same chain and that the
type string matches exactly. Do not commit a fixture that encodes the bug.

- [ ] **Step 6: Run the whole suite**

```bash
cd /Users/senexi/dev/eudiw/elpaso/matcher
podman run --rm -v /tmp/paso-work:/src:z \
  --entrypoint /bin/bash elpaso-matcher-toolchain:wasi33 \
  -c 'cd /src && make test 2>&1 | tail -20'
```

Expected: all cases pass, including `TC32_ExtractPaymentSca1`, `TC33_ExtractPaymentDetails`, `TC34_ExtractPaymentGeneric` — that is the evidence the new branch did not steal traffic from the existing ones.

- [ ] **Step 7: Extract the two patches**

```bash
cd /tmp/paso-work
mkdir -p /Users/senexi/dev/eudiw/elpaso/matcher/patches
git diff -- openid4vp1_0.c \
  > /Users/senexi/dev/eudiw/elpaso/matcher/patches/0001-paso-sca-payment.patch
git add -A testdata test_runner.cc
git diff --cached -- test_runner.cc testdata \
  > /Users/senexi/dev/eudiw/elpaso/matcher/patches/0002-paso-test-case.patch
wc -l /Users/senexi/dev/eudiw/elpaso/matcher/patches/*.patch
```

Expected: `0001` touches only `openid4vp1_0.c`; `0002` touches `test_runner.cc` and adds the two `testdata` files.

- [ ] **Step 8: Rebuild through the real pipeline**

```bash
cd /Users/senexi/dev/eudiw/elpaso
bash scripts/build-matcher.sh
```

Expected: patches apply, `make test` green, surface still matches upstream, new `artifact_sha256` printed. Record the new hash in `matcher/PROVENANCE`, replacing the previous value.

- [ ] **Step 9: Verify and commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
bash scripts/build-matcher.sh --verify
git add matcher/patches matcher/PROVENANCE app/src/main/assets/openid4vp1_0.wasm
git commit -m "feat(matcher): render PaSO SCA payments in the system selector

Upstream handles urn:eudi:sca:payment:1, payment_details and a generic
top-level shape. PaSO's urn:paso:sca:global:payment:1 matched none, so a PaSO
payment rendered with a null merchant name and amount. PaSO cannot reuse the
EUDI branch: its amount is one display-ready string, not a number plus a
separate currency.

Carried as patches over verbatim upstream, with a covering test case in
upstream's own doctest harness."
```

---

### Task 5: Dependency bump (compile-only gate)

No behaviour change. The point is to isolate the alpha bump's fallout from the blob rewrite, so a compile break here is unambiguous.

**Files:**

- Modify: `gradle/libs.versions.toml:23-24`, `gradle/libs.versions.toml:107-108`
- Modify: `app/build.gradle.kts:133-136`
- Test: `gradle :app:compileDebugKotlin` and `gradle :app:testDebugUnitTest`

**Interfaces:**

- Consumes: nothing from earlier tasks.
- Produces: dependency aliases `libs.androidx.credentials.registry.digitalcredentials.mdoc`, `…sdjwtvc`, `…openid`, and the types `androidx.credentials.registry.digitalcredentials.sdjwt.{SdJwtEntry,SdJwtClaim}`, `…mdoc.{MdocEntry,MdocField}`, `…openid4vp.OpenId4VpRegistry` for Task 6.

- [ ] **Step 1: Bump versions**

In `gradle/libs.versions.toml`, replace lines 23–24:

```toml
credentials = "1.7.0-alpha03"
credentialsRegistry = "1.0.0-alpha05"
```

- [ ] **Step 2: Add the three artifact aliases**

In `gradle/libs.versions.toml`, after the existing `androidx-credentials-registry-provider-play-services` line:

```toml
# Blob serialisers for the stock Digital Credentials registry format. The vendored
# CMWallet matcher reads exactly this layout (4-byte little-endian JSON offset, icon
# bytes, then JSON) — matcher/upstream/index.md names these classes as its source of
# truth, so we let them build the bytes instead of hand-rolling the format.
# alpha05 is a hard floor: alpha04 emits no `supported_protocols` key, and without it
# the matcher's protocol loop never runs and nothing matches.
androidx-credentials-registry-digitalcredentials-mdoc = { module = "androidx.credentials.registry:registry-digitalcredentials-mdoc", version.ref = "credentialsRegistry" }
androidx-credentials-registry-digitalcredentials-sdjwtvc = { module = "androidx.credentials.registry:registry-digitalcredentials-sdjwtvc", version.ref = "credentialsRegistry" }
androidx-credentials-registry-digitalcredentials-openid = { module = "androidx.credentials.registry:registry-digitalcredentials-openid", version.ref = "credentialsRegistry" }
```

- [ ] **Step 3: Wire them into the app**

In `app/build.gradle.kts`, after `implementation(libs.androidx.credentials.registry.provider.play.services)`:

```kotlin
    implementation(libs.androidx.credentials.registry.digitalcredentials.mdoc)
    implementation(libs.androidx.credentials.registry.digitalcredentials.sdjwtvc)
    implementation(libs.androidx.credentials.registry.digitalcredentials.openid)
```

- [ ] **Step 4: Compile**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If `androidx.credentials` 1.7.0-alpha03 broke an API used by `DcPresentationActivity` or `DcIssuanceActivity` (candidates: `PendingIntentHandler`, `selectedEntryId`, `signingInfoCompat`, `biometricPromptResult`, `ExperimentalDigitalCredentialApi`), fix the call site minimally and **report exactly what changed** — do not redesign around it.

- [ ] **Step 5: Confirm the test baseline is unmoved**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:testDebugUnitTest
```

Expected: 56 tests, 2 failed, both in `TransactionDataTest`. Any other failure is caused by this bump and must be understood before continuing.

- [ ] **Step 6: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: bump credentials registry to 1.0.0-alpha05

alpha04's OpenId4VpRegistry emits no supported_protocols key, which the
CMWallet matcher iterates to decide which requests to process — with alpha04
it matches nothing at all. alpha05 also adds the three PROTOCOL_OPENID4VP_1_0_*
constants and metadata_display_text.

registry-provider:1.0.0-alpha05 requires androidx.credentials 1.7.0-alpha03;
that bump is pinned explicitly so it shows up in this diff rather than being
resolved silently. Adds the mdoc/sdjwtvc/openid blob serialisers."
```

---

### Task 6: Pure entry mapper with unit tests

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapper.kt`
- Create: `app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapperTest.kt`
- Test: `gradle :app:testDebugUnitTest --tests '*DcRegistryEntryMapperTest*'`

**Interfaces:**

- Consumes: Task 5's androidx types (`SdJwtClaim`, `MdocField`, `VerificationFieldDisplayProperties`) and `kotlinx.serialization.json`. Nothing else — this file must stay free of Android types. (`SdJwtVctExtractor` and `SdJwtClaimsReconstructor` are consumed by Task 7, not here.)
- Produces:
  - `DcRegistryEntryMapper.sdJwtClaims(claimsTree: JsonObject): List<SdJwtClaim>`
  - `DcRegistryEntryMapper.mdocFields(namespaces: Map<String, Map<String, JsonElement>>): List<MdocField>`
  - `DcRegistryEntryMapper.displayName(path: List<String>): String`
  - `DcRegistryEntryMapper.displayValue(element: JsonElement): String?`

Task 7 calls exactly these four.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapperTest.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DcRegistryEntryMapperTest {

    @Test
    fun `sdJwtClaims flattens a nested tree to dotted display names`() {
        val tree = buildJsonObject {
            put("given_name", JsonPrimitive("Ada"))
            putJsonObject("address") {
                put("locality", JsonPrimitive("Berlin"))
            }
        }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        val byPath = claims.associateBy { it.path }
        assertEquals(setOf(listOf("given_name"), listOf("address", "locality")), byPath.keys)
        assertEquals("Ada", byPath[listOf("given_name")]!!.value)
        assertEquals("Berlin", byPath[listOf("address", "locality")]!!.value)
    }

    @Test
    fun `sdJwtClaims treats an array as a single leaf`() {
        val tree = buildJsonObject {
            putJsonArray("nationalities") {
                add(JsonPrimitive("DE"))
                add(JsonPrimitive("FR"))
            }
        }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        assertEquals(1, claims.size)
        assertEquals(listOf("nationalities"), claims.single().path)
    }

    @Test
    fun `sdJwtClaims skips the tree root and empty objects`() {
        val tree = buildJsonObject {
            putJsonObject("empty") {}
            put("vct", JsonPrimitive("https://example.test/vct"))
        }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        assertTrue(claims.none { it.path.isEmpty() })
        assertEquals(setOf(listOf("empty"), listOf("vct")), claims.map { it.path }.toSet())
    }

    @Test
    fun `mdocFields keys by namespace and element identifier`() {
        val namespaces: Map<String, Map<String, JsonElement>> = mapOf(
            "org.iso.18013.5.1" to mapOf(
                "family_name" to JsonPrimitive("Lovelace"),
                "age_over_18" to JsonPrimitive(true),
            ),
        )

        val fields = DcRegistryEntryMapper.mdocFields(namespaces)

        assertEquals(2, fields.size)
        val familyName = fields.single { it.identifier == "family_name" }
        assertEquals("org.iso.18013.5.1", familyName.namespace)
        assertEquals("Lovelace", familyName.fieldValue)
    }

    @Test
    fun `displayName joins a nested path with dots`() {
        assertEquals("address.locality", DcRegistryEntryMapper.displayName(listOf("address", "locality")))
    }

    @Test
    fun `displayValue unwraps primitives and stringifies containers`() {
        assertEquals("Ada", DcRegistryEntryMapper.displayValue(JsonPrimitive("Ada")))
        assertEquals("true", DcRegistryEntryMapper.displayValue(JsonPrimitive(true)))
        val array = buildJsonObject { putJsonArray("x") { add(JsonPrimitive("a")) } }["x"]!!
        assertEquals("""["a"]""", DcRegistryEntryMapper.displayValue(array))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /Users/senexi/dev/eudiw/elpaso
gradle :app:testDebugUnitTest --tests '*DcRegistryEntryMapperTest*'
```

Expected: compilation failure — `Unresolved reference: DcRegistryEntryMapper`.

- [ ] **Step 3: Write the mapper**

`app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapper.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import androidx.credentials.registry.digitalcredentials.mdoc.MdocField
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtClaim
import androidx.credentials.registry.provider.digitalcredentials.VerificationFieldDisplayProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure mapping from decoded credential content to the androidx registry field types.
 *
 * Split out of [DcRegistryBlobBuilder] on purpose: entry-level construction needs a real
 * `Bitmap` for the icon, and this module has no `androidTest` source set and no
 * Robolectric, so anything touching `Bitmap` cannot be unit-tested here. Everything in
 * this file is deliberately free of Android types so it can be.
 *
 * The shapes produced here are consumed by the vendored CMWallet matcher, whose expected
 * registry layout is documented in `matcher/upstream/index.md`: for `dc+sd-jwt`, claims
 * nest into a `paths` tree keyed by each path segment, with `{value, display}` leaves;
 * for `mso_mdoc`, `paths` is namespace then element identifier.
 */
internal object DcRegistryEntryMapper {

    /**
     * Depth-first walk of a reconstructed SD-JWT claims tree, yielding one [SdJwtClaim]
     * per leaf.
     *
     * Arrays are leaves, not branches: the matcher's path resolution returns the whole
     * array for an array-valued path, and there is no useful per-index display label.
     * Empty objects are also leaves so the path still exists for DCQL path-existence
     * checks, which is what most queries actually test.
     */
    fun sdJwtClaims(claimsTree: JsonObject): List<SdJwtClaim> {
        val out = mutableListOf<SdJwtClaim>()

        fun walk(element: JsonElement, prefix: List<String>) {
            when {
                element is JsonObject && element.isNotEmpty() ->
                    element.forEach { (key, value) -> walk(value, prefix + key) }

                prefix.isEmpty() -> Unit // never emit a claim for the tree root

                else -> out += claim(prefix, element)
            }
        }

        walk(claimsTree, emptyList())
        return out
    }

    /** One [MdocField] per element, addressed as `[namespace, element_id]` per OID4VP 6.4.1. */
    fun mdocFields(namespaces: Map<String, Map<String, JsonElement>>): List<MdocField> =
        namespaces.flatMap { (namespace, elements) ->
            elements.map { (identifier, value) ->
                MdocField(
                    namespace = namespace,
                    identifier = identifier,
                    fieldValue = unwrap(value),
                    fieldDisplayPropertySet = setOf(
                        VerificationFieldDisplayProperties(
                            displayName = identifier,
                            displayValue = displayValue(value),
                        ),
                    ),
                )
            }
        }

    /**
     * Label for a nested claim. Dotted segments (`address.locality`) are adequate for
     * now; localised, VCT-metadata-driven labels are a separate piece of work.
     */
    fun displayName(path: List<String>): String = path.joinToString(".")

    /** Human-readable rendering of a claim value, or null when there is nothing to show. */
    fun displayValue(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> if (element is JsonNull) null else element.content
        is JsonObject, is JsonArray -> element.toString()
    }

    private fun claim(path: List<String>, element: JsonElement) = SdJwtClaim(
        path = path,
        value = unwrap(element),
        fieldDisplayPropertySet = setOf(
            VerificationFieldDisplayProperties(
                displayName = displayName(path),
                displayValue = displayValue(element),
            ),
        ),
        isSelectivelyDisclosable = true,
    )

    /**
     * Convert a [JsonElement] to the plain Kotlin value the serialiser writes into the
     * blob's `value` position. Strings, booleans and numbers map natively; containers
     * fall back to their JSON text, because the matcher only inspects values for DCQL
     * `values:` equality checks and a stringified container is closer to useful than a
     * dropped path.
     */
    private fun unwrap(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> when {
            element is JsonNull -> ""
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> element.content.toLongOrNull()
                ?: element.content.toDoubleOrNull()
                ?: element.content
        }

        is JsonObject, is JsonArray -> element.toString()
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
cd /Users/senexi/dev/eudiw/elpaso
gradle :app:testDebugUnitTest --tests '*DcRegistryEntryMapperTest*'
```

Expected: PASS, 6 tests. If `SdJwtClaim`/`MdocField` cannot be constructed on the JVM (an unexpected Android dependency inside them), **stop and report** — that invalidates the split in this plan and Task 8's approach becomes the only verification available.

- [ ] **Step 5: Full unit-test run**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:testDebugUnitTest
```

Expected: 62 tests, 2 failed (the two known `TransactionDataTest` ones).

- [ ] **Step 6: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapper.kt \
        app/src/test/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryEntryMapperTest.kt
git commit -m "feat(dcapi): map credential content to androidx registry fields

Pure, Android-free mapping so it is unit-testable: this module has no
androidTest source set and no Robolectric, and entry-level construction needs a
real Bitmap for the icon."
```

---

### Task 7: Switch the registry over to the stock blob

**Files:**

- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryBlobBuilder.kt`
- Create: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/CborJson.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistrySync.kt`
- Modify: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/CustomMatcherRegistry.kt` (KDoc)
- Delete: `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/MatcherPackageBuilder.kt`
- Delete: `app/src/main/assets/dcapi_matcher.wasm`
- Test: `gradle :app:compileDebugKotlin`, then the full unit-test run

**Interfaces:**

- Consumes: `DcRegistryEntryMapper.{sdJwtClaims,mdocFields}` from Task 6; the asset `openid4vp1_0.wasm` from Tasks 3–4.
- Produces: `DcRegistryBlobBuilder.build(credentials: List<Credential>, icon: Bitmap, registryId: String): ByteArray` returning the stock registry blob; `CborJson.toJson(item: DataItem): JsonElement`; `DcRegistrySync` unchanged in public signature.

- [ ] **Step 1: Write `DcRegistryBlobBuilder`**

`app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistryBlobBuilder.kt`:

```kotlin
package dev.digitallabor.elpaso.wallet.dcapi

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.credentials.registry.digitalcredentials.mdoc.MdocEntry
import androidx.credentials.registry.digitalcredentials.openid4vp.OpenId4VpRegistry
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtEntry
import androidx.credentials.registry.provider.digitalcredentials.DigitalCredentialEntry
import androidx.credentials.registry.provider.digitalcredentials.VerificationEntryDisplayProperties
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.vct.SdJwtClaimsReconstructor
import dev.digitallabor.elpaso.wallet.vct.SdJwtVctExtractor
import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.issuersigned.IssuerNamespaces

/**
 * Builds the Digital Credentials registry blob consumed by the vendored CMWallet
 * matcher (`app/src/main/assets/openid4vp1_0.wasm`, source under `matcher/`).
 *
 * The blob is not our format. It is the stock layout produced by
 * `OpenId4VpRegistry` — a 4-byte little-endian offset to the JSON, raw icon bytes in
 * between, then JSON keyed by credential format and then by VCT or doctype.
 * `matcher/upstream/index.md` names that Jetpack class as the format's source of truth,
 * so we build a registry with it and reuse its `credentials` bytes rather than
 * hand-rolling the binary framing.
 *
 * `supported_protocols` in that JSON is what the matcher's request loop iterates. It
 * only exists from `registry-digitalcredentials-openid:1.0.0-alpha05` onward; against
 * alpha04 the matcher processes no requests at all and the wallet never appears in the
 * system picker.
 */
internal object DcRegistryBlobBuilder {
    private const val LOG_TAG = "DcRegistryBlobBuilder"

    /**
     * Serialise [credentials] into registry bytes ready for `CustomMatcherRegistry`.
     *
     * [icon] is the wallet's launcher icon, passed in rather than rendered here so this
     * object does not need a `Context`.
     */
    fun build(
        credentials: List<Credential>,
        icon: Bitmap,
        registryId: String,
    ): ByteArray {
        val entries = credentials.mapNotNull { credential ->
            when (credential.format) {
                Format.SdJwtVc -> sdJwtEntry(credential, icon)
                Format.MsoMdoc -> mdocEntry(credential, icon)
            }
        }
        return OpenId4VpRegistry(
            credentialEntries = entries,
            id = registryId,
            supportedProtocols = listOf(
                OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_UNSIGNED,
                OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_SIGNED,
                OpenId4VpRegistry.PROTOCOL_OPENID4VP_1_0_MULTISIGNED,
            ),
        ).credentials
    }

    private fun display(credential: Credential, icon: Bitmap) =
        setOf(
            VerificationEntryDisplayProperties(
                title = credential.displayName,
                subtitle = credential.issuerId,
                icon = icon,
            ),
        )

    private fun sdJwtEntry(credential: Credential, icon: Bitmap): DigitalCredentialEntry? {
        val vct = SdJwtVctExtractor.extract(credential.payload) ?: run {
            Log.w(LOG_TAG, "sdJwtEntry: no vct for ${credential.id}")
            return null
        }
        // Reconstructed tree: `_sd` digests resolved back to their disclosure values at
        // the position the issuer signed them at. Anything missing here, or placed
        // differently from where the verifier looks, simply will not match.
        val claimsTree = SdJwtClaimsReconstructor.reconstruct(credential.payload)
        return SdJwtEntry(
            verifiableCredentialType = vct,
            claims = DcRegistryEntryMapper.sdJwtClaims(claimsTree),
            entryDisplayPropertySet = display(credential, icon),
            id = credential.id,
        )
    }

    /**
     * Decode the mdoc `IssuerSigned` CBOR and expose its namespace-keyed elements.
     *
     * Strict on purpose. Unlike the detail screen, which tolerates a non-`bstr`
     * `IssuerSignedItem.random` for display, the matcher must not advertise a credential
     * the presentation path cannot actually produce a `DeviceResponse` for — the MSO
     * digests are over the issuer's original bytes. A non-conformant issuer therefore
     * yields a logged omission rather than an entry that fails at selection time.
     */
    private fun mdocEntry(credential: Credential, icon: Bitmap): DigitalCredentialEntry? =
        runCatching {
            val payloadBytes = Base64.decode(
                credential.payload.decodeToString(),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val issuerSignedMap = Cbor.decode(payloadBytes).asMap
            val nameSpacesItem =
                issuerSignedMap.entries.firstOrNull { it.key.asTstr == "nameSpaces" }?.value
                    ?: run {
                        Log.w(LOG_TAG, "mdocEntry: no nameSpaces for ${credential.id}")
                        return@runCatching null
                    }
            val namespaces = IssuerNamespaces.fromDataItem(nameSpacesItem)
            if (namespaces.data.isEmpty()) {
                Log.w(LOG_TAG, "mdocEntry: empty namespaces for ${credential.id}")
                return@runCatching null
            }
            val decoded: Map<String, Map<String, JsonElement>> =
                namespaces.data.entries.associate { (namespace, elements) ->
                    namespace to elements.entries.associate { (name, signedItem) ->
                        name to CborJson.toJson(signedItem.dataElementValue)
                    }
                }
            MdocEntry(
                docType = credential.configurationId,
                fields = DcRegistryEntryMapper.mdocFields(decoded),
                entryDisplayPropertySet = display(credential, icon),
                id = credential.id,
            )
        }.onFailure { Log.w(LOG_TAG, "mdocEntry failed for ${credential.id}", it) }.getOrNull()
}
```

- [ ] **Step 2: Move `cborToJson` into a reusable object**

`MatcherPackageBuilder.cborToJson` is the only part of that file worth keeping, and Step 1 references it as `CborJson.toJson`. Create
`app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/CborJson.kt` containing an
`internal object CborJson` with a single `fun toJson(item: DataItem): JsonElement`, whose
body is copied **verbatim** from `MatcherPackageBuilder.cborToJson` (the `when
(item.majorType)` block covering `UNSIGNED_INTEGER`/`NEGATIVE_INTEGER`, `BYTE_STRING`,
`UNICODE_STRING`, `ARRAY`, `MAP`, `TAG`, `SPECIAL`), together with that function's KDoc.
Carry over the same imports (`org.multipaz.cbor.Cbor`, `DataItem`, `MajorType`,
`android.util.Base64`, and the `kotlinx.serialization.json` types).

- [ ] **Step 3: Rewire `DcRegistrySync`**

Three edits in `app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/DcRegistrySync.kt`:

1. Replace the class KDoc paragraph that begins "We ship our own matcher binary" with:

```kotlin
 * We ship a matcher built in-house from CMWallet's reference C implementation (see
 * `matcher/` and `app/src/main/assets/openid4vp1_0.wasm`) rather than the binary bundled
 * with `OpenId4VpRegistry`. Building it ourselves is what lets us carry a small local
 * delta — PaSO SCA payment rendering — while keeping upstream's DCQL semantics, including
 * credential sets, signed and multisigned requests, and inline issuance entries.
 *
 * The credential payload itself IS the stock `OpenId4VpRegistry` blob: we build that
 * registry, take its bytes, and pair them with our own matcher. See
 * [DcRegistryBlobBuilder].
```

1. In `register`, replace the `MatcherPackageBuilder.build(credentials, launcherIcon())`
call with:

```kotlin
        val packageJson = DcRegistryBlobBuilder.build(credentials, launcherIcon(), REGISTRY_ID)
```

1. Replace `MATCHER_ASSET` in the companion:

```kotlin
        const val MATCHER_ASSET = "openid4vp1_0.wasm"
```

- [ ] **Step 4: Make the debug dump binary-aware**

Replace the body of `dumpPackageForDebug` in `DcRegistrySync.kt` with:

```kotlin
    /**
     * Mirror the registry blob we are about to register to internal storage so it can be
     * pulled off-device (`adb pull /data/data/<pkg>/files/dcapi_package.bin`) and checked
     * with `scripts/verify-registry-blob.py`, and chunk-log the JSON tail so it is visible
     * in logcat too.
     *
     * The blob is binary — a 4-byte little-endian offset to the JSON, then icon bytes —
     * so only the tail is text. This is the tool that explains why a credential did not
     * match; keep it working.
     */
    private fun dumpPackageForDebug(packageBytes: ByteArray) {
        runCatching {
            val file = File(context.filesDir, "dcapi_package.bin")
            file.writeBytes(packageBytes)
            Log.i(LOG_TAG, "wrote registry blob: ${file.absolutePath} (${packageBytes.size} bytes)")
        }.onFailure { Log.w(LOG_TAG, "blob dump failed", it) }

        val jsonOffset = runCatching {
            ByteBuffer.wrap(packageBytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }.getOrElse {
            Log.w(LOG_TAG, "could not read JSON offset", it)
            return
        }
        if (jsonOffset < 4 || jsonOffset > packageBytes.size) {
            Log.w(LOG_TAG, "implausible JSON offset $jsonOffset for ${packageBytes.size} bytes")
            return
        }
        val text = packageBytes.decodeToString(jsonOffset, packageBytes.size)
        var i = 0
        var part = 0
        val chunk = 3500
        val total = (text.length + chunk - 1) / chunk
        while (i < text.length) {
            val end = (i + chunk).coerceAtMost(text.length)
            Log.i(LOG_TAG, "blob[$part/$total]=${text.substring(i, end)}")
            i = end
            part++
        }
    }
```

Add the two imports this needs: `java.nio.ByteBuffer` and `java.nio.ByteOrder`.

- [ ] **Step 5: Update `CustomMatcherRegistry` KDoc**

Replace its KDoc body with:

```kotlin
/**
 * `RegisterCredentialsRequest` pairing the stock registry blob with our own matcher WASM.
 *
 * `OpenId4VpRegistry` is `final` and accepts no matcher parameter, so it cannot be
 * subclassed to inject ours. It does extend `DigitalCredentialRegistry`, though, so its
 * `credentials` bytes can be lifted and re-paired here with the binary we build from
 * `matcher/`. The platform runs [matcherWasm] in its sandboxed interpreter on every
 * incoming DC API request; the matcher reads [credentialsJson] via
 * `ReadCredentialsBuffer` and emits the entries the system selector surfaces.
 */
```

- [ ] **Step 6: Delete the replaced pieces**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git rm app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi/MatcherPackageBuilder.kt
git rm app/src/main/assets/dcapi_matcher.wasm
grep -rn "MatcherPackageBuilder\|dcapi_matcher" --include='*.kt' app/src | grep -v '/build/' || echo NO_REFS
```

Expected: `NO_REFS`. Any hit must be fixed before compiling.

- [ ] **Step 7: Compile and test**

```bash
cd /Users/senexi/dev/eudiw/elpaso
gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; 62 tests, 2 known failures. If `OpenId4VpRegistry`'s parameter names differ from `credentialEntries` / `id` / `supportedProtocols`, use positional arguments in the documented order (entries, id, then the third string, inline issuance entries, protocols) and report the real signature.

- [ ] **Step 8: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add -A app/src/main/java/dev/digitallabor/elpaso/wallet/dcapi app/src/main/assets
git commit -m "feat(dcapi): register the stock blob with the CMWallet matcher

Replaces MatcherPackageBuilder's bespoke PackageConfig JSON with the stock
OpenId4VpRegistry blob, whose binary framing the vendored matcher expects.
Drops the Kotlin-side knobs that format carried (payment_sca, ts12_prefixes,
transaction_data_types, log_level); PaSO awareness now lives in
matcher/patches/0001 instead.

The debug dump follows the format: it writes dcapi_package.bin and logs only
the JSON tail past the little-endian offset header."
```

---

### Task 8: Host-side blob verifier

The spec asked for a blob decode test and flagged it might have to be an `androidTest`. It can be neither here: there is no `androidTest` source set, no Robolectric, and `VerificationEntryDisplayProperties` needs a real `Bitmap`. The equivalent evidence therefore comes from the blob the app actually produces on-device, checked by a committed script — which validates real data rather than a synthetic fixture. **This is a deliberate deviation from the spec; record it in the task report.**

**Files:**

- Create: `scripts/verify-registry-blob.py`
- Test: run it against a blob pulled from a device (needs Task 10's install)

**Interfaces:**

- Consumes: `dcapi_package.bin` written by `DcRegistrySync.dumpPackageForDebug` (Task 7).
- Produces: `scripts/verify-registry-blob.py BLOB` — exit 0 on a conforming blob, exit 1 with a named reason otherwise.

- [ ] **Step 1: Write the verifier**

`scripts/verify-registry-blob.py`:

```python
#!/usr/bin/env python3
"""Validate a Digital Credentials registry blob against what the matcher reads.

Pull one off a device with:
  adb exec-out run-as dev.digitallabor.elpaso.wallet cat files/dcapi_package.bin > /tmp/blob.bin
  python3 scripts/verify-registry-blob.py /tmp/blob.bin

Checks exactly the keys matcher/upstream/openid4vp1_0.c and dcql.c read, so a pass here
means the matcher can see our credentials. Format spec: matcher/upstream/index.md.
"""
import json
import struct
import sys

PROTOCOLS = {
    "openid4vp-v1-unsigned",
    "openid4vp-v1-signed",
    "openid4vp-v1-multisigned",
}


def fail(msg):
    print("FAIL: " + msg)
    raise SystemExit(1)


def check_candidate(fmt, key, index, cand):
    """One credential entry. dcql.c reads id, display.verification and paths."""
    where = fmt + "/" + key + "[" + str(index) + "]"
    if not cand.get("id"):
        fail(where + " has no id; AddEntryToSet would report an empty cred id")
    verification = cand.get("display", {}).get("verification")
    if not verification:
        fail(where + " has no display.verification; the selector entry would be blank")
    if not verification.get("title"):
        print("WARN: " + where + " has an empty title")
    icon = verification.get("icon")
    if icon and not ("start" in icon and "length" in icon):
        fail(where + " icon lacks start/length; creds_blob offset arithmetic would read garbage")
    paths = cand.get("paths")
    if not paths:
        fail(where + " has no paths; every claim query against it fails to match")
    leaves = count_leaves(paths)
    print("  " + where + ": id=" + cand["id"] + " leaves=" + str(leaves))
    if leaves == 0:
        fail(where + " paths tree has no display-bearing leaf")


def count_leaves(node):
    """Leaves are objects carrying `display`; dcql.c's AddAllClaims looks for exactly that."""
    if not isinstance(node, dict):
        return 0
    if "display" in node:
        return 1
    return sum(count_leaves(v) for v in node.values())


def main(path):
    data = open(path, "rb").read()
    if len(data) < 5:
        fail("blob is only " + str(len(data)) + " bytes")

    (offset,) = struct.unpack_from("<i", data, 0)
    print("json offset: " + str(offset) + " (blob " + str(len(data)) + " bytes)")
    if not 4 <= offset <= len(data):
        fail("implausible JSON offset " + str(offset))
    print("icon region: " + str(offset - 4) + " bytes")

    try:
        doc = json.loads(data[offset:].decode("utf-8"))
    except Exception as exc:
        fail("JSON at offset " + str(offset) + " did not parse: " + str(exc))

    protocols = doc.get("supported_protocols")
    if not protocols:
        fail(
            "no supported_protocols. openid4vp1_0.c iterates this array, so with it "
            "absent the matcher processes zero requests and the wallet never appears "
            "in the picker. This is the signature of building the blob against "
            "registry-digitalcredentials-openid 1.0.0-alpha04 instead of alpha05."
        )
    print("supported_protocols: " + ", ".join(protocols))
    unknown = set(protocols) - PROTOCOLS
    if unknown:
        print("WARN: unrecognised protocols " + str(sorted(unknown)))

    credentials = doc.get("credentials")
    if not credentials:
        fail("no credentials object")

    total = 0
    for fmt, by_key in credentials.items():
        if fmt == "issuance":
            continue
        if fmt not in ("mso_mdoc", "dc+sd-jwt"):
            print("WARN: format '" + fmt + "' is not one dcql.c handles; it will be skipped")
            continue
        for key, candidates in by_key.items():
            for index, cand in enumerate(candidates):
                check_candidate(fmt, key, index, cand)
                total += 1

    if total == 0:
        fail("no credential candidates under mso_mdoc or dc+sd-jwt")
    print("OK: " + str(total) + " candidate(s) conform")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: verify-registry-blob.py BLOB", file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1])
```

- [ ] **Step 2: Smoke-test the verifier against a known-good blob**

Upstream's `testdata/registry.json` is a conforming registry, so wrapping it in the binary framing exercises the happy path without a device:

```bash
cd /Users/senexi/dev/eudiw/elpaso
python3 - <<'PY'
import json, struct
src = "matcher/upstream/testdata/registry.json"
body = open(src, "rb").read()
offset = 4 + 10
open("/tmp/good-blob.bin", "wb").write(struct.pack("<i", offset) + bytes(range(10)) + body)
print("wrote /tmp/good-blob.bin")
PY
python3 scripts/verify-registry-blob.py /tmp/good-blob.bin
```

Expected: `supported_protocols: openid4vp-v1-signed, openid4vp-v1-unsigned, openid4vp-v1-multisigned`, several `id=… leaves=N` lines, and `OK: N candidate(s) conform`.

- [ ] **Step 3: Prove it catches the alpha04 failure mode**

```bash
cd /Users/senexi/dev/eudiw/elpaso
python3 - <<'PY'
import json, struct
doc = json.load(open("matcher/upstream/testdata/registry.json"))
doc.pop("supported_protocols", None)
body = json.dumps(doc).encode()
offset = 4 + 10
open("/tmp/bad-blob.bin", "wb").write(struct.pack("<i", offset) + bytes(range(10)) + body)
PY
python3 scripts/verify-registry-blob.py /tmp/bad-blob.bin; echo "exit=$?"
```

Expected: `FAIL: no supported_protocols…` and `exit=1`. A verifier that passes here is not checking the one thing most likely to go wrong.

- [ ] **Step 4: Commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
git add scripts/verify-registry-blob.py
git commit -m "test(dcapi): verify a pulled registry blob against the matcher's reads

Checks the little-endian offset header, icon start/length, supported_protocols
and the per-candidate id/display/paths shape that dcql.c walks. Replaces the
spec's proposed decode unit test, which is not possible here: no androidTest
source set, no Robolectric, and the display properties need a real Bitmap."
```

---

### Task 9: Documentation and reference cleanup

**Files:**

- Modify: `README.md` (asset list around line 304, presentation bullet around line 61)
- Modify: `AGENTS.md` (build quirks, DC API section)
- Test: `grep` for stale references

**Interfaces:**

- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Find every stale reference**

```bash
cd /Users/senexi/dev/eudiw/elpaso
grep -rn "dcapi_matcher\|dcapi-matcher\|MatcherPackageBuilder" \
  README.md AGENTS.md docs/ scripts/ --include='*.md' --include='*.sh' \
  | grep -v 'docs/superpowers/specs/2026-08-21-cmwallet-matcher-adoption-design.md' \
  | grep -v 'docs/superpowers/plans/2026-08-21-cmwallet-matcher-adoption.md'
```

The two documents excluded above describe the change itself and legitimately name the old artifact. Everything else the grep reports is stale and must be updated in the next two steps. Historical plan and spec documents under `docs/superpowers/` that record *past* work are history, not current API — leave those alone, matching the convention AGENTS.md already sets for the pre-fork package name.

- [ ] **Step 2: Update `README.md`**

Replace the asset-list paragraph (currently naming `dcapi_matcher.wasm`) with:

```markdown
Assets: `openid4vp1_0.wasm` (DC API presentation matcher, built from CMWallet's
reference C source — see `matcher/` and run `bash scripts/build-matcher.sh`),
`dc_issuance_matcher.wasm` (DC API issuance/creation-options matcher, vendored from
CMWallet's `provision_hardcoded.wasm`; source is CMWallet `matcher/issuance/provision.c`),
`trusted_issuers.json`, `trusted_verifiers.json`.
```

And in the presentation feature list, replace the bullet mentioning "a bundled matcher binary (`dcapi_matcher.wasm`)" with:

```markdown
- **W3C Digital Credentials API** — registers a credential provider so stored
  credentials appear in the Android system credential picker, using a matcher built
  in-house from CMWallet's reference C implementation (`openid4vp1_0.wasm`).
```

- [ ] **Step 3: Add the build quirk to `AGENTS.md`**

Under "Build quirks", add:

```markdown
- **The DC API matcher is a committed binary with a container build.**
  `app/src/main/assets/openid4vp1_0.wasm` is built from vendored CMWallet C sources in
  `matcher/` by `bash scripts/build-matcher.sh`, which needs **Podman** (or `docker` via
  `MATCHER_ENGINE`). `--verify` rebuilds and fails if the committed binary, a fresh
  build, and `matcher/PROVENANCE` disagree. Never edit `matcher/upstream/` — local
  deltas are patch files in `matcher/patches/`, and the build refuses to run if one
  does not apply.
- **Credman allowlists the matcher's wasm exports** to `memory`, `_start` and `main`.
  Anything else throws `IllegalArgumentException: Unknown export` at request time and
  the wallet silently vanishes from the system picker. `matcher/strip_exports.py`
  enforces this and the build asserts the result.
- **`androidx.credentials.registry` must stay at 1.0.0-alpha05 or newer.** alpha04's
  `OpenId4VpRegistry` emits no `supported_protocols` key; the matcher iterates that
  array, so on alpha04 it processes zero requests and nothing ever matches. That
  failure is invisible — no crash, no log, just an absent wallet.
```

- [ ] **Step 4: Update the DC API description in `AGENTS.md`**

In the cross-cutting architecture section, where `DcRegistrySync` is described, make sure the text reflects that the credential payload is the stock `OpenId4VpRegistry` blob paired with our own matcher, and that `matcher/UPSTREAM.md` is the place to read before touching the C. Keep it to two or three sentences; AGENTS.md is about not breaking things, not a tutorial.

- [ ] **Step 5: Re-run the grep and commit**

```bash
cd /Users/senexi/dev/eudiw/elpaso
grep -rn "dcapi_matcher\|dcapi-matcher" README.md AGENTS.md scripts/ || echo CLEAN
git add README.md AGENTS.md
git commit -m "docs: record the containerised matcher build and its traps

Names the two failure modes that produce no error message: an unexpected wasm
export, and a registry blob without supported_protocols. Both make the wallet
disappear from the system picker silently."
```

---

### Task 10: Branch-level verification

Everything before this proved pieces work in isolation. This proves the platform accepts the result, which no earlier rung can.

**Files:**

- Modify: none, unless a gate fails
- Test: `assembleRelease`, then a device

**Interfaces:**

- Consumes: Tasks 1–9.
- Produces: a verification report; no code.

- [ ] **Step 1: Confirm every diagnostic is clear**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:compileDebugKotlin && gradle :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL; 62 tests with exactly the 2 known `TransactionDataTest` failures.

- [ ] **Step 2: R8 gate**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. `assembleDebug` does not exercise R8, so this is the only place three new alpha artifacts can surface a shrinker problem. If R8 strips something the registry libraries reflect over, add a keep rule to `proguard-rules.pro` and re-run — **report the rule and why it was needed**. Note this task bumps `app/version.properties`; commit that file like any other source change.

- [ ] **Step 3: Install and confirm the registration happens**

```bash
cd /Users/senexi/dev/eudiw/elpaso && gradle :app:installDebug
adb logcat -c
adb shell monkey -p dev.digitallabor.elpaso.wallet -c android.intent.category.LAUNCHER 1
sleep 5
adb logcat -d | grep -E "DcRegistrySync|DcRegistryBlobBuilder" | tail -20
```

Expected: a `Registering total=N sdjwt=… mdoc=…` line, a `wrote registry blob: …dcapi_package.bin` line, and `registerCredentials succeeded`. A `registerCredentials failed` line here means the platform rejected the wasm or the blob — capture the exception and stop.

- [ ] **Step 4: Verify the real blob**

```bash
cd /Users/senexi/dev/eudiw/elpaso
adb exec-out run-as dev.digitallabor.elpaso.wallet cat files/dcapi_package.bin > /tmp/device-blob.bin
python3 scripts/verify-registry-blob.py /tmp/device-blob.bin
```

Expected: `OK: N candidate(s) conform`, with N equal to the credential count from Step 3. This is where the spec's two known unknowns get answered — **record in the task report** (a) how array and nested-object claim values were serialised into `value`, and (b) whether `isSelectivelyDisclosable` changed the emitted JSON at all. If arrays came through mangled, fix `DcRegistryEntryMapper.unwrap` and rerun from Step 3.

- [ ] **Step 5: Drive a real verifier**

Open `https://digital-credentials.dev/` on the device and request a credential the wallet holds. Check, in order:

1. The wallet appears in the system picker at all. If not: the blob or protocol list is wrong — go back to Step 4's output.
2. The entry shows the expected title and subtitle, and the requested claim names are listed.
3. Selecting it launches `DcPresentationActivity` and the consent screen shows the right credential (log line `selected_entry_id=<uuid>` should match a credential id).
4. The verifier accepts the returned `vp_token`.

Repeat for an mdoc credential and an SD-JWT credential, since they take different `dcql.c` paths.

Watch for `IllegalArgumentException: Unknown export` in logcat — that means the export filter regressed and the build gate was bypassed somehow.

- [ ] **Step 6: Drive a PaSO payment**

Using the local `../cli-verifier` or `../eudipay-merchant-mock`, send an OpenID4VP request carrying a `urn:paso:sca:global:payment:1` `transaction_data` entry. Expected: the system selector renders a **payment** entry showing the merchant name and the amount (e.g. `Merchant X` / `56.66 EUR`), not a plain verification entry and not a payment entry with blanks. Blanks mean `patches/0001` is not in the shipped binary — re-run `bash scripts/build-matcher.sh --verify`.

- [ ] **Step 7: Report**

Summarise, with evidence: unit-test counts, `assembleRelease` result, the blob verifier output, which verifiers were exercised, and the answers to the two known unknowns from Step 4. Then hand off for final review.

---

## Notes for the executor

**Work on a branch, ideally in a worktree.** This touches the DC API core and has no valid intermediate state (see Global Constraints). Use `superpowers:using-git-worktrees` before starting.

**Two deliberate deviations from the spec, both already argued above:**

1. The spec's "blob decode test" became a host-side verifier script (Task 8), because this module has no `androidTest` source set and no Robolectric, and the display properties require a real `Bitmap`.
2. The spec described one new Kotlin file; the plan splits it into `DcRegistryEntryMapper` (pure, tested) and `DcRegistryBlobBuilder` (Android), for the same testability reason.

**Where to stop and ask rather than improvise:**

- Upstream HEAD is not `9407802` (Task 1, Step 1).
- Upstream's shipped wasm exports something outside `{memory, _start, main}` (Task 2, Step 2).
- `make test` fails on *pristine* upstream (Task 2, Step 8).
- `SdJwtClaim`/`MdocField` cannot be constructed on the JVM (Task 6, Step 4).
- `androidx.credentials` 1.7.0-alpha03 breaks a DC API activity in a way that needs more than a mechanical call-site fix (Task 5, Step 4).

**Never do these**, each of which turns a loud failure into a silent one:

- Relax `matcher/reference-surface.json` or the export allowlist to make the surface diff pass.
- Commit a `TC41` expected fixture that shows empty merchant/amount.
- Edit `matcher/upstream/` directly instead of adding a patch.
- Downgrade the registry dependency below alpha05.
