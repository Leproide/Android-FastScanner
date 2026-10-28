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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Thrown for HTTP errors; [permanent] = retrying will not help (auth, bad path...). */
class WebDavException(val code: Int, msg: String) :
    UploadException(msg, permanent = code in 400..499 && code != 408 && code != 429)

/** Minimal WebDAV client: PROPFIND (check), MKCOL (create dirs), PUT (upload). */
class WebDavClient(private val cfg: Config) {

    private val http: OkHttpClient = buildClient()

    /** Base collection URL built from server + port + remote path. */
    val baseUrl: HttpUrl = buildBaseUrl(cfg)

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false) // PUT must not be silently redirected to GET
        if (cfg.username.isNotEmpty()) {
            // Preemptive Basic auth (supported by Nextcloud, ownCloud, Synology, QNAP, Apache, nginx).
            val auth = Credentials.basic(cfg.username, cfg.password, Charsets.UTF_8)
            b.addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", auth).build())
            }
        }
        if (cfg.trustAll) applyTrustAll(b)
        return b.build()
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun applyTrustAll(b: OkHttpClient.Builder) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        b.sslSocketFactory(ctx.socketFactory, tm)
        b.hostnameVerifier { _, _ -> true }
    }

    private fun dirUrl(segments: List<String>): HttpUrl {
        val u = rootUrl().newBuilder()
        segments.forEach { u.addPathSegment(it) }
        u.addPathSegment("") // trailing slash: collections should be addressed with "/"
        return u.build()
    }

    private fun rootUrl(): HttpUrl = baseServerUrl(cfg)

    private val pathSegments: List<String> =
        cfg.remotePath.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** PROPFIND Depth:0. Returns HTTP status code. */
    private fun propfind(url: HttpUrl): Int {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body).header("Depth", "0").build()
        http.newCall(req).execute().use { return it.code }
    }

    private fun mkcol(url: HttpUrl): Int {
        val req = Request.Builder().url(url).method("MKCOL", null).build()
        http.newCall(req).execute().use { return it.code }
    }

    /**
     * Makes sure the target folder exists, creating missing levels.
     * Intermediate MKCOL failures are ignored (e.g. no rights on /remote.php),
     * only the final folder status matters.
     */
    fun ensureDirectory() {
        if (pathSegments.isEmpty()) return
        val target = dirUrl(pathSegments)
        val code = propfind(target)
        if (code == 207 || code == 200) return
        if (code == 401 || code == 403) throw WebDavException(code, "Accesso negato ($code): controlla credenziali")
        if (code in 300..399) throw WebDavException(400, "Il server reindirizza (HTTP $code): controlla http/https, porta e percorso")
        if (code != 404 && code != 409) throw WebDavException(code, "PROPFIND HTTP $code")
        for (i in 1..pathSegments.size) {
            val c = mkcol(dirUrl(pathSegments.take(i)))
            if (i == pathSegments.size && c !in listOf(200, 201, 204, 405)) {
                throw WebDavException(c, "Impossibile creare la cartella (HTTP $c)")
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
        http.newCall(req).execute().use { r ->
            if (r.code in 200..299) return url.toString()
            throw WebDavException(r.code, "PUT HTTP ${r.code} ${r.message}")
        }
    }

    /** Connection test used by the settings screen. */
    fun test(): String {
        ensureDirectory()
        val code = propfind(dirUrl(pathSegments))
        if (code != 207 && code != 200) throw WebDavException(code, "HTTP $code")
        return dirUrl(pathSegments).toString()
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

        /** scheme://host[:port][/server-path] — server path kept if typed in the server field. */
        fun baseServerUrl(cfg: Config): HttpUrl {
            var s = cfg.server.trim()
            if (!s.contains("://")) s = "https://$s"
            val u = s.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL server non valido")
            val b = u.newBuilder()
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
            cfg.remotePath.split('/').filter { it.isNotBlank() }.forEach { b.addPathSegment(it.trim()) }
            return b.build()
        }
    }
}
