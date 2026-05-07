#!/usr/bin/env python3
"""
VPN Server.

Listens on UDP, decrypts incoming packets, writes them to a TUN interface.
Reads packets from TUN, encrypts, and forwards to the appropriate client
based on destination IP.

Usage:
    sudo python3 server.py --config config/server.yaml
"""

import argparse
import logging
import select
import signal
import socket
import sys
import time
from typing import Dict, Tuple

from vpn.config import load, SERVER_DEFAULTS
from vpn.crypto import Cipher, CryptoError, derive_key
from vpn.tun import TunDevice
from vpn import protocol

log = logging.getLogger("vpn-server")


class VpnServer:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.cipher = Cipher(derive_key(cfg["password"]))
        # Map: client tunnel-IP (str) -> (UDP addr, last_seen_unix_ts)
        self.clients: Dict[str, Tuple[Tuple[str, int], float]] = {}
        self._stop = False

    def stop(self, *_):
        log.info("Shutdown requested")
        self._stop = True

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.cfg["listen"], self.cfg["port"]))
        log.info("Listening on %s:%d/udp", self.cfg["listen"], self.cfg["port"])

        with TunDevice(self.cfg["tun_name"], mtu=self.cfg["mtu"]) as tun:
            tun.configure(self.cfg["tun_ip"])
            log.info("TUN %s up with %s", tun.name, self.cfg["tun_ip"])

            signal.signal(signal.SIGINT, self.stop)
            signal.signal(signal.SIGTERM, self.stop)

            while not self._stop:
                try:
                    ready, _, _ = select.select([sock, tun.fd], [], [], 1.0)
                except (InterruptedError, OSError):
                    continue
                for fd in ready:
                    if fd is sock:
                        self._from_udp(sock, tun)
                    else:
                        self._from_tun(sock, tun)
                self._expire_clients()

        sock.close()
        log.info("Server stopped")

    # ---- packet handling --------------------------------------------------

    def _from_udp(self, sock: socket.socket, tun: TunDevice):
        try:
            data, addr = sock.recvfrom(65535)
        except OSError:
            return
        try:
            plaintext = self.cipher.decrypt(data)
            msg_type, payload = protocol.unpack(plaintext)
        except (CryptoError, ValueError) as e:
            log.warning("Bad packet from %s: %s", addr, e)
            return

        if msg_type == protocol.TYPE_DATA:
            src_ip, _ = protocol.parse_ipv4_addresses(payload)
            if src_ip is None:
                return
            self.clients[src_ip] = (addr, time.time())
            tun.write(payload)
        elif msg_type == protocol.TYPE_HANDSHAKE:
            if len(payload) >= 4:
                ip = ".".join(str(b) for b in payload[:4])
                self.clients[ip] = (addr, time.time())
                log.info("Client %s registered from %s", ip, addr)
        elif msg_type == protocol.TYPE_KEEPALIVE:
            # Update last-seen for any matching client
            for ip, (caddr, _) in list(self.clients.items()):
                if caddr == addr:
                    self.clients[ip] = (addr, time.time())

    def _from_tun(self, sock: socket.socket, tun: TunDevice):
        try:
            packet = tun.read()
        except OSError:
            return
        _, dst_ip = protocol.parse_ipv4_addresses(packet)
        if dst_ip is None:
            return
        client = self.clients.get(dst_ip)
        if client is None:
            # No known client for this destination; drop.
            return
        addr, _ = client
        framed = protocol.pack(protocol.TYPE_DATA, packet)
        try:
            sock.sendto(self.cipher.encrypt(framed), addr)
        except OSError as e:
            log.warning("send to %s failed: %s", addr, e)

    def _expire_clients(self):
        timeout = self.cfg["keepalive_seconds"] * 4
        now = time.time()
        for ip, (_, ts) in list(self.clients.items()):
            if now - ts > timeout:
                log.info("Client %s timed out", ip)
                self.clients.pop(ip, None)


def main():
    ap = argparse.ArgumentParser(description="Python VPN server")
    ap.add_argument(
        "--config", "-c", default="config/server.yaml", help="path to YAML config"
    )
    args = ap.parse_args()

    cfg = load(args.config, SERVER_DEFAULTS)
    logging.basicConfig(
        level=cfg["log_level"],
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    try:
        VpnServer(cfg).run()
    except RuntimeError as e:
        log.error(str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
