package dev.conduit.net

import dev.conduit.crypto.SecureChannel
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object Channel {
    const val CONTROL = 0
    const val MIRROR = 1
    const val CAMERA = 2
    const val FILE = 3
}

/** One authenticated, encrypted TCP connection to the Mac. */
class Connection private constructor(
    private val socket: Socket,
    private val secure: SecureChannel,
) {
    @Volatile var isOpen = true
        private set

    fun send(json: JSONObject) {
        try {
            secure.sendJson(json.toString())
        } catch (e: IOException) {
            close(); throw e
        }
    }

    fun send(type: String, build: JSONObject.() -> Unit = {}) = send(JSONObject().put("type", type).apply(build))

    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        try {
            secure.sendBinary(data, offset, length)
        } catch (e: IOException) {
            close(); throw e
        }
    }

    fun receive(): SecureChannel.Frame = secure.receive()

    fun close() {
        isOpen = false
        runCatching { socket.close() }
    }

    companion object {
        fun open(host: String, port: Int, channel: Int, key: ByteArray, deviceId: String, deviceName: String): Connection {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), 2500)
                socket.soTimeout = 15000 // handshake timeout; control channel resets it after auth
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                val secure = SecureChannel.clientHandshake(input, output, channel, key)
                secure.sendJson(
                    JSONObject().put("type", "auth").put("deviceId", deviceId).put("deviceName", deviceName).toString()
                )
                val reply = secure.receive()
                if (reply !is SecureChannel.Frame.Json || JSONObject(reply.text).optString("type") != "auth-ok") {
                    throw IOException("Mac rejected the connection")
                }
                socket.soTimeout = if (channel == Channel.CONTROL) 420_000 else 0 // > keepalive interval
                return Connection(socket, secure)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
