#!/usr/bin/env bash
# Builds the custom DC API matcher WASM and vendors it into app/src/main/assets/.
#
# The matcher source lives in a sibling repo at ../dcapi-matcher (override with
# MATCHER_REPO). It targets wasm32-unknown-unknown and is consumed at runtime by
# DcRegistrySync, which hands the bytes to androidx.credentials.registry as the
# custom matcher (replacing OpenId4VpRegistry's bundled WASM that doesn't handle
# OpenID4VP transaction_data correctly).
#
# Usage: bash scripts/build-matcher.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MATCHER_REPO="${MATCHER_REPO:-$APP_ROOT/../dcapi-matcher}"
ASSET_DIR="$APP_ROOT/app/src/main/assets"
ASSET_PATH="$ASSET_DIR/dcapi_matcher.wasm"

if [ ! -d "$MATCHER_REPO" ]; then
    echo "matcher repo not found at $MATCHER_REPO" >&2
    echo "set MATCHER_REPO=<path> to override" >&2
    exit 1
fi

# Homebrew rustc shadows rustup's on some setups and lacks the wasm32 target;
# cargo's `--target` resolution goes via the rustc on PATH, so even
# `rustup run stable cargo` can pick the wrong rustc if Homebrew's cargo/rustc
# are earlier in PATH. Resolve both binaries by absolute path when rustup is
# available so we always hit the toolchain where the wasm32 target lives.
if command -v rustup >/dev/null 2>&1; then
    TOOLCHAIN_BIN="$(rustup which cargo)"
    TOOLCHAIN_BIN="${TOOLCHAIN_BIN%/*}"
    CARGO=("$TOOLCHAIN_BIN/cargo")
    export RUSTC="$TOOLCHAIN_BIN/rustc"
else
    CARGO=(cargo)
fi

echo "Building matcher in $MATCHER_REPO (cargo=${CARGO[*]}, rustc=${RUSTC:-cargo-default})..."
(cd "$MATCHER_REPO" && "${CARGO[@]}" build --release \
    --target wasm32-unknown-unknown \
    -p aptitude-consortium-dcapi-matcher)

SRC="$MATCHER_REPO/target/wasm32-unknown-unknown/release/aptitude-consortium-dcapi-matcher.wasm"
if [ ! -f "$SRC" ]; then
    echo "matcher wasm not found at $SRC after build" >&2
    exit 1
fi

mkdir -p "$ASSET_DIR"
cp "$SRC" "$ASSET_PATH"

# Strip exports that Play Services' Credman runtime doesn't expect (data-segment
# globals emitted by the wasm linker). The runtime allowlist is `memory`,
# `_start`, `main`; anything else surfaces as `IllegalArgumentException: Unknown
# export` at request time and the wallet silently disappears from the picker.
python3 - "$ASSET_PATH" <<'PY'
import io, sys
path = sys.argv[1]
def leb_u(b, i):
    n = 0; s = 0
    while True:
        x = b[i]; i += 1
        n |= (x & 0x7f) << s
        if not (x & 0x80):
            return n, i
        s += 7
def leb_enc(n):
    out = bytearray()
    while True:
        b = n & 0x7f; n >>= 7
        if n == 0:
            out.append(b); return bytes(out)
        out.append(b | 0x80)
d = open(path, 'rb').read()
i = 8
out = io.BytesIO(); out.write(d[:8])
ALLOW = {'memory', '_start', 'main'}
while i < len(d):
    sid = d[i]; i += 1
    sz, i = leb_u(d, i)
    body = d[i:i+sz]; i += sz
    if sid == 7:
        bi = 0
        cnt, bi = leb_u(body, bi)
        exports = []
        for _ in range(cnt):
            nl, bi = leb_u(body, bi)
            name = body[bi:bi+nl].decode(); bi += nl
            kind = body[bi]; bi += 1
            idx, bi = leb_u(body, bi)
            exports.append((name, kind, idx))
        kept = [e for e in exports if e[0] in ALLOW]
        if len(kept) != len(exports):
            print(f"strip: kept {[e[0] for e in kept]} dropped {[e[0] for e in exports if e[0] not in ALLOW]}")
        nb = bytearray(); nb.extend(leb_enc(len(kept)))
        for n, k, x in kept:
            nb.extend(leb_enc(len(n)))
            nb.extend(n.encode())
            nb.append(k)
            nb.extend(leb_enc(x))
        out.write(bytes([7])); out.write(leb_enc(len(nb))); out.write(bytes(nb))
    else:
        out.write(bytes([sid])); out.write(leb_enc(sz)); out.write(body)
open(path, 'wb').write(out.getvalue())
PY

echo "Vendored matcher: $ASSET_PATH ($(wc -c <"$ASSET_PATH") bytes)"
