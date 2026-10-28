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
import androidx.annotation.StringRes
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Upload failure with a localizable message ([res] + [args]).
 * [permanent] = retrying will not help (wrong credentials, missing share,
 * certificate changed...): the job is marked failed immediately.
 */
open class UploadException(
    @StringRes val res: Int,
    val permanent: Boolean,
    val args: List<Any?> = emptyList(),
    cause: Throwable? = null,
) : IOException("res#$res $args", cause) {
    /** Message in the device language. */
    fun localized(ctx: Context): String = ctx.getString(res, *args.toTypedArray())
}

/** The remote name is already taken: the caller retries with another name. */
class AlreadyExistsException(name: String) :
    UploadException(R.string.err_exists, permanent = false, args = listOf(name))

/** Localized message for any throwable (used by worker and settings). */
fun Throwable.userMessage(ctx: Context): String =
    if (this is UploadException) localized(ctx)
    else ctx.getString(R.string.err_generic, message ?: javaClass.simpleName)

/**
 * Common contract for the upload back-ends (WebDAV, SMB).
 * Lifecycle for one job: [prepare] once -> [upload] per file -> [close].
 */
interface Uploader : Closeable {
    /** Human readable destination (shown in UI / test result). */
    val destination: String

    /** Connects/authenticates and makes sure the target folder exists. */
    fun prepare()

    /**
     * Uploads [file] as [name] in the target folder.
     * Must NEVER overwrite: throws [AlreadyExistsException] if [name] exists.
     */
    fun upload(file: File, name: String, mime: String)

    /** Connection test for the settings screen; returns a description of the target. */
    fun test(): String {
        prepare()
        return destination
    }

    companion object {
        fun forConfig(cfg: Config): Uploader = when (cfg.protocol) {
            Protocol.WEBDAV -> WebDavUploader(cfg)
            Protocol.SMB -> SmbUploader(cfg)
        }

        /** Destination string without connecting (main screen). */
        fun describe(cfg: Config): String = when (cfg.protocol) {
            Protocol.WEBDAV -> WebDavClient.buildBaseUrl(cfg).toString()
            Protocol.SMB -> SmbUploader.describe(cfg)
        }
    }
}

/** Adapter: existing [WebDavClient] behind the [Uploader] interface. */
class WebDavUploader(cfg: Config) : Uploader {
    val client = WebDavClient(cfg)
    override val destination: String = client.baseUrl.toString()
    override fun prepare() = client.ensureDirectory()
    override fun upload(file: File, name: String, mime: String) {
        try {
            client.put(file, name, mime)
        } catch (e: WebDavException) {
            if (e.code == 412) throw AlreadyExistsException(name)
            throw e
        }
    }
    override fun test(): String = client.test()
    override fun close() { /* OkHttp needs no explicit close */ }
}
