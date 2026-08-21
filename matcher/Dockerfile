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
#
# TARGETARCH is only populated automatically for a platform-aware build. When it is
# empty (a plain `podman build` on some versions), fall back to the image's own Debian
# architecture, which is the thing we actually care about.
COPY wasi-sdk.sha256 /tmp/wasi-sdk.sha256
RUN set -eux; \
    ARCH_RAW="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    case "${ARCH_RAW}" in \
      arm64) ARCH=arm64 ;; \
      amd64) ARCH=x86_64 ;; \
      *) echo "unsupported architecture=${ARCH_RAW}" >&2; exit 1 ;; \
    esac; \
    TARBALL="wasi-sdk-${WASI_SDK_VERSION}-${ARCH}-linux.tar.gz"; \
    curl -sSL --fail --retry 3 -o "/tmp/${TARBALL}" \
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