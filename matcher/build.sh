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
    #
    # DO NOT ADD -flto. It makes the module import wasi_snapshot_preview1.random_get,
    # which upstream's shipped binary does not import, and the surface diff below then
    # fails. None of the matcher's own sources reference entropy; with LTO the libc
    # allocator's getentropy path stays live and the import survives. Credman only
    # provides the host functions upstream's module declares, so an extra import risks
    # a failed instantiation at request time -- i.e. the wallet silently disappearing
    # from the system picker. Parity with upstream is worth more than the ~8% size win.
    "$WASI_SDK_PATH/bin/clang" \
        --target=wasm32-wasip1 \
        -Os \
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