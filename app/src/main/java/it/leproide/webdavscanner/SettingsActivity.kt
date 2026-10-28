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

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Destination (WebDAV OR SMB, mutually exclusive toggles), credentials,
 * output format and scanner options.
 */
class SettingsActivity : AppCompatActivity() {

    // Protocol toggles + sections
    private lateinit var swWebdav: MaterialSwitch
    private lateinit var swSmb: MaterialSwitch
    private lateinit var sectionWebdav: View
    private lateinit var sectionSmb: View

    // WebDAV
    private lateinit var edtServer: EditText
    private lateinit var edtPort: EditText
    private lateinit var edtPath: EditText
    private lateinit var edtUser: EditText
    private lateinit var edtPass: EditText
    private lateinit var swTrustAll: MaterialSwitch

    // SMB
    private lateinit var edtSmbHost: EditText
    private lateinit var edtSmbPort: EditText
    private lateinit var edtSmbShare: EditText
    private lateinit var edtSmbPath: EditText
    private lateinit var edtSmbDomain: EditText
    private lateinit var edtSmbUser: EditText
    private lateinit var edtSmbPass: EditText
    private lateinit var swSmbSigning: MaterialSwitch
    private lateinit var swSmbEncrypt: MaterialSwitch
    private lateinit var swSmbDfs: MaterialSwitch

    // Output / scanner
    private lateinit var edtPrefix: EditText
    private lateinit var swGallery: MaterialSwitch
    private lateinit var rgFormat: RadioGroup
    private lateinit var rgMode: RadioGroup
    private lateinit var txtResult: TextView

    /** Guards against listener recursion while flipping the paired switch. */
    private var syncing = false

    /** Pinned WebDAV certificate (SHA-256); captured by "Test connection". */
    private var certPin = ""

    /** false while the code itself flips the self-signed switch (keeps the new pin). */
    private var pinResetEnabled = true

    /** A certificate was accepted in this screen: remind the user to Save. */
    private var justPinned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableInsetsAwareLayout()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupToolbarAndInsets(findViewById(R.id.root), findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        swWebdav = findViewById(R.id.swWebdav)
        swSmb = findViewById(R.id.swSmb)
        sectionWebdav = findViewById(R.id.sectionWebdav)
        sectionSmb = findViewById(R.id.sectionSmb)

        edtServer = findViewById(R.id.edtServer)
        edtPort = findViewById(R.id.edtPort)
        edtPath = findViewById(R.id.edtPath)
        edtUser = findViewById(R.id.edtUser)
        edtPass = findViewById(R.id.edtPass)
        swTrustAll = findViewById(R.id.swTrustAll)

        edtSmbHost = findViewById(R.id.edtSmbHost)
        edtSmbPort = findViewById(R.id.edtSmbPort)
        edtSmbShare = findViewById(R.id.edtSmbShare)
        edtSmbPath = findViewById(R.id.edtSmbPath)
        edtSmbDomain = findViewById(R.id.edtSmbDomain)
        edtSmbUser = findViewById(R.id.edtSmbUser)
        edtSmbPass = findViewById(R.id.edtSmbPass)
        swSmbSigning = findViewById(R.id.swSmbSigning)
        swSmbEncrypt = findViewById(R.id.swSmbEncrypt)
        swSmbDfs = findViewById(R.id.swSmbDfs)

        edtPrefix = findViewById(R.id.edtPrefix)
        swGallery = findViewById(R.id.swGallery)
        rgFormat = findViewById(R.id.rgFormat)
        rgMode = findViewById(R.id.rgMode)
        txtResult = findViewById(R.id.txtTestResult)

        if (savedInstanceState == null) fill(Prefs.load(this))

        // Exactly one protocol active: turning one on turns the other off,
        // turning the active one off switches to the other.
        swWebdav.setOnCheckedChangeListener { _, on -> selectProtocol(if (on) Protocol.WEBDAV else Protocol.SMB) }
        swSmb.setOnCheckedChangeListener { _, on -> selectProtocol(if (on) Protocol.SMB else Protocol.WEBDAV) }
        updateSections()

        // Toggling the self-signed option (or changing server) forgets the pinned certificate.
        swTrustAll.setOnCheckedChangeListener { _, _ ->
            if (pinResetEnabled) { certPin = ""; justPinned = false; txtResult.text = "" }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val cfg = readForm() ?: return@setOnClickListener
            if (isCleartextWithCredentials(cfg)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.warn_http_title)
                    .setMessage(R.string.warn_http_msg)
                    .setPositiveButton(R.string.save_anyway) { _, _ -> save(cfg) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else save(cfg)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("certPin", certPin)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        certPin = savedInstanceState.getString("certPin", "")
    }

    private fun save(cfg: Config) {
        Prefs.save(this, cfg)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Basic auth over plain http exposes the password on the network. */
    private fun isCleartextWithCredentials(c: Config) =
        c.protocol == Protocol.WEBDAV && c.username.isNotEmpty() &&
            c.server.trim().startsWith("http://", ignoreCase = true)

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun selectedProtocol() = if (swSmb.isChecked) Protocol.SMB else Protocol.WEBDAV

    private fun selectProtocol(p: Protocol) {
        if (syncing) return
        syncing = true
        swWebdav.isChecked = p == Protocol.WEBDAV
        swSmb.isChecked = p == Protocol.SMB
        syncing = false
        updateSections()
        txtResult.text = ""
    }

    private fun updateSections() {
        val smb = selectedProtocol() == Protocol.SMB
        sectionSmb.visibility = if (smb) View.VISIBLE else View.GONE
        sectionWebdav.visibility = if (smb) View.GONE else View.VISIBLE
    }

    private fun fill(c: Config) {
        syncing = true
        swWebdav.isChecked = c.protocol == Protocol.WEBDAV
        swSmb.isChecked = c.protocol == Protocol.SMB
        syncing = false

        edtServer.setText(c.server)
        edtPort.setText(c.port?.toString() ?: "")
        edtPath.setText(c.remotePath)
        edtUser.setText(c.username)
        edtPass.setText(c.password)
        swTrustAll.isChecked = c.trustAll
        certPin = c.certPin

        edtSmbHost.setText(c.smbHost)
        edtSmbPort.setText(c.smbPort?.toString() ?: "")
        edtSmbShare.setText(c.smbShare)
        edtSmbPath.setText(c.smbPath)
        edtSmbDomain.setText(c.smbDomain)
        edtSmbUser.setText(c.smbUser)
        edtSmbPass.setText(c.smbPassword)
        swSmbSigning.isChecked = c.smbSigning
        swSmbEncrypt.isChecked = c.smbEncrypt
        swSmbDfs.isChecked = c.smbDfs

        edtPrefix.setText(c.prefix)
        swGallery.isChecked = c.galleryImport
        rgFormat.check(
            when (c.format) {
                OutputFormat.PDF -> R.id.rbPdf
                OutputFormat.JPG -> R.id.rbJpg
                OutputFormat.BOTH -> R.id.rbBoth
            }
        )
        rgMode.check(
            when (c.mode) {
                ScanMode.FULL -> R.id.rbFull
                ScanMode.FILTER -> R.id.rbFilter
                ScanMode.BASE -> R.id.rbBase
            }
        )
    }

    /** Parses an optional port field; returns Result.failure on invalid input. */
    private fun readPort(e: EditText): kotlin.Result<Int?> {
        val txt = e.text.toString().trim()
        if (txt.isEmpty()) return kotlin.Result.success(null)
        val p = txt.toIntOrNull()?.takeIf { it in 1..65535 }
        if (p == null) {
            e.error = "1-65535"
            return kotlin.Result.failure(IllegalArgumentException())
        }
        return kotlin.Result.success(p)
    }

    /**
     * Validates the form (only the active protocol's fields are mandatory);
     * returns null and shows a field error if invalid.
     */
    private fun readForm(): Config? {
        val protocol = selectedProtocol()
        val port = readPort(edtPort).getOrElse { if (protocol == Protocol.WEBDAV) return null else null }
        val smbPort = readPort(edtSmbPort).getOrElse { if (protocol == Protocol.SMB) return null else null }

        when (protocol) {
            Protocol.WEBDAV -> if (edtServer.text.isNullOrBlank()) {
                edtServer.error = getString(R.string.server_url); return null
            }
            Protocol.SMB -> {
                if (edtSmbHost.text.isNullOrBlank()) {
                    edtSmbHost.error = getString(R.string.host_required); return null
                }
            }
        }

        val cfg = Config(
            protocol = protocol,
            server = edtServer.text.toString().trim(),
            port = port,
            remotePath = edtPath.text.toString().trim().ifEmpty { "/" },
            username = edtUser.text.toString(),
            password = edtPass.text.toString(),
            trustAll = swTrustAll.isChecked,
            certPin = if (swTrustAll.isChecked) certPin else "",
            smbHost = edtSmbHost.text.toString().trim(),
            smbPort = smbPort,
            smbShare = edtSmbShare.text.toString().trim(),
            smbPath = edtSmbPath.text.toString().trim(),
            smbDomain = edtSmbDomain.text.toString().trim(),
            smbUser = edtSmbUser.text.toString().trim(),
            smbPassword = edtSmbPass.text.toString(),
            smbSigning = swSmbSigning.isChecked,
            smbEncrypt = swSmbEncrypt.isChecked,
            smbDfs = swSmbDfs.isChecked,
            format = when (rgFormat.checkedRadioButtonId) {
                R.id.rbJpg -> OutputFormat.JPG
                R.id.rbBoth -> OutputFormat.BOTH
                else -> OutputFormat.PDF
            },
            prefix = edtPrefix.text.toString(),
            mode = when (rgMode.checkedRadioButtonId) {
                R.id.rbFilter -> ScanMode.FILTER
                R.id.rbBase -> ScanMode.BASE
                else -> ScanMode.FULL
            },
            galleryImport = swGallery.isChecked,
        )

        // Validate the destination early so the user is not surprised at upload time.
        return try {
            when (protocol) {
                Protocol.WEBDAV -> WebDavClient.buildBaseUrl(cfg)
                Protocol.SMB -> SmbUploader.parseTarget(cfg)
            }
            cfg
        } catch (e: UploadException) {
            val field = if (protocol == Protocol.SMB) edtSmbShare else edtServer
            field.error = e.localized(this); null
        }
    }

    /**
     * Strict test (no silent trust). If the server certificate is untrusted
     * (self-signed / private CA / IP address) or differs from the pinned one,
     * the user sees its details and can accept it; the test then re-runs.
     */
    private fun testConnection() {
        val cfg = readForm() ?: return
        txtResult.text = getString(R.string.testing)
        lifecycleScope.launch {
            // Pinning enabled but nothing pinned yet: show the certificate first.
            if (cfg.protocol == Protocol.WEBDAV && cfg.trustAll && cfg.certPin.isEmpty()) {
                val info = withContext(Dispatchers.IO) { WebDavClient.probeCertificate(cfg) }
                if (info == null) {
                    txtResult.text = getString(R.string.test_fail, getString(R.string.err_no_certificate))
                } else askTrust(info, null)
                return@launch
            }
            val error: Exception? = withContext(Dispatchers.IO) {
                try {
                    Uploader.forConfig(cfg).use { it.test() }
                    null
                } catch (e: Exception) {
                    e
                }
            }
            when (error) {
                null -> {
                    val dest = runCatching { Uploader.describe(cfg) }.getOrDefault("")
                    var ok = getString(R.string.test_ok, dest)
                    if (justPinned) ok += "\n\n" + getString(R.string.cert_trusted, cfg.certPin)
                    txtResult.text = ok
                }
                is CertChangedException -> {
                    txtResult.text = getString(R.string.test_fail, error.localized(this@SettingsActivity))
                    val info = error.seen ?: withContext(Dispatchers.IO) { WebDavClient.probeCertificate(cfg) }
                    if (info != null) askTrust(info, error.expected)
                }
                is CertUntrustedException -> {
                    txtResult.text = getString(R.string.test_fail, error.localized(this@SettingsActivity))
                    val info = withContext(Dispatchers.IO) { WebDavClient.probeCertificate(cfg) }
                    if (info != null) askTrust(info, null)
                }
                else -> txtResult.text = getString(R.string.test_fail, error.userMessage(this@SettingsActivity))
            }
        }
    }

    /**
     * Shows certificate details; on accept pins it (enabling the self-signed
     * option if needed) and re-runs the test. [oldPin] != null = certificate changed.
     */
    private fun askTrust(info: CertInfo, oldPin: String?) {
        val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val intro = if (oldPin == null) getString(R.string.cert_intro_untrusted)
        else getString(R.string.cert_intro_changed, oldPin)
        val body = getString(
            R.string.cert_details,
            intro,
            info.subject,
            if (info.selfSigned) getString(R.string.cert_self_signed) else info.issuer,
            df.format(info.notAfter) + if (info.expired) " " + getString(R.string.cert_expired) else "",
            info.sha256,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(if (oldPin == null) R.string.cert_title_untrusted else R.string.cert_title_changed)
            .setMessage(body)
            .setPositiveButton(if (oldPin == null) R.string.cert_accept else R.string.cert_accept_new) { _, _ ->
                pinResetEnabled = false
                swTrustAll.isChecked = true
                pinResetEnabled = true
                certPin = info.sha256
                justPinned = true
                txtResult.text = getString(R.string.cert_trusted, info.sha256)
                testConnection() // verify with the pinned certificate
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
