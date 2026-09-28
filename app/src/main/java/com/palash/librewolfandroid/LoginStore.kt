package com.palash.librewolfandroid

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class Login(val site: String, val username: String, val password: String)

/**
 * Encrypted on-device password vault. Gecko autofill prompts are connected to
 * this store, while credentials are sealed with an AES256-GCM key in the
 * Android Keystore and never leave the device.
 */
class LoginStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun all(): List<Login> {
        val out = mutableListOf<Login>()
        val json = prefs.getString(K_LIST, null)?.let { decrypt(it) } ?: return out
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Login(o.optString("s"), o.optString("u"), o.optString("p")))
        }
        return out
    }

    /**
     * Saved logins that apply to [pageUrl]. Matches on scheme+host+port so a
     * login for one origin is never offered on another.
     */
    @Synchronized
    fun forOrigin(pageUrl: String): List<Login> {
        val host = runCatching { java.net.URI(pageUrl).let { "${it.scheme}://${it.host}:${it.port}" } }
            .getOrNull() ?: return emptyList()
        return all().filter { runCatching { java.net.URI(it.site).let { s -> "${s.scheme}://${s.host}:${s.port}" } }
            .getOrNull() == host }
    }

    @Synchronized
    fun add(site: String, username: String, password: String) {
        if ((!site.startsWith("https://") && !site.startsWith("http://")) || username.isBlank()) return
        val entries = all().toMutableList()
        entries.removeAll { it.site == site && it.username == username }
        entries.add(0, Login(site, username, password))
        save(entries)
    }

    @Synchronized
    fun remove(site: String, username: String) =
        save(all().filter { !(it.site == site && it.username == username) })

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(entries: List<Login>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("s", e.site).put("u", e.username).put("p", e.password))
        }
        try {
            prefs.edit { putString(K_LIST, encrypt(arr.toString())) }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PREFS = "librewolf_vault"
        private const val K_LIST = "logins"
        private const val ALIAS = "librewolf_vault_key"
    }
}
