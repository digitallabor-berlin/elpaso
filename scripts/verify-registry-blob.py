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
from typing import NoReturn

PROTOCOLS = {
    "openid4vp-v1-unsigned",
    "openid4vp-v1-signed",
    "openid4vp-v1-multisigned",
}


def fail(msg) -> NoReturn:
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
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError as exc:
        fail("could not read " + path + ": " + str(exc))
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