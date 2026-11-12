package dev.conduit.net

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedMac(
    val id: String,
    val name: String,
    val port: Int,
    val hosts: List<String>,
    val key: ByteArray,
)

/**
 * Stores the paired Mac. The 32-byte pairing key is wrapped with a hardware-backed
 * Android Keystore key, so it never sits in plain text in shared preferences.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    fun load(): PairedMac? {
        val id = prefs.getString("macId", null) ?: return null
        val wrapped = prefs.getString("key", null) ?: return null
        val key = runCatching { unwrap(wrapped) }.getOrNull() ?: return null
        return PairedMac(
            id = id,
            name = prefs.getString("macName", "Mac") ?: "Mac",
            port = prefs.getInt("port", 47820),
            hosts = prefs.getString("hosts", "")!!.split(',').filter { it.isNotBlank() },
            key = key,
        )
    }

    fun save(mac: PairedMac) {
        prefs.edit()
            .putString("macId", mac.id)
            .putString("macName", mac.name)
            .putInt("port", mac.port)
            .putString("hosts", mac.hosts.joinToString(","))
            .putString("key", wrap(mac.key))
            .apply()
    }

    fun updateHost(host: String, port: Int) {
        val hosts = (listOf(host) + (prefs.getString("hosts", "") ?: "").split(','))
            .filter { it.isNotBlank() }.distinct().take(4)
        prefs.edit().putString("hosts", hosts.joinToString(",")).putInt("port", port).apply()
    }

    fun clear() {
        prefs.edit().remove("macId").remove("macName").remove("port").remove("hosts").remove("key").apply()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val out = cipher.iv + cipher.doFinal(raw)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }

    companion object {
        private const val ALIAS = "conduit-pairing-wrap"

        /** Parses `conduit://pair?...` from the Mac's QR code (or the copy-paste code). */
        fun parsePairingUri(text: String): PairedMac? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "conduit" || uri.host != "pair") return null
            val key = uri.getQueryParameter("key")?.let {
                runCatching { Base64.decode(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
            } ?: return null
            if (key.size != 32) return null
            return PairedMac(
                id = uri.getQueryParameter("id") ?: return null,
                name = uri.getQueryParameter("name") ?: "Mac",
                port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null,
                hosts = uri.getQueryParameter("hosts")?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                key = key,
            )
        }
    }
}
