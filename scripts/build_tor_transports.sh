#!/usr/bin/env bash
# Builds official Tor Project Pluggable Transport binaries (lyrebird/obfs4 and snowflake)
# as Android statically linked ELF executables packaged inside android/app/src/main/jniLibs/<abi>/.
#
# Android extracts lib*.so files from jniLibs/<abi>/ into applicationInfo.nativeLibraryDir
# with executable permissions (+x), allowing TorService to execute them as Managed Transports
# under Android 10+ W^X SELinux rules without conflicting with hiddify-core.aar gomobile runtime.
#
# Upstream sources & licenses:
#   - lyrebird (obfs4): https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird (BSD-2-Clause / GPL-3.0-or-later)
#   - snowflake client: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake (BSD-3-Clause)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_DIR="${ROOT_DIR}/android/app/src/main/jniLibs"
BUILD_TMP="${ROOT_DIR}/.build-tor-pts"

LYREBIRD_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git"
LYREBIRD_REF="${LYREBIRD_REF:-lyrebird-0.6.1}"

SNOWFLAKE_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake.git"
SNOWFLAKE_REF="${SNOWFLAKE_REF:-v2.11.0}"

rm -rf "${BUILD_TMP}"
mkdir -p "${BUILD_TMP}"

echo "==> Cloning Tor Project lyrebird (${LYREBIRD_REF})..."
git clone --depth 1 --branch "${LYREBIRD_REF}" "${LYREBIRD_REPO}" "${BUILD_TMP}/lyrebird" \
  || git clone --depth 1 "${LYREBIRD_REPO}" "${BUILD_TMP}/lyrebird"

echo "==> Cloning Tor Project snowflake (${SNOWFLAKE_REF})..."
git clone --depth 1 --branch "${SNOWFLAKE_REF}" "${SNOWFLAKE_REPO}" "${BUILD_TMP}/snowflake" \
  || git clone --depth 1 "${SNOWFLAKE_REPO}" "${BUILD_TMP}/snowflake"

build_for_abi() {
  local abi="$1"
  local goarch="$2"
  local goarm="${3:-}"
  local out_dir="${JNILIBS_DIR}/${abi}"
  mkdir -p "${out_dir}"

  echo "==> Building liblyrebird.so (obfs4) for ${abi} (GOOS=linux GOARCH=${goarch}${goarm:+ GOARM=${goarm}})..."
  (
    cd "${BUILD_TMP}/lyrebird"
    export CGO_ENABLED=0
    export GOOS=linux
    export GOARCH="${goarch}"
    if [[ -n "${goarm}" ]]; then
      export GOARM="${goarm}"
    else
      unset GOARM || true
    fi
    go build -trimpath -ldflags="-s -w" -o "${out_dir}/liblyrebird.so" ./cmd/lyrebird
  )

  echo "==> Building libsnowflake.so (Snowflake) for ${abi} (GOOS=linux GOARCH=${goarch}${goarm:+ GOARM=${goarm}})..."
  (
    cd "${BUILD_TMP}/snowflake/client"
    export CGO_ENABLED=0
    export GOOS=linux
    export GOARCH="${goarch}"
    if [[ -n "${goarm}" ]]; then
      export GOARM="${goarm}"
    else
      unset GOARM || true
    fi
    go build -trimpath -ldflags="-s -w" -o "${out_dir}/libsnowflake.so" .
  )

  chmod 0755 "${out_dir}/liblyrebird.so" "${out_dir}/libsnowflake.so"
  test -s "${out_dir}/liblyrebird.so"
  test -s "${out_dir}/libsnowflake.so"
  file "${out_dir}/liblyrebird.so" "${out_dir}/libsnowflake.so" || ls -lh "${out_dir}"
}

build_for_abi "arm64-v8a" "arm64"
build_for_abi "armeabi-v7a" "arm" "7"
build_for_abi "x86_64" "amd64"

rm -rf "${BUILD_TMP}"
echo "==> Successfully built and packaged Snowflake & obfs4 (lyrebird) binaries for all Android ABIs."