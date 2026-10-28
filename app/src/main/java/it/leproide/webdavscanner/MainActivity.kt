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
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.widget.ScrollView
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    private lateinit var btnRetryAll: Button

    /** Failed / retrying jobs whose files are still on the phone: (jobId, label, newest record). */
    private var retryable: List<Triple<String, String, WorkInfo>> = emptyList()

    /** Jobs currently queued or running (newest record not finished). */
    private var activeJobs: Set<String> = emptySet()

    /** Jobs re-queued by the user since the last list refresh (double-tap guard). */
    private var retriedNow: Set<String> = emptySet()

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
        findViewById<android.view.View>(R.id.btnClear).setOnClickListener { clearFinished() }
        btnRetryAll = findViewById(R.id.btnRetryAll)
        btnRetryAll.setOnClickListener {
            retryable.forEach { (job, label, info) ->
                retryJob(job, label, info.takeIf { it.state == WorkInfo.State.ENQUEUED })
            }
        }
        findViewById<TextView>(R.id.txtCredits).apply {
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            text = getString(R.string.credits_footer, version)
            setOnClickListener { openGithub() }
        }

        observeUploads()
        // Automatic cleanup of old failed/orphan files and scanner leftovers.
        lifecycleScope.launch { runCatching { Storage.cleanup(applicationContext) } }
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
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); return true
            }
            R.id.action_about -> {
                showAbout(); return true
            }
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
                } finally {
                    deleteScannerOutput(result)
                }
            }
            if (jobDir == null || jobDir.list().isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.scanner_error, getString(R.string.err_no_files)), Toast.LENGTH_LONG).show()
                return@launch
            }
            UploadWorker.enqueue(this@MainActivity, jobDir, base)
            Toast.makeText(this@MainActivity, getString(R.string.queued, base), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The scanner leaves its PDF/JPEG output in our cache: delete it right away so
     * scanned documents do not linger on the device after the upload.
     */
    private fun deleteScannerOutput(result: GmsDocumentScanningResult) {
        val uris = listOfNotNull(result.pdf?.uri) + result.pages.orEmpty().map { it.imageUri }
        val roots = listOf(cacheDir.canonicalPath, filesDir.canonicalPath)
        uris.filter { it.scheme == "file" }.mapNotNull { it.path?.let(::File) }.forEach { f ->
            val p = runCatching { f.canonicalPath }.getOrNull() ?: return@forEach
            // Only our own private dirs, never the pending upload folder.
            if (roots.any { p.startsWith(it + File.separator) } && !p.contains("${File.separator}pending${File.separator}")) {
                f.delete()
            }
        }
    }

    /**
     * "Clear list". If uploads are still queued / retrying / running, asks for
     * confirmation first: they are cancelled and their local files deleted.
     */
    private fun clearFinished() {
        lifecycleScope.launch {
            val wm = WorkManager.getInstance(this@MainActivity)
            val infos = wm.getWorkInfosByTagFlow(UploadWorker.WORK_TAG).first()
            // Not completed = queued / retrying / running, or failed with the scan still on the phone.
            val newest = infos.groupBy { Storage.jobIdOf(it) }
                .filterKeys { it != null }
                .mapValues { (_, l) -> l.maxBy { i -> timeOf(i) } }
            val pendingJobs = newest.filter { (job, info) ->
                !info.state.isFinished ||
                    (info.state == WorkInfo.State.FAILED && Storage.jobHasFiles(this@MainActivity, job!!))
            }.keys
            if (pendingJobs.isEmpty()) {
                clearAll(cancelPending = false)
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(getString(R.string.clear_confirm_msg, pendingJobs.size))
                .setPositiveButton(R.string.clear_confirm_ok) { _, _ ->
                    lifecycleScope.launch { clearAll(cancelPending = true) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Empties the list and deletes local files ([cancelPending] = also not-completed uploads). */
    private suspend fun clearAll(cancelPending: Boolean) {
        val ctx = applicationContext
        val wm = WorkManager.getInstance(ctx)
        withContext(Dispatchers.IO) {
            if (cancelPending) {
                // Wait until the cancellation is applied, so pruneWork() removes those records too.
                runCatching { wm.cancelAllWorkByTag(UploadWorker.WORK_TAG).result.get() }
            }
            val infos = wm.getWorkInfosByTagFlow(UploadWorker.WORK_TAG).first()
            val stillActive = infos.filter { !it.state.isFinished }.mapNotNull { Storage.jobIdOf(it) }.toSet()
            Storage.pendingRoot(ctx).listFiles()
                ?.filter { it.name !in stillActive }
                ?.forEach { Storage.deleteJob(ctx, it.name) }
            Prefs.setDismissedJobs(ctx, emptySet())
            runCatching { wm.pruneWork().result.get() }
        }
        retriedNow = emptySet()
    }

    /** Failed upload: retry (files still on the phone), open settings, or delete it. */
    private fun onFailedJob(jobId: String, label: String, error: String?) {
        val hasFiles = Storage.jobHasFiles(this, jobId)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(
                (error ?: getString(R.string.state_failed)) + "\n\n" +
                    getString(if (hasFiles) R.string.failed_kept else R.string.failed_gone,
                        TimeUnit.MILLISECONDS.toDays(Storage.FAILED_RETENTION_MS))
            )
            .setNeutralButton(R.string.settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                Storage.deleteJob(this, jobId)
                Prefs.setDismissedJobs(this, Prefs.dismissedJobs(this) + jobId)
                refreshUploads()
            }
        if (hasFiles) b.setPositiveButton(R.string.retry) { _, _ ->
            retryJob(jobId, label, retryable.firstOrNull { it.first == jobId }?.third?.takeIf { it.state == WorkInfo.State.ENQUEUED })
        }
        b.show()
    }

    /**
     * Manual retry. [waiting] = the job is in automatic retry (backoff): it is
     * cancelled and re-queued to run immediately.
     */
    private fun retryJob(jobId: String, label: String, waiting: WorkInfo? = null) {
        // Ignore double taps.
        if (jobId in retriedNow || !Storage.jobHasFiles(this, jobId)) return
        if (waiting == null && jobId in activeJobs) return
        retriedNow = retriedNow + jobId
        val wm = WorkManager.getInstance(this)
        if (waiting != null) {
            if (waiting.state != WorkInfo.State.ENQUEUED) return // running right now: let it finish
            wm.cancelWorkById(waiting.id)
        }
        UploadWorker.enqueue(this, Storage.jobDir(this, jobId), label)
        Toast.makeText(this, getString(R.string.queued, label), Toast.LENGTH_SHORT).show()
    }

    private fun refreshUploads() {
        lifecycleScope.launch {
            renderUploads(
                WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(UploadWorker.WORK_TAG).first()
            )
        }
    }

    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val html = getString(R.string.about_html, version, GITHUB_URL)
        val msg = TextView(this).apply {
            text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setView(ScrollView(this).apply { addView(msg) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.open_github) { _, _ -> openGithub() }
            .show()
    }

    private fun openGithub() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }
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
        val dismissed = Prefs.dismissedJobs(this)
        fun tag(i: WorkInfo, p: String) = i.tags.firstOrNull { it.startsWith(p) }?.removePrefix(p)
        val items = infos
            // A retried job has several records: keep only the newest one.
            .groupBy { Storage.jobIdOf(it) ?: it.id.toString() }
            .filterKeys { it !in dismissed }
            .map { (job, list) -> job to list.maxBy { timeOf(it) } }
            .map { (job, info) -> Triple(job, tag(info, UploadWorker.LABEL_PREFIX) ?: "?", info) }
            .sortedByDescending { it.second }
            .take(30)

        // Space used on the phone by scans not uploaded yet.
        val pending = Storage.pendingBytes(this)
        if (pending > 0) {
            listUploads.addView(row(getString(R.string.pending_space, Formatter.formatShortFileSize(this, pending))))
        }
        activeJobs = items.filter { !it.third.state.isFinished }.map { it.first }.toSet()
        retriedNow = emptySet()
        retryable = items.filter { canRetry(it.third) && Storage.jobHasFiles(this, it.first) }
        btnRetryAll.visibility = if (retryable.size > 1) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            listUploads.addView(row(getString(R.string.no_uploads)))
            return
        }
        items.forEach { (job, label, info) ->
            // Final error (failed) or last attempt's error (automatic retry in progress).
            val error = info.outputData.getString(UploadWorker.KEY_ERROR) ?: Prefs.jobError(this, job)
            val state = when (info.state) {
                WorkInfo.State.ENQUEUED ->
                    if (info.runAttemptCount > 0)
                        getString(R.string.state_retry_n, info.runAttemptCount) + (error?.let { "\n$it" } ?: "")
                    else getString(R.string.state_enqueued)
                WorkInfo.State.RUNNING -> getString(R.string.state_running) +
                    (info.progress.getString(UploadWorker.KEY_PROGRESS)?.let { " $it" } ?: "")
                WorkInfo.State.SUCCEEDED -> "✓ " + getString(R.string.state_succeeded)
                WorkInfo.State.FAILED -> "✗ " + getString(R.string.state_failed) +
                    (error?.let { ": $it" } ?: "")
                WorkInfo.State.CANCELLED -> getString(R.string.state_cancelled)
                WorkInfo.State.BLOCKED -> getString(R.string.state_enqueued)
            }
            val text = row("$label\n$state")
            if (!canRetry(info)) {
                listUploads.addView(text)
                return@forEach
            }
            // Failed / retrying: text opens the options dialog, visible "Retry" button when files are still here.
            text.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text.background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                .let { a -> a.getDrawable(0).also { a.recycle() } }
            text.setOnClickListener { onFailedJob(job, label, error) }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text)
            }
            if (Storage.jobHasFiles(this, job)) {
                line.addView(
                    MaterialButton(this).apply {
                        setText(if (info.state == WorkInfo.State.FAILED) R.string.retry else R.string.retry_now)
                        setOnClickListener {
                            retryJob(job, label, info.takeIf { it.state == WorkInfo.State.ENQUEUED })
                        }
                    }
                )
            }
            listUploads.addView(line)
        }
    }

    /** Enqueue time stored in the tags (0 for records created by older versions). */
    private fun timeOf(i: WorkInfo): Long =
        i.tags.firstOrNull { it.startsWith(UploadWorker.TIME_PREFIX) }
            ?.removePrefix(UploadWorker.TIME_PREFIX)?.toLongOrNull() ?: 0L

    /** Failed, or waiting for the next automatic attempt after an error. */
    private fun canRetry(info: WorkInfo) =
        info.state == WorkInfo.State.FAILED ||
            (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0)

    private fun row(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 12, 0, 12)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    }

    companion object {
        private const val TAG = "MainActivity"
        const val GITHUB_URL = "https://github.com/Leproide"
        const val ACTION_SINGLE = "it.leproide.webdavscanner.SCAN_SINGLE"
        const val ACTION_MULTI = "it.leproide.webdavscanner.SCAN_MULTI"
    }
}
