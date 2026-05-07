#!/usr/bin/env bash
# سكربت إيقاف وتنظيف الـ VPN
# Teardown: remove the TUN interface and revert NAT rules.
#
# Usage:  sudo bash scripts/teardown.sh [WAN_INTERFACE]

set -uo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: must run as root (use sudo)" >&2
  exit 1
fi

WAN_IF="${1:-$(ip route show default | awk '/default/ {print $5; exit}')}"
VPN_SUBNET="${VPN_SUBNET:-10.8.0.0/24}"
VPN_PORT="${VPN_PORT:-51820}"

echo "==> Removing TUN interface vpn0 (if present)..."
ip link delete vpn0 2>/dev/null || true

if [[ -n "$WAN_IF" ]]; then
  echo "==> Removing iptables rules..."
  iptables -t nat -D POSTROUTING -s "$VPN_SUBNET" -o "$WAN_IF" -j MASQUERADE 2>/dev/null || true
  iptables -D FORWARD -i vpn0 -o "$WAN_IF" -j ACCEPT 2>/dev/null || true
  iptables -D FORWARD -i "$WAN_IF" -o vpn0 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
  iptables -D INPUT -p udp --dport "$VPN_PORT" -j ACCEPT 2>/dev/null || true
fi

echo "==> Done."
