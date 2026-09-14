#!/usr/bin/env bash
# Build app/src/main/jniLibs/arm64-v8a/libtailscale.so.
#
# This exists because the binary it produces was previously unreproducible: no
# recipe was recorded anywhere, so nobody could tell which features were compiled
# in. That is not a theoretical problem — a build with ts_omit_acme shipped, which
# silently removed TLS certificate provisioning. `tailscale serve --https` still
# advertised an HTTPS URL and every handshake failed, with nothing in the binary
# to explain why. `tailscale version` could not even report its own build tags,
# because UPX strips Go build metadata.
#
# Two things about the artifact are deliberate and easy to get wrong:
#
#   1. It is not a shared library despite the .so name. It is a static PIE
#      executable, named .so so that Android packages it into jniLibs and
#      extracts it with the exec bit set. It is the combined tailscale +
#      tailscaled binary (ts_include_cli), dispatching on argv[0].
#
#   2. It carries a local patch (patches/tailscale-tcp-socket.patch) so the local
#      API works over loopback TCP instead of a unix socket. tailscaled runs here
#      as the shell uid while its clients run as app uids, and no directory is
#      both shell-writable and app-readable, so a unix socket is unusable.
#      TailscaleLauncher passes --socket 127.0.0.1:<port> to both daemon and CLI.
#
#      Four upstream layers reject that, and each only becomes visible once the
#      previous is fixed, so do not expect a partial patch to work:
#        - safesocket dials/listens unix-only    -> connection refused
#        - ipnauth's peercred lookup fails on TCP -> 401 unsupported connection type
#        - permissions are granted only to unix socks -> access denied
#        - ACME's DNS lookup has no resolver to use -> TLS handshake internal error
#
#      That last one is not about sockets at all, it is about Android having no
#      /etc/resolv.conf: a cgo-free Go binary then falls back to 127.0.0.1:53 and
#      every lookup fails. Reaching the control plane still works (bootstrap DNS
#      has hardcoded addresses), so the node looks perfectly healthy right up
#      until a cert is needed. The patch gives the ACME client its own resolver,
#      but ONLY when no resolv.conf exists; override with TS_ACME_DNS.
#      The patch treats a LOOPBACK TCP local-API connection as equivalent to the
#      unix socket at all three. Consequence, stated plainly: any local process
#      that can open 127.0.0.1:<port> gets full control of tailscaled, where a
#      unix socket would have restricted it by uid. That is inherent to putting
#      the local API on TCP; the shipped binary has always had this property.
#
#   3. GOOS=android, not linux. Tailscale reports its GOOS to the control plane.
#      Build this as linux and the coordination server sees an android node
#      return as linux, flags it ("node OS changed since last connection, was
#      node state copied between devices?"), and strips the node's DNSName --
#      after which cert requests fail with the deeply misleading "your Tailscale
#      account does not support getting TLS certs". Nothing is wrong with the
#      account; the node just no longer has a name to certify.
#
#      GOOS=android produces a PIE, which UPX only compresses from v5 onward.
#      UPX 4.x fails with "CantPackException: bad e_shstrtab".
#
# Usage:  tools/build-libtailscale.sh [tailscale-version]
set -euo pipefail

TS_VERSION="${1:-v1.96.4}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a/libtailscale.so"
PATCH="$(cd "$(dirname "$0")" && pwd)/patches/tailscale-tcp-socket.patch"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Feature set. Omit only what is meaningless on an Android head unit; keep every
# connectivity, serve and TLS feature. acme is NOT omitted -- that is the whole
# point of this file. Trimming further is possible but each entry is a feature
# someone may depend on, so justify additions here rather than in a shell history.
TAGS=ts_include_cli
TAGS+=,ts_omit_aws,ts_omit_kube,ts_omit_cloud,ts_omit_synology
TAGS+=,ts_omit_bird,ts_omit_dbus,ts_omit_networkmanager,ts_omit_resolved
TAGS+=,ts_omit_desktop_sessions,ts_omit_systray,ts_omit_colorable
TAGS+=,ts_omit_completion,ts_omit_completion_scripts,ts_omit_webbrowser
TAGS+=,ts_omit_qrcodes,ts_omit_clientupdate,ts_omit_drive,ts_omit_taildrop
TAGS+=,ts_omit_webclient,ts_omit_tap,ts_omit_tpm,ts_omit_capture
TAGS+=,ts_omit_debugportmapper,ts_omit_debugeventbus,ts_omit_doctor
TAGS+=,ts_omit_hujsonconf,ts_omit_identityfederation,ts_omit_oauthkey
TAGS+=,ts_omit_sdnotify

echo "==> cloning tailscale $TS_VERSION"
git clone --depth 1 --branch "$TS_VERSION" https://github.com/tailscale/tailscale.git "$WORK/ts"

echo "==> applying TCP-socket patch"
git -C "$WORK/ts" apply "$PATCH"

echo "==> building android/arm64"
( cd "$WORK/ts" && GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -tags "$TAGS" -trimpath -ldflags "-s -w" -o "$WORK/libtailscale.so" ./cmd/tailscaled )

# UPX < 5 cannot pack the PIE that GOOS=android produces. Check up front rather
# than letting the build finish and silently ship an uncompressed 22MB binary.
upx_major=$(upx --version 2>/dev/null | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')
if [ -z "$upx_major" ] || [ "$upx_major" -lt 5 ]; then
    echo "FATAL: need UPX >= 5 to compress a PIE (have: $(upx --version 2>/dev/null | head -1))" >&2
    exit 1
fi

echo "==> compressing"
upx --best -q "$WORK/libtailscale.so"

# Fail loudly rather than shipping another ACME-less binary.
echo "==> verifying ACME is present"
if ! upx -d -o "$WORK/check.bin" "$WORK/libtailscale.so" >/dev/null 2>&1; then
    echo "FATAL: could not decompress for verification" >&2; exit 1
fi
if ! LC_ALL=C grep -qa "acme-v02.api.letsencrypt.org" "$WORK/check.bin"; then
    echo "FATAL: no ACME in the built binary -- 'tailscale cert' and 'serve --https' would fail" >&2
    exit 1
fi

install -m 0644 "$WORK/libtailscale.so" "$OUT"
echo "==> wrote $OUT ($(wc -c < "$OUT") bytes)"
echo "    verify on device:  tailscale cert --help   # must print 'Get TLS certs'"
