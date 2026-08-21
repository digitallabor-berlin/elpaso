# Vendored CMWallet matcher

## What this is

`upstream/` is a byte-identical copy of the `matcher/` directory from CMWallet
(<https://github.com/digitalcredentialsdev/CMWallet>) at commit `769402d`, excluding
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
