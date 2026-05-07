"""
Linux TUN device wrapper.

A TUN device is a virtual network interface that delivers Layer-3 IP packets
to userspace. We open /dev/net/tun and ioctl() it into TUN mode (no Ethernet
header, no packet info prefix). The kernel then routes traffic to/from this
interface and we read/write raw IP packets via the file descriptor.

This is exactly how WireGuard, OpenVPN, and Tailscale move packets.
"""

import os
import fcntl
import struct
import subprocess

# Linux kernel constants (from <linux/if_tun.h>)
TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000  # don't prepend the 4-byte protocol info header


class TunDevice:
    """
    Layer-3 TUN device. Read/write raw IPv4 packets.

    Requires CAP_NET_ADMIN (typically: run as root).
    """

    def __init__(self, name: str = "vpn0", mtu: int = 1400):
        self.name = name
        self.mtu = mtu
        try:
            self.fd = os.open("/dev/net/tun", os.O_RDWR)
        except OSError as e:
            raise RuntimeError(
                "Cannot open /dev/net/tun. TUN/TAP not available "
                "(is this Linux? do you have permission?)"
            ) from e

        # Ask kernel to allocate a TUN interface with the requested name.
        ifr = struct.pack("16sH", name.encode("ascii"), IFF_TUN | IFF_NO_PI)
        try:
            fcntl.ioctl(self.fd, TUNSETIFF, ifr)
        except OSError as e:
            os.close(self.fd)
            raise RuntimeError(
                f"Failed to create TUN interface '{name}': {e}. "
                "You probably need to run as root (sudo)."
            ) from e

    # --- I/O ---------------------------------------------------------------

    def read(self, n: int = 65535) -> bytes:
        return os.read(self.fd, n)

    def write(self, packet: bytes) -> int:
        return os.write(self.fd, packet)

    def close(self) -> None:
        try:
            os.close(self.fd)
        except OSError:
            pass

    def fileno(self) -> int:
        return self.fd

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    # --- Configuration helpers --------------------------------------------

    def configure(self, ip_cidr: str) -> None:
        """
        Assign an IP, set MTU, bring the interface up.

        Example: tun.configure("10.8.0.1/24")
        """
        _run(["ip", "addr", "add", ip_cidr, "dev", self.name])
        _run(["ip", "link", "set", "dev", self.name, "mtu", str(self.mtu)])
        _run(["ip", "link", "set", "dev", self.name, "up"])


def _run(cmd: list) -> None:
    """Run a command, raising on failure with a readable error."""
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            f"Command failed: {' '.join(cmd)}\n{e.stderr.strip()}"
        ) from e
