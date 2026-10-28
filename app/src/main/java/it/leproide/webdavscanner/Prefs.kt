/*
 * FastScanner
 * Copyright (C) 2026
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package it.leproide.webdavscanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Output format stored in settings. */
enum class OutputFormat { PDF, JPG, BOTH }

/** Values mirror GmsDocumentScannerOptions.SCANNER_MODE_* (FULL=1, BASE_WITH_FILTER=2, BASE=3). */
enum class ScanMode { FULL, FILTER, BASE }

/** Upload back-end: exactly one is active. */
enum class Protocol { WEBDAV, SMB }

/** Immutable snapshot of the user configuration. */
data class Config(
    val protocol: Protocol,
    // --- WebDAV ---
    val server: String,
    val port: Int?,          // null = scheme default
    val remotePath: String,
    val username: String,
    val password: String,
    val trustAll: Boolean,   // self-signed certificate: pinning mode
    val certPin: String,     // SHA-256 of the pinned leaf certificate ("" = not yet captured)
    // --- SMB2/3 ---
    val smbHost: String,
    val smbPort: Int?,       // null = 445
    val smbShare: String,
    val smbPath: String,
    val smbDomain: String,   // empty = local account
    val smbUser: String,
    val smbPassword: String,
    val smbSigning: Boolean,
    val smbEncrypt: Boolean,
    val smbDfs: Boolean,
    // --- Output / scanner ---
    val format: OutputFormat,
    val prefix: String,
    val mode: ScanMode,
    val galleryImport: Boolean,
) {
    val isConfigured: Boolean
        get() = when (protocol) {
            Protocol.WEBDAV -> server.isNotBlank()
            Protocol.SMB -> smbHost.isNotBlank()
        }
}

/**
 * SharedPreferences wrapper. The password is encrypted with an AES-GCM key
 * held in the Android Keystore (never leaves secure hardware where available).
 */
object Prefs {
    private const val FILE = "settings"
    private const val TAG = "Prefs"

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Config(
            protocol = runCatching { Protocol.valueOf(p.getString("protocol", "WEBDAV")!!) }
                .getOrDefault(Protocol.WEBDAV),
            server = p.getString("server", "") ?: "",
            port = p.getInt("port", -1).takeIf { it in 1..65535 },
            remotePath = p.getString("path", "/") ?: "/",
            username = p.getString("user", "") ?: "",
            password = p.getString("pass", null)?.let { Crypto.decrypt(it) } ?: "",
            trustAll = p.getBoolean("trustAll", false),
            certPin = p.getString("certPin", "") ?: "",
            smbHost = p.getString("smbHost", "") ?: "",
            smbPort = p.getInt("smbPort", -1).takeIf { it in 1..65535 },
            smbShare = p.getString("smbShare", "") ?: "",
            smbPath = p.getString("smbPath", "") ?: "",
            smbDomain = p.getString("smbDomain", "") ?: "",
            smbUser = p.getString("smbUser", "") ?: "",
            smbPassword = p.getString("smbPass", null)?.let { Crypto.decrypt(it) } ?: "",
            smbSigning = p.getBoolean("smbSigning", true),
            smbEncrypt = p.getBoolean("smbEncrypt", false),
            smbDfs = p.getBoolean("smbDfs", false),
            format = runCatching { OutputFormat.valueOf(p.getString("format", "PDF")!!) }
                .getOrDefault(OutputFormat.PDF),
            prefix = p.getString("prefix", "scan_") ?: "scan_",
            mode = runCatching { ScanMode.valueOf(p.getString("mode", "FULL")!!) }
                .getOrDefault(ScanMode.FULL),
            galleryImport = p.getBoolean("gallery", false),
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("protocol", c.protocol.name)
            .putString("server", c.server.trim())
            .putInt("port", c.port ?: -1)
            .putString("path", c.remotePath.trim().ifEmpty { "/" })
            .putString("user", c.username)
            .putString("pass", Crypto.encrypt(c.password))
            .putBoolean("trustAll", c.trustAll)
            .putString("certPin", if (c.trustAll) c.certPin else "")
            .putString("smbHost", c.smbHost.trim())
            .putInt("smbPort", c.smbPort ?: -1)
            .putString("smbShare", c.smbShare.trim())
            .putString("smbPath", c.smbPath.trim())
            .putString("smbDomain", c.smbDomain.trim())
            .putString("smbUser", c.smbUser.trim())
            .putString("smbPass", Crypto.encrypt(c.smbPassword))
            .putBoolean("smbSigning", c.smbSigning)
            .putBoolean("smbEncrypt", c.smbEncrypt)
            .putBoolean("smbDfs", c.smbDfs)
            .putString("format", c.format.name)
            .putString("prefix", c.prefix)
            .putString("mode", c.mode.name)
            .putBoolean("gallery", c.galleryImport)
            .apply()
    }

    /** Remembers the last base filename to guarantee uniqueness within the same second. */
    fun lastBaseName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastBase", null)

    fun setLastBaseName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("lastBase", name).apply()
    }

    /** AES/GCM helper backed by AndroidKeyStore. Output = Base64(iv || ciphertext). */
    private object Crypto {
        private const val ALIAS = "webdav_scanner_pw"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        fun encrypt(plain: String): String {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        fun decrypt(enc: String): String? = try {
            val raw = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key lost (e.g. app data restored on another device): user must re-enter it.
            Log.w(TAG, "Password decrypt failed", e)
            null
        }
    }
}
