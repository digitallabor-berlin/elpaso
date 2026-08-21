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


def read_module(path):
    """Read a wasm file, failing with a build-legible message rather than a traceback.

    This runs inside the build container, where the only consumer of a stack trace is
    a build log; a named reason is more useful than an IndexError from the section
    walker further down.
    """
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError as exc:
        raise SystemExit(f"wasm_surface: cannot read {path}: {exc}")


def surface(path):
    data = read_module(path)
    if data[:4] != b"\x00asm":
        raise SystemExit(f"wasm_surface: {path} is not a wasm module")
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