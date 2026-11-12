package dev.conduit.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted framing over a socket stream. See PROTOCOL.md.
 * One instance per connection; send/receive are each safe from one thread at a time
 * (send is synchronized, receive is called from the single reader thread).
 */
class SecureChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private var sendCounter = 0L
    private var recvCounter = 0L

    sealed class Frame {
        class Json(val text: String) : Frame()
        class Binary(val data: ByteArray) : Frame()
    }

    fun sendJson(json: String) = send(KIND_JSON, json.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val payload = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        send(KIND_BINARY, payload)
    }

    @Synchronized
    private fun send(kind: Byte, payload: ByteArray) {
        val plain = ByteArray(payload.size + 1)
        plain[0] = kind
        System.arraycopy(payload, 0, plain, 1, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKeySpec, GCMParameterSpec(128, nonce(sendCounter++)))
        val sealed = cipher.doFinal(plain)
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
    }

    /** Blocks until a frame arrives. Throws on EOF, tampering or a wrong key. */
    fun receive(): Frame {
        val len = input.readInt()
        if (len < 17 || len > MAX_FRAME) throw IOException("Bad frame length $len")
        val sealed = ByteArray(len)
        input.readFully(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, recvKeySpec, GCMParameterSpec(128, nonce(recvCounter++)))
        val plain = try {
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw IOException("Decryption failed (wrong pairing key?)", e)
        }
        val body = plain.copyOfRange(1, plain.size)
        return if (plain[0] == KIND_JSON) Frame.Json(String(body, Charsets.UTF_8)) else Frame.Binary(body)
    }

    companion object {
        private const val KIND_JSON: Byte = 0
        private const val KIND_BINARY: Byte = 1
        const val MAX_FRAME = 16 * 1024 * 1024
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte())
        private const val VERSION: Byte = 1

        /** Client side of the handshake (the phone always connects to the Mac). */
        fun clientHandshake(input: DataInputStream, output: DataOutputStream, channel: Int, pairingKey: ByteArray): SecureChannel {
            val rc = ByteArray(32).also { SecureRandom().nextBytes(it) }
            output.write(MAGIC)
            output.writeByte(VERSION.toInt())
            output.writeByte(channel)
            output.write(rc)
            output.flush()

            val reply = ByteArray(37)
            input.readFully(reply)
            if (!reply.copyOfRange(0, 4).contentEquals(MAGIC) || reply[4] != VERSION) {
                throw IOException("Not a Conduit server or unsupported version")
            }
            val rs = reply.copyOfRange(5, 37)
            val salt = rc + rs
            val c2s = hkdf(pairingKey, salt, "conduit-v1 c2s".toByteArray())
            val s2c = hkdf(pairingKey, salt, "conduit-v1 s2c".toByteArray())
            return SecureChannel(input, output, sendKey = c2s, recvKey = s2c)
        }

        private fun nonce(counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

        /** RFC 5869 HKDF-SHA256 with a 32-byte output (one block). */
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
            val prk = extract.doFinal(ikm)
            val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            expand.update(info)
            expand.update(1.toByte())
            return expand.doFinal()
        }
    }
}
