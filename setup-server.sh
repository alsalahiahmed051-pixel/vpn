#!/usr/bin/env bash
# سكربت إعداد سيرفر الـ VPN
# Server-side setup: enable IP forwarding and configure NAT (MASQUERADE).
# Run once on the server BEFORE starting the VPN process.
#
# Usage:  sudo bash scripts/setup-server.sh [WAN_INTERFACE]
# Example: sudo bash scripts/setup-server.sh eth0

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: must run as root (use sudo)" >&2
  exit 1
fi

# Auto-detect default interface if not given
WAN_IF="${1:-$(ip route show default | awk '/default/ {print $5; exit}')}"

if [[ -z "$WAN_IF" ]]; then
  echo "ERROR: could not detect WAN interface. Pass it as the first argument." >&2
  exit 1
fi

VPN_SUBNET="${VPN_SUBNET:-10.8.0.0/24}"
VPN_PORT="${VPN_PORT:-51820}"

echo "==> WAN interface: $WAN_IF"
echo "==> VPN subnet:    $VPN_SUBNET"
echo "==> VPN UDP port:  $VPN_PORT"

# 1) Enable IPv4 forwarding (persistent)
echo "==> Enabling IPv4 forwarding..."
echo 1 > /proc/sys/net/ipv4/ip_forward
sed -i 's/^#\?net.ipv4.ip_forward.*/net.ipv4.ip_forward=1/' /etc/sysctl.conf || true
grep -q '^net.ipv4.ip_forward=1' /etc/sysctl.conf || \
  echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf

# 2) NAT outgoing traffic from the VPN subnet
echo "==> Adding iptables MASQUERADE for $VPN_SUBNET..."
iptables -t nat -C POSTROUTING -s "$VPN_SUBNET" -o "$WAN_IF" -j MASQUERADE 2>/dev/null \
  || iptables -t nat -A POSTROUTING -s "$VPN_SUBNET" -o "$WAN_IF" -j MASQUERADE

# 3) Allow forwarding between the VPN interface and the WAN
iptables -C FORWARD -i vpn0 -o "$WAN_IF" -j ACCEPT 2>/dev/null \
  || iptables -A FORWARD -i vpn0 -o "$WAN_IF" -j ACCEPT
iptables -C FORWARD -i "$WAN_IF" -o vpn0 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
  || iptables -A FORWARD -i "$WAN_IF" -o vpn0 -m state --state RELATED,ESTABLISHED -j ACCEPT

# 4) Allow incoming UDP on the VPN port
iptables -C INPUT -p udp --dport "$VPN_PORT" -j ACCEPT 2>/dev/null \
  || iptables -A INPUT -p udp --dport "$VPN_PORT" -j ACCEPT

echo
echo "==> Done. Save iptables rules so they survive a reboot, e.g.:"
echo "    apt-get install -y iptables-persistent"
echo "    netfilter-persistent save"
echo
echo "==> Now run the server:"
echo "    sudo python3 server.py --config config/server.yaml"
