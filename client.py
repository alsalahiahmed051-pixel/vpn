#!/usr/bin/env python3
"""
VPN Client.

Creates a TUN interface, sends an initial handshake to the server,
then bidirectionally encrypts/decrypts packets between TUN and UDP.

Usage:
    sudo python3 client.py --config config/client.yaml
"""

import argparse
import ipaddress
import logging
import select
import signal
import socket
import subprocess
import sys
import threading
import time

from vpn.config import load, CLIENT_DEFAULTS
from vpn.crypto import Cipher, CryptoError, derive_key
from vpn.tun import TunDevice
from vpn import protocol

log = logging.getLogger("vpn-client")


class VpnClient:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.cipher = Cipher(derive_key(cfg["password"]))
        self._stop = False

    def stop(self, *_):
        log.info("Shutdown requested")
        self._stop = True

    def run(self):
        # Resolve server address once at startup
        server_addr = (
            socket.gethostbyname(self.cfg["server_host"]),
            self.cfg["server_port"],
        )
        log.info("Server endpoint: %s:%d", *server_addr)

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # connect() lets us use send()/recv() and filters incoming packets
        sock.connect(server_addr)

        with TunDevice(self.cfg["tun_name"], mtu=self.cfg["mtu"]) as tun:
            tun.configure(self.cfg["tun_ip"])
            log.info("TUN %s up with %s", tun.name, self.cfg["tun_ip"])

            self._maybe_set_default_route(server_addr[0])
            self._send_handshake(sock)

            signal.signal(signal.SIGINT, self.stop)
            signal.signal(signal.SIGTERM, self.stop)

            ka_thread = threading.Thread(
                target=self._keepalive_loop, args=(sock,), daemon=True
            )
            ka_thread.start()

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

        sock.close()
        log.info("Client stopped")

    # ---- packet handling --------------------------------------------------

    def _send_handshake(self, sock: socket.socket):
        ip = ipaddress.ip_interface(self.cfg["tun_ip"]).ip
        payload = ip.packed
        framed = protocol.pack(protocol.TYPE_HANDSHAKE, payload)
        sock.send(self.cipher.encrypt(framed))
        log.info("Handshake sent (tunnel IP %s)", ip)

    def _keepalive_loop(self, sock: socket.socket):
        interval = self.cfg["keepalive_seconds"]
        while not self._stop:
            time.sleep(interval)
            try:
                framed = protocol.pack(protocol.TYPE_KEEPALIVE)
                sock.send(self.cipher.encrypt(framed))
            except OSError:
                pass

    def _from_udp(self, sock: socket.socket, tun: TunDevice):
        try:
            data = sock.recv(65535)
        except OSError:
            return
        try:
            plaintext = self.cipher.decrypt(data)
            msg_type, payload = protocol.unpack(plaintext)
        except (CryptoError, ValueError) as e:
            log.warning("Bad packet from server: %s", e)
            return
        if msg_type == protocol.TYPE_DATA:
            tun.write(payload)

    def _from_tun(self, sock: socket.socket, tun: TunDevice):
        try:
            packet = tun.read()
        except OSError:
            return
        framed = protocol.pack(protocol.TYPE_DATA, packet)
        try:
            sock.send(self.cipher.encrypt(framed))
        except OSError as e:
            log.warning("send failed: %s", e)

    # ---- routing ----------------------------------------------------------

    def _maybe_set_default_route(self, server_ip: str):
        """If route_all_traffic is true, route 0.0.0.0/0 through the VPN.

        We pin a /32 route to the server through the *current* default
        gateway first, otherwise the encrypted UDP packets would loop back
        through the VPN itself.
        """
        if not self.cfg.get("route_all_traffic"):
            return
        try:
            # Find current default gateway and its interface
            out = subprocess.check_output(
                ["ip", "route", "show", "default"], text=True
            ).split()
            # Format: "default via <gw> dev <iface> ..."
            gw = out[out.index("via") + 1]
            iface = out[out.index("dev") + 1]
            log.info("Current default: gw=%s dev=%s", gw, iface)

            subprocess.run(
                ["ip", "route", "add", f"{server_ip}/32", "via", gw, "dev", iface],
                check=True,
            )
            subprocess.run(["ip", "route", "del", "default"], check=True)
            subprocess.run(
                ["ip", "route", "add", "default", "dev", self.cfg["tun_name"]],
                check=True,
            )
            log.info("Default route now goes through %s", self.cfg["tun_name"])
        except (subprocess.CalledProcessError, ValueError, IndexError) as e:
            log.warning("Could not adjust default route: %s", e)


def main():
    ap = argparse.ArgumentParser(description="Python VPN client")
    ap.add_argument(
        "--config", "-c", default="config/client.yaml", help="path to YAML config"
    )
    args = ap.parse_args()

    cfg = load(args.config, CLIENT_DEFAULTS)
    logging.basicConfig(
        level=cfg["log_level"],
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    try:
        VpnClient(cfg).run()
    except RuntimeError as e:
        log.error(str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
