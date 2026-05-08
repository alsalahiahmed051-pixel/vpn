package com.vpn

/**
 * Wire protocol — مطابق لـ Python vpn/protocol.py
 *
 * Post-decryption frame:
 *   [ version(1B) | type(1B) | payload ]
 *
 * Types: DATA=0, KEEPALIVE=1, HANDSHAKE=2
 */
object Protocol {
    const val VERSION: Byte = 1
    const val TYPE_DATA: Byte = 0
    const val TYPE_KEEPALIVE: Byte = 1
    const val TYPE_HANDSHAKE: Byte = 2

    fun pack(type: Byte, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(VERSION, type) + payload

    fun unpack(data: ByteArray): Pair<Byte, ByteArray> {
        require(data.size >= 2) { "Frame too short" }
        require(data[0] == VERSION) { "Unsupported protocol version: ${data[0]}" }
        return Pair(data[1], data.copyOfRange(2, data.size))
    }
}
