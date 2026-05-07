"""
Wire protocol for VPN packets (post-decryption).

After AES-GCM decryption, every payload starts with this 2-byte header:

    +---------+-------+----------------------------+
    | version | type  |          payload           |
    +---------+-------+----------------------------+
       1 byte  1 byte         variable

Types:
    DATA       (0)  - payload is a raw IPv4 packet to inject into TUN
    KEEPALIVE  (1)  - empty payload, used to keep NAT mappings alive
    HANDSHAKE  (2)  - initial registration: payload is client tun-IP (4 bytes)
"""

import struct

PROTOCOL_VERSION = 1

TYPE_DATA = 0
TYPE_KEEPALIVE = 1
TYPE_HANDSHAKE = 2


def pack(msg_type: int, payload: bytes = b"") -> bytes:
    return struct.pack("!BB", PROTOCOL_VERSION, msg_type) + payload


def unpack(data: bytes):
    if len(data) < 2:
        raise ValueError("Packet too short for header")
    version, msg_type = struct.unpack_from("!BB", data, 0)
    if version != PROTOCOL_VERSION:
        raise ValueError(f"Unsupported protocol version: {version}")
    return msg_type, data[2:]


def parse_ipv4_addresses(packet: bytes):
    """
    Extract (src_ip, dst_ip) from an IPv4 packet header.
    Returns a tuple of dotted-decimal strings, or (None, None) if not IPv4.
    """
    if len(packet) < 20:
        return None, None
    version = (packet[0] >> 4) & 0xF
    if version != 4:
        return None, None
    src = ".".join(str(b) for b in packet[12:16])
    dst = ".".join(str(b) for b in packet[16:20])
    return src, dst
