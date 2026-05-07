#!/usr/bin/env bash
# سكربت اختبار محلي شامل — يشغّل السيرفر والعميل في network namespaces منفصلتين
# على نفس الجهاز ويتحقق من نجاح النفق.
#
# Local end-to-end test using Linux network namespaces. No second machine
# or VPS required. Works on any Linux (including WSL2 with a recent kernel).
#
# Usage:  sudo bash scripts/local-test.sh

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: must run as root (use sudo)" >&2
  exit 1
fi

PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
PASSWORD=${PASSWORD:-$(openssl rand -hex 16 2>/dev/null || echo "test-password-$(date +%s)")}
SRV_NS=vpn-srv-ns
CLI_NS=vpn-cli-ns

cleanup() {
  echo "==> Cleaning up..."
  [[ -f /tmp/vpn-server.pid ]] && kill "$(cat /tmp/vpn-server.pid)" 2>/dev/null || true
  [[ -f /tmp/vpn-client.pid ]] && kill "$(cat /tmp/vpn-client.pid)" 2>/dev/null || true
  ip netns del "$SRV_NS" 2>/dev/null || true
  ip netns del "$CLI_NS" 2>/dev/null || true
  ip link del veth-srv 2>/dev/null || true
  rm -f /tmp/vpn-server.yaml /tmp/vpn-client.yaml
  rm -f /tmp/vpn-server.pid /tmp/vpn-client.pid
}

# Clean any prior run, then arm cleanup-on-exit
cleanup
trap cleanup EXIT

echo "==> Creating network namespaces"
ip netns add "$SRV_NS"
ip netns add "$CLI_NS"
ip netns exec "$SRV_NS" ip link set lo up
ip netns exec "$CLI_NS" ip link set lo up

echo "==> Creating veth pair to act as the carrier 'internet' between them"
ip link add veth-srv type veth peer name veth-cli
ip link set veth-srv netns "$SRV_NS"
ip link set veth-cli netns "$CLI_NS"
ip netns exec "$SRV_NS" ip addr add 192.0.2.1/24 dev veth-srv
ip netns exec "$CLI_NS" ip addr add 192.0.2.2/24 dev veth-cli
ip netns exec "$SRV_NS" ip link set veth-srv up
ip netns exec "$CLI_NS" ip link set veth-cli up

echo "==> Carrier sanity check: client → server on 192.0.2.1"
ip netns exec "$CLI_NS" ping -c1 -W1 192.0.2.1 >/dev/null && echo "    ✓ carrier link up"

echo "==> Writing temp configs (random password length: ${#PASSWORD})"
cat > /tmp/vpn-server.yaml <<EOF
listen: "0.0.0.0"
port: 51820
tun_name: "vpn0"
tun_ip: "10.8.0.1/24"
mtu: 1400
keepalive_seconds: 25
log_level: "INFO"
password: "$PASSWORD"
EOF

cat > /tmp/vpn-client.yaml <<EOF
server_host: "192.0.2.1"
server_port: 51820
tun_name: "vpn0"
tun_ip: "10.8.0.2/24"
mtu: 1400
keepalive_seconds: 25
route_all_traffic: false
log_level: "INFO"
password: "$PASSWORD"
EOF

echo "==> Starting server in $SRV_NS"
ip netns exec "$SRV_NS" python3 "$PROJECT_DIR/server.py" \
  -c /tmp/vpn-server.yaml > /tmp/vpn-server.log 2>&1 &
echo $! > /tmp/vpn-server.pid
sleep 1

echo "==> Starting client in $CLI_NS"
ip netns exec "$CLI_NS" python3 "$PROJECT_DIR/client.py" \
  -c /tmp/vpn-client.yaml > /tmp/vpn-client.log 2>&1 &
echo $! > /tmp/vpn-client.pid
sleep 2

echo
echo "==> Ping inside the encrypted tunnel: client (10.8.0.2) → server (10.8.0.1)"
echo "    Each ICMP echo is wrapped in AES-256-GCM and shipped over UDP/51820."
echo
if ip netns exec "$CLI_NS" ping -c3 -W2 10.8.0.1; then
  RESULT=0
  echo
  echo "✅ SUCCESS — encrypted tunnel is working end-to-end."
else
  RESULT=1
  echo
  echo "❌ FAIL — see logs below."
fi

echo
echo "---- server log ----"; sed 's/^/  /' /tmp/vpn-server.log
echo "---- client log ----"; sed 's/^/  /' /tmp/vpn-client.log

exit $RESULT
