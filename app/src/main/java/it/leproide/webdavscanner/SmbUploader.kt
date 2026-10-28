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

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/SMB3 uploader (smbj). SMB1 is never used.
 *
 * Authentication: NTLMv2 (via SPNEGO negotiation) with
 *  - local accounts:  user "mario", domain empty (or the server name)
 *  - domain accounts: user "mario" + domain "CONTOSO", or "CONTOSO\mario",
 *                     or UPN "mario@contoso.local" typed in the user field.
 * Kerberos is not available on Android (no JGSS): domain accounts work as
 * long as the domain allows NTLMv2 (the default on Active Directory).
 *
 * Security: SMB 3.1.1 preferred (pre-auth integrity, AES-GCM/CCM encryption),
 * message signing required by default, optional mandatory encryption.
 */
class SmbUploader(private val cfg: Config) : Uploader {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val target = parseTarget(cfg)

    override val destination: String = describe(cfg)

    override fun prepare(): Unit = wrap {
        val config = SmbConfig.builder()
            // Modern dialects only, newest first.
            .withDialects(
                SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2
            )
            .withSigningRequired(cfg.smbSigning)
            .withEncryptData(cfg.smbEncrypt)
            .withDfsEnabled(cfg.smbDfs)
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()

        val c = SMBClient(config).also { client = it }
        val conn = c.connect(target.host, target.port).also { connection = it }

        if (cfg.smbEncrypt && !conn.connectionContext.supportsEncryption()) {
            throw UploadException(R.string.err_smb_no_encryption, permanent = true)
        }

        val (user, domain) = splitUser(cfg.smbUser, cfg.smbDomain)
        val auth = if (user.isEmpty()) AuthenticationContext.guest()
        else AuthenticationContext(user, cfg.smbPassword.toCharArray(), domain)

        val s = conn.authenticate(auth).also { session = it }
        val sh = s.connectShare(target.share) as? DiskShare
            ?: throw UploadException(R.string.err_smb_not_disk, permanent = true, args = listOf(target.share))
        share = sh

        // Create the folder tree level by level.
        var path = ""
        for (seg in target.folder) {
            path = if (path.isEmpty()) seg else "$path\\$seg"
            if (!sh.folderExists(path)) sh.mkdir(path)
        }
    }

    override fun upload(file: File, name: String, mime: String): Unit = wrap {
        val sh = share ?: throw IllegalStateException("prepare() not called")
        val path = (target.folder + name).joinToString("\\")
        val remote = try {
            sh.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_CREATE, // fails if exists -> never overwrite
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_COLLISION) throw AlreadyExistsException(name)
            throw e
        }
        try {
            remote.use { f ->
                f.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            }
        } catch (e: Exception) {
            // Do not leave a truncated file behind: the retry would see a name collision.
            runCatching { sh.rm(path) }
            throw e
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
    }

    /** Maps smbj errors to [UploadException] with a clear Italian message. */
    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: UploadException) {
        throw e
    } catch (e: SMBApiException) {
        throw when (e.status) {
            NtStatus.STATUS_LOGON_FAILURE ->
                UploadException(R.string.err_smb_login, true, cause = e)
            NtStatus.STATUS_ACCESS_DENIED ->
                UploadException(R.string.err_smb_access, true, cause = e)
            NtStatus.STATUS_BAD_NETWORK_NAME ->
                UploadException(R.string.err_smb_no_share, true, listOf(target.share), e)
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                UploadException(R.string.err_smb_path, true, cause = e)
            else -> UploadException(
                R.string.err_smb_generic, false,
                listOf("${e.status} (0x${java.lang.Long.toHexString(e.statusCode)})"), e
            )
        }
    } catch (e: SMBRuntimeException) {
        // Transport / protocol problems: usually transient (network drop, timeout).
        throw UploadException(R.string.err_smb_generic, false, listOf(e.message ?: e.javaClass.simpleName), e)
    } catch (e: IOException) {
        throw UploadException(R.string.err_network, false, listOf(e.message ?: e.javaClass.simpleName), e)
    }

    /** Parsed destination. */
    data class Target(val host: String, val port: Int, val share: String, val folder: List<String>)

    companion object {
        const val DEFAULT_PORT = 445

        /**
         * Host field accepts: "nas", "192.168.1.10", "\\nas\share\dir" or "smb://nas/share/dir".
         * In the last two forms share/folder fields are filled from the host field if empty.
         */
        fun parseTarget(cfg: Config): Target {
            val raw = cfg.smbHost.trim().removePrefix("smb://").trimStart('\\', '/')
            val parts = raw.split('\\', '/').filter { it.isNotBlank() }
            if (parts.isEmpty()) throw UploadException(R.string.err_smb_no_host, permanent = true)
            val host = parts[0]
            val share = cfg.smbShare.trim().trim('\\', '/').ifEmpty { parts.getOrNull(1).orEmpty() }
            if (share.isEmpty()) throw UploadException(R.string.err_smb_no_share_name, permanent = true)
            val folderSrc = cfg.smbPath.ifBlank {
                if (cfg.smbShare.isBlank()) parts.drop(2).joinToString("/") else ""
            }
            // Same rules as WebDAV: no '.' / '..' segments.
            val folder = WebDavClient.segmentsOf(folderSrc)
            return Target(host, cfg.smbPort ?: DEFAULT_PORT, share, folder)
        }

        fun describe(cfg: Config): String = runCatching {
            val t = parseTarget(cfg)
            val port = if (t.port == DEFAULT_PORT) "" else ":${t.port}"
            "\\\\${t.host}$port\\${t.share}" + t.folder.joinToString("") { "\\$it" }
        }.getOrDefault(cfg.smbHost)

        /**
         * "DOM\user" -> (user, DOM); "user@dom.local" -> kept as UPN with empty domain
         * (NTLM accepts UPN); otherwise (user, domainField). Empty domain = local account.
         */
        fun splitUser(userField: String, domainField: String): Pair<String, String> {
            val u = userField.trim()
            val d = domainField.trim()
            val bs = u.indexOf('\\')
            return when {
                bs > 0 -> u.substring(bs + 1) to u.substring(0, bs)
                else -> u to d
            }
        }
    }
}
