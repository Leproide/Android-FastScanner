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

import android.annotation.SuppressLint
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Thrown for HTTP errors; permanent = retrying will not help (auth, bad path...). */
class WebDavException(val code: Int, res: Int, vararg args: Any?) :
    UploadException(res, permanent = code in 400..499 && code != 408 && code != 429, args = args.toList())

/**
 * Minimal WebDAV client: PROPFIND (check), MKCOL (create dirs), PUT (upload).
 *
 * TLS: system + user CAs by default. For self-signed servers the user enables
 * certificate pinning (trust-on-first-use): the SHA-256 of the server leaf
 * certificate is captured on "Test connection" and every later connection is
 * accepted ONLY if the certificate matches. No "trust everything" mode exists.
 *
 * @param capturePin true only for the settings test: accepts an unknown
 *                   certificate (when no pin is stored yet) and records it.
 */
class WebDavClient(private val cfg: Config, private val capturePin: Boolean = false) {

    /** Leaf fingerprint observed during the last handshake (pinning mode only). */
    @Volatile var seenPin: String? = null
        private set

    /** Non-null when the server presented a certificate different from the pinned one. */
    @Volatile var pinMismatch: String? = null
        private set

    private val http: OkHttpClient = buildClient()

    /** Base collection URL built from server + port + remote path. */
    val baseUrl: HttpUrl = buildBaseUrl(cfg)

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false) // never forward credentials/PUT bodies to another location
            .followSslRedirects(false)
        if (cfg.username.isNotEmpty()) {
            // Preemptive Basic auth (Nextcloud, ownCloud, Synology, QNAP, Apache, nginx).
            // Sent only to the configured host because redirects are disabled.
            val auth = Credentials.basic(cfg.username, cfg.password, Charsets.UTF_8)
            b.addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", auth).build())
            }
        }
        if (cfg.trustAll) applyPinning(b)
        return b.build()
    }

    @SuppressLint("CustomX509TrustManager")
    private fun applyPinning(b: OkHttpClient.Builder) {
        val expected = cfg.certPin
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates not supported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
                val leaf = chain[0]
                leaf.checkValidity() // still reject expired / not-yet-valid certificates
                val pin = fingerprint(leaf)
                seenPin = pin
                if (expected.isEmpty()) {
                    if (!capturePin) throw CertificateException("No pinned certificate")
                    return // first use, settings test: recorded in seenPin
                }
                if (!MessageDigest.isEqual(pin.toByteArray(), expected.toByteArray())) {
                    pinMismatch = pin
                    throw CertificateException("Pinned certificate mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        b.sslSocketFactory(ctx.socketFactory, tm)
        // The pinned certificate identifies the server; self-signed certs often lack a matching SAN.
        b.hostnameVerifier { _, _ -> true }
    }

    private fun dirUrl(segments: List<String>): HttpUrl {
        val u = rootUrl().newBuilder()
        segments.forEach { u.addPathSegment(it) }
        u.addPathSegment("") // trailing slash: collections should be addressed with "/"
        return u.build()
    }

    private fun rootUrl(): HttpUrl = baseServerUrl(cfg)

    private val pathSegments: List<String> = segmentsOf(cfg.remotePath)

    /** Executes a call translating TLS pin problems into clear, permanent errors. */
    private inline fun <T> exec(req: Request, block: (Response) -> T): T {
        try {
            return http.newCall(req).execute().use(block)
        } catch (e: IOException) {
            if (e is UploadException) throw e
            pinMismatch?.let { throw UploadException(R.string.err_cert_changed, true, listOf(cfg.certPin, it), e) }
            if (cfg.trustAll && cfg.certPin.isEmpty() && !capturePin) {
                throw UploadException(R.string.err_cert_pin_missing, true, cause = e)
            }
            throw UploadException(R.string.err_network, false, listOf(e.message ?: e.javaClass.simpleName), e)
        }
    }

    /** PROPFIND Depth:0. Returns HTTP status code. */
    private fun propfind(url: HttpUrl): Int {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "0").build()
        return exec(req) { it.code }
    }

    private fun mkcol(url: HttpUrl): Int {
        val req = Request.Builder().url(url).method("MKCOL", null).build()
        return exec(req) { it.code }
    }

    /**
     * Makes sure the target folder exists, creating missing levels.
     * Intermediate MKCOL failures are ignored (e.g. no rights on /remote.php),
     * only the final folder status matters.
     */
    fun ensureDirectory() {
        val target = dirUrl(pathSegments)
        val code = propfind(target)
        if (code == 207 || code == 200) return
        if (code == 401 || code == 403) throw WebDavException(code, R.string.err_access_denied_http, code)
        if (code in 300..399) throw WebDavException(400, R.string.err_redirect, code)
        if (pathSegments.isEmpty() || (code != 404 && code != 409)) throw WebDavException(code, R.string.err_http, code)
        for (i in 1..pathSegments.size) {
            val c = mkcol(dirUrl(pathSegments.take(i)))
            if (i == pathSegments.size && c !in listOf(200, 201, 204, 405)) {
                throw WebDavException(c, R.string.err_mkdir, c)
            }
        }
    }

    /**
     * Uploads [file] as [name]. Uses If-None-Match:* so an existing file is never
     * overwritten: on 412 the caller picks another name.
     * @return final remote URL
     */
    fun put(file: File, name: String, mime: String): String {
        val url = dirUrl(pathSegments).newBuilder().apply {
            // dirUrl ends with an empty segment; replace it with the file name
            setPathSegment(build().pathSize - 1, name)
        }.build()
        val req = Request.Builder()
            .url(url)
            .put(file.asRequestBody(mime.toMediaType()))
            .header("If-None-Match", "*")
            .build()
        return exec(req) { r ->
            if (r.code in 200..299) url.toString()
            else throw WebDavException(r.code, R.string.err_http, r.code)
        }
    }

    /** Connection test used by the settings screen. */
    fun test(): String {
        ensureDirectory()
        return dirUrl(pathSegments).toString()
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

        /** SHA-256 of the DER certificate, uppercase hex with ':' separators. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }

        /** Splits a remote path; rejects '.' / '..' so the path cannot climb above the base. */
        fun segmentsOf(path: String): List<String> {
            val segs = path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
            if (segs.any { it == "." || it == ".." }) throw UploadException(R.string.err_bad_path, true)
            return segs
        }

        /** scheme://host[:port][/server-path] — server path kept if typed in the server field. */
        fun baseServerUrl(cfg: Config): HttpUrl {
            var s = cfg.server.trim()
            if (!s.contains("://")) s = "https://$s"
            val u = s.toHttpUrlOrNull() ?: throw UploadException(R.string.err_bad_url, true)
            if (u.pathSegments.any { it == "." || it == ".." }) throw UploadException(R.string.err_bad_path, true)
            val b = u.newBuilder().query(null).fragment(null)
            cfg.port?.let { b.port(it) }
            // Drop a trailing empty segment so addPathSegment appends cleanly.
            val built = b.build()
            return if (built.pathSegments.lastOrNull() == "" && built.pathSize > 1)
                built.newBuilder().removePathSegment(built.pathSize - 1).build()
            else built
        }

        /** Human readable destination for the main screen. */
        fun buildBaseUrl(cfg: Config): HttpUrl {
            val b = baseServerUrl(cfg).newBuilder()
            segmentsOf(cfg.remotePath).forEach { b.addPathSegment(it) }
            return b.build()
        }
    }
}
