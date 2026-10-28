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

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Main screen: two actions (upload to WebDAV or SMB, chosen in settings).
 *  - Single: ML Kit scanner limited to 1 page -> "Fine" -> upload, no prompts.
 *  - Multi:  ML Kit scanner, unlimited pages -> "Fine" -> upload, no prompts.
 * File name = <prefix>yyyy-MM-dd_HH-mm-ss (seconds included, never overwritten).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scanLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var txtTarget: TextView
    private lateinit var listUploads: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableInsetsAwareLayout()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupToolbarAndInsets(findViewById(R.id.root), findViewById(R.id.toolbar))

        txtTarget = findViewById(R.id.txtTarget)
        listUploads = findViewById(R.id.listUploads)

        scanLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.let { handleResult(it) }
            }
            // RESULT_CANCELED = user backed out: nothing to do.
        }

        findViewById<android.view.View>(R.id.cardSingle).setOnClickListener { startScan(multi = false) }
        findViewById<android.view.View>(R.id.cardMulti).setOnClickListener { startScan(multi = true) }
        findViewById<android.view.View>(R.id.btnClear).setOnClickListener {
            WorkManager.getInstance(this).pruneWork()
        }

        observeUploads()
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        val cfg = Prefs.load(this)
        txtTarget.text = if (cfg.isConfigured) {
            getString(R.string.target, runCatching { Uploader.describe(cfg) }
                .getOrDefault(cfg.server.ifBlank { cfg.smbHost }))
        } else getString(R.string.not_configured)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Launcher shortcuts start the scanner directly. */
    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_SINGLE -> startScan(multi = false)
            ACTION_MULTI -> startScan(multi = true)
        }
        intent?.action = Intent.ACTION_MAIN // avoid re-triggering on recreate
    }

    private fun startScan(multi: Boolean) {
        val cfg = Prefs.load(this)
        if (!cfg.isConfigured) {
            Toast.makeText(this, R.string.not_configured, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val mode = when (cfg.mode) {
            ScanMode.FULL -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
            ScanMode.FILTER -> GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
            ScanMode.BASE -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
        }
        val opts = GmsDocumentScannerOptions.Builder()
            .setScannerMode(mode)
            .setGalleryImportAllowed(cfg.galleryImport)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .apply { if (!multi) setPageLimit(1) }
            .build()

        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Scanner start failed", e)
                Toast.makeText(this, getString(R.string.scanner_error, e.message), Toast.LENGTH_LONG).show()
            }
    }

    /** Copies scanner output to a private job folder with final names, then queues the upload. */
    private fun handleResult(result: GmsDocumentScanningResult) {
        val cfg = Prefs.load(this)
        val base = uniqueBaseName(cfg.prefix)
        lifecycleScope.launch {
            val jobDir = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "pending/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    if (cfg.format != OutputFormat.JPG) {
                        result.pdf?.uri?.let { copy(it, File(dir, "$base.pdf")) }
                    }
                    if (cfg.format != OutputFormat.PDF) {
                        val pages = result.pages.orEmpty()
                        pages.forEachIndexed { i, p ->
                            val name = if (pages.size == 1) "$base.jpg"
                            else "${base}_${String.format(Locale.US, "%02d", i + 1)}.jpg"
                            copy(p.imageUri, File(dir, name))
                        }
                    }
                    dir
                } catch (e: Exception) {
                    Log.e(TAG, "Copy failed", e)
                    dir.deleteRecursively()
                    null
                }
            }
            if (jobDir == null || jobDir.list().isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.scanner_error, "nessun file"), Toast.LENGTH_LONG).show()
                return@launch
            }
            UploadWorker.enqueue(this@MainActivity, jobDir, base)
            Toast.makeText(this@MainActivity, getString(R.string.queued, base), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(src: Uri, dst: File) {
        contentResolver.openInputStream(src)!!.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
    }

    /** prefix + yyyy-MM-dd_HH-mm-ss; adds -2, -3 if two scans finish in the same second. */
    private fun uniqueBaseName(prefix: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val stem = sanitize(prefix) + ts
        // Stored as "<stem>|<count>": same stem as last scan -> increment counter.
        val last = Prefs.lastBaseName(this)?.split('|').orEmpty()
        val count = if (last.getOrNull(0) == stem) (last.getOrNull(1)?.toIntOrNull() ?: 1) + 1 else 1
        Prefs.setLastBaseName(this, "$stem|$count")
        return if (count == 1) stem else "$stem-$count"
    }

    /** Removes characters invalid in file names on common servers (Windows/SMB-backed shares). */
    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|%]"), "_")

    /** Shows the last uploads with live status. */
    private fun observeUploads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTagFlow(UploadWorker.WORK_TAG)
                    .collect { infos -> renderUploads(infos) }
            }
        }
    }

    private fun renderUploads(infos: List<WorkInfo>) {
        listUploads.removeAllViews()
        val items = infos
            .map { info -> (info.tags.firstOrNull { it.startsWith(UploadWorker.LABEL_PREFIX) }
                ?.removePrefix(UploadWorker.LABEL_PREFIX) ?: "?") to info }
            .sortedByDescending { it.first }
            .take(30)
        if (items.isEmpty()) {
            listUploads.addView(row(getString(R.string.no_uploads)))
            return
        }
        items.forEach { (label, info) ->
            val state = when (info.state) {
                WorkInfo.State.ENQUEUED ->
                    if (info.runAttemptCount > 0) getString(R.string.state_retry) else getString(R.string.state_enqueued)
                WorkInfo.State.RUNNING -> getString(R.string.state_running) +
                    (info.progress.getString(UploadWorker.KEY_PROGRESS)?.let { " $it" } ?: "")
                WorkInfo.State.SUCCEEDED -> "✓ " + getString(R.string.state_succeeded)
                WorkInfo.State.FAILED -> "✗ " + getString(R.string.state_failed) +
                    (info.outputData.getString(UploadWorker.KEY_ERROR)?.let { ": $it" } ?: "")
                WorkInfo.State.CANCELLED -> getString(R.string.state_cancelled)
                WorkInfo.State.BLOCKED -> getString(R.string.state_enqueued)
            }
            listUploads.addView(row("$label\n$state"))
        }
    }

    private fun row(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 12, 0, 12)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_SINGLE = "it.leproide.webdavscanner.SCAN_SINGLE"
        const val ACTION_MULTI = "it.leproide.webdavscanner.SCAN_MULTI"
    }
}
