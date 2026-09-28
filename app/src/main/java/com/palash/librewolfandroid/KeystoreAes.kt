package com.palash.librewolfandroid

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key held in the Android Keystore, by alias.
 *
 * Used for the sync identity's private key and for the chain key on disk. The
 * wrapping key is generated inside the Keystore and is not exportable, so what
 * lands in the preference file is useless on its own and useless on any other
 * device — which is the property that makes storing a chain key locally
 * defensible at all.
 *
 * The stored format is `base64(iv || ciphertext+tag)`, which is what
 * [LoginStore] already writes for its vault. That is deliberate: the two are
 * kept apart by alias, and the vault is left alone rather than refactored onto
 * this in the same change as sync, because it is a working, device-verified
 * component and a rewrite of its crypto is not something to bundle with an
 * unrelated feature.
 */
object KeystoreAes {

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * The Keystore key for [alias], created on first use.
     *
     * The alias names the key rather than being derived from anything, so two
     * features cannot collide and one feature's key cannot be rotated by
     * accident when the other changes.
     */
    private fun key(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // The IV is generated per message by the cipher itself. A fixed
                // one would reuse a key/IV pair, which leaks the key outright.
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** [plain] sealed to [alias]. Returns null rather than throwing on a bad key. */
    fun seal(alias: String, plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val blob = cipher.iv + cipher.doFinal(plain)
        Base64.encodeToString(blob, Base64.NO_WRAP)
    }.getOrNull()

    /** The inverse. Null when the blob is not ours, or the key has changed. */
    fun open(alias: String, blob: String): ByteArray? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(alias),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }.getOrNull()
}
