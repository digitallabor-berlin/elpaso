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
[ -f "$BUILT" ] || {
    echo "expected $BUILT after build" >&2
    exit 1
}
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
