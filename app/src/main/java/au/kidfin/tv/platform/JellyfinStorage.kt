// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import au.kidfin.tv.jellyfin.ServerConfig
import au.kidfin.tv.jellyfin.ServerConfigCodec
import au.kidfin.tv.jellyfin.ServerConfigStore
import au.kidfin.tv.jellyfin.TokenStore
import au.kidfin.tv.session.CorruptStateException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class AtomicBytes(file: File) {
    private val atomic = AtomicFile(file)

    fun read(): ByteArray? = try {
        atomic.readFully()
    } catch (e: FileNotFoundException) {
        null
    }

    fun write(bytes: ByteArray) {
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    fun delete() = atomic.delete()
}

class FileServerConfigStore(file: File) : ServerConfigStore {
    private val file = AtomicBytes(file)

    override fun load(): ServerConfig? = try {
        file.read()?.toString(Charsets.UTF_8)?.let(ServerConfigCodec::decode)
    } catch (e: IOException) {
        throw CorruptStateException("unreadable", e)
    }

    override fun save(config: ServerConfig) = file.write(ServerConfigCodec.encode(config).toByteArray(Charsets.UTF_8))

    override fun clear() = file.delete()
}

/**
 * Access token encrypted with AES-GCM under a non-exportable Android Keystore key. The file
 * lives in noBackupFilesDir; the key never leaves the device, so a copied file is useless.
 */
class KeystoreTokenStore(file: File) : TokenStore {
    private val file = AtomicBytes(file)

    override fun load(): String? {
        val text = try {
            file.read()?.toString(Charsets.US_ASCII)
        } catch (e: IOException) {
            null
        } ?: return null
        return try {
            val (iv, sealed) = text.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(sealed).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        file.write(text.toByteArray(Charsets.US_ASCII))
    }

    override fun clear() = file.delete()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kidfin_jellyfin_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
