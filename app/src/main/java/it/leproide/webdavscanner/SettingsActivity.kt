/*
 * WebDAV Scanner
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
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WebDAV server, credentials, output format and scanner options. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var edtServer: EditText
    private lateinit var edtPort: EditText
    private lateinit var edtPath: EditText
    private lateinit var edtUser: EditText
    private lateinit var edtPass: EditText
    private lateinit var edtPrefix: EditText
    private lateinit var swTrustAll: MaterialSwitch
    private lateinit var swGallery: MaterialSwitch
    private lateinit var rgFormat: RadioGroup
    private lateinit var rgMode: RadioGroup
    private lateinit var txtResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableInsetsAwareLayout()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupToolbarAndInsets(findViewById(R.id.root), findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        edtServer = findViewById(R.id.edtServer)
        edtPort = findViewById(R.id.edtPort)
        edtPath = findViewById(R.id.edtPath)
        edtUser = findViewById(R.id.edtUser)
        edtPass = findViewById(R.id.edtPass)
        edtPrefix = findViewById(R.id.edtPrefix)
        swTrustAll = findViewById(R.id.swTrustAll)
        swGallery = findViewById(R.id.swGallery)
        rgFormat = findViewById(R.id.rgFormat)
        rgMode = findViewById(R.id.rgMode)
        txtResult = findViewById(R.id.txtTestResult)

        if (savedInstanceState == null) fill(Prefs.load(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            readForm()?.let {
                Prefs.save(this, it)
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun fill(c: Config) {
        edtServer.setText(c.server)
        edtPort.setText(c.port?.toString() ?: "")
        edtPath.setText(c.remotePath)
        edtUser.setText(c.username)
        edtPass.setText(c.password)
        edtPrefix.setText(c.prefix)
        swTrustAll.isChecked = c.trustAll
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

    /** Validates the form; returns null (with field error) if invalid. */
    private fun readForm(): Config? {
        val server = edtServer.text.toString().trim()
        if (server.isEmpty()) {
            edtServer.error = getString(R.string.server_url); return null
        }
        val portTxt = edtPort.text.toString().trim()
        val port = if (portTxt.isEmpty()) null else portTxt.toIntOrNull()?.takeIf { it in 1..65535 }
        if (portTxt.isNotEmpty() && port == null) {
            edtPort.error = "1-65535"; return null
        }
        val cfg = Config(
            server = server,
            port = port,
            remotePath = edtPath.text.toString().trim().ifEmpty { "/" },
            username = edtUser.text.toString(),
            password = edtPass.text.toString(),
            trustAll = swTrustAll.isChecked,
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
        // Validate URL early so the user is not surprised at upload time.
        return try {
            WebDavClient.buildBaseUrl(cfg); cfg
        } catch (e: IllegalArgumentException) {
            edtServer.error = e.message; null
        }
    }

    private fun testConnection() {
        val cfg = readForm() ?: return
        txtResult.text = getString(R.string.testing)
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    getString(R.string.test_ok, WebDavClient(cfg).test())
                } catch (e: Exception) {
                    getString(R.string.test_fail, e.message ?: e.javaClass.simpleName)
                }
            }
            txtResult.text = msg
        }
    }
}
