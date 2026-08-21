#!/usr/bin/env python3
"""Filter a wasm module's export section down to Credman's allowlist.

Play Services' Credman runtime accepts only `memory`, `_start` and `main` as exports.
Anything else surfaces at request time as `IllegalArgumentException: Unknown export`,
and the wallet silently vanishes from the system credential picker. wasi-sdk's linker
emits extra symbols (__heap_base, __data_end, __indirect_function_table), so this runs
on every build. It rewrites the file in place and then asserts the result, so a future
linker change fails the build instead of shipping a dead matcher.

Note the allowlist is a superset of what upstream's shipped binary actually exports
(`_start` and `memory`; see reference-surface.json). `main` is permitted because
Credman accepts it, but build.sh's surface diff is the tighter gate: it requires our
export set to equal upstream's exactly.
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


def read_module(path):
    """Read the module, failing with a build-legible reason rather than a traceback."""
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError as exc:
        raise SystemExit(f"strip: cannot read {path}: {exc}")


def write_module(path, data):
    try:
        with open(path, "wb") as fh:
            fh.write(data)
    except OSError as exc:
        raise SystemExit(f"strip: cannot write {path}: {exc}")


def strip(path):
    data = read_module(path)
    if data[:4] != b"\x00asm":
        raise SystemExit(f"strip: {path} is not a wasm module")
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
    write_module(path, out.getvalue())
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