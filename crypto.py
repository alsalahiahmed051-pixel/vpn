"""
AES-256-GCM authenticated encryption for VPN packets.

We use a counter-based nonce (8 bytes counter + 4 bytes random) to prevent
nonce reuse and detect replays. Key is derived from password via scrypt.
"""

import os
import struct
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt


class CryptoError(Exception):
    """Raised on decrypt/auth failure."""


class Cipher:
    """
    AES-256-GCM with embedded nonce.

    Wire format produced by encrypt():
        [ 12-byte nonce | ciphertext | 16-byte GCM auth tag ]

    The 12-byte nonce is sent in the clear (this is standard for AEAD ciphers;
    the auth tag protects against tampering).
    """

    NONCE_SIZE = 12
    KEY_SIZE = 32  # AES-256

    def __init__(self, key: bytes):
        if len(key) != self.KEY_SIZE:
            raise ValueError(f"Key must be {self.KEY_SIZE} bytes (AES-256)")
        self._aead = AESGCM(key)
        # Random session prefix + monotonic counter for unique nonces
        self._nonce_prefix = os.urandom(4)
        self._counter = 0

    def _next_nonce(self) -> bytes:
        self._counter += 1
        return self._nonce_prefix + struct.pack(">Q", self._counter)

    def encrypt(self, plaintext: bytes, aad: bytes = b"") -> bytes:
        nonce = self._next_nonce()
        ct = self._aead.encrypt(nonce, plaintext, aad)
        return nonce + ct

    def decrypt(self, data: bytes, aad: bytes = b"") -> bytes:
        if len(data) < self.NONCE_SIZE + 16:
            raise CryptoError("Packet too short to contain nonce + tag")
        nonce, ct = data[: self.NONCE_SIZE], data[self.NONCE_SIZE :]
        try:
            return self._aead.decrypt(nonce, ct, aad)
        except Exception as e:
            raise CryptoError(f"Decryption failed: {e}") from e


def derive_key(password: str, salt: bytes = b"vpn-project-v1-salt") -> bytes:
    """
    Derive a 32-byte key from a password using scrypt.

    Both client and server must use the *same* password and salt. In a real
    deployment, prefer a long random shared secret (32 bytes hex) over a
    human-typed password.
    """
    if not password:
        raise ValueError("Password cannot be empty")
    kdf = Scrypt(salt=salt, length=Cipher.KEY_SIZE, n=2**14, r=8, p=1)
    return kdf.derive(password.encode("utf-8"))
