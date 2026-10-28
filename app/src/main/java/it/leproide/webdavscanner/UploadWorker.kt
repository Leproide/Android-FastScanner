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
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads (WebDAV or SMB) every file of one scan job (directory under filesDir/pending/<job>).
 * Survives app close / reboot; waits for network; retries with backoff.
 * Each local file is deleted right after its successful upload, so a retry
 * only re-sends what is still missing.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobDir = File(inputData.getString(KEY_DIR) ?: return@withContext Result.failure())
        val files = jobDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            jobDir.delete()
            return@withContext Result.success()
        }

        val cfg = Prefs.load(applicationContext)
        if (!cfg.isConfigured) return@withContext fail("Server non configurato")

        try {
            // Back-end chosen in settings (WebDAV or SMB) at upload time.
            Uploader.forConfig(cfg).use { up ->
                up.prepare()
                files.forEachIndexed { i, f ->
                    setProgress(workDataOf(KEY_PROGRESS to "${i + 1}/${files.size}"))
                    uploadUnique(up, f)
                    f.delete()
                }
                jobDir.delete()
                Result.success(workDataOf(KEY_RESULT to up.destination))
            }
        } catch (e: UploadException) {
            Log.w(TAG, "Upload failed", e)
            if (e.permanent || runAttemptCount >= MAX_ATTEMPTS) fail(e.message ?: "Errore")
            else Result.retry()
        } catch (e: IOException) {
            // Network / TLS / timeout: transient unless we've tried too often.
            Log.w(TAG, "Upload I/O error", e)
            if (runAttemptCount >= MAX_ATTEMPTS) fail(e.message ?: e.javaClass.simpleName) else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Upload unexpected error", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Never overwrite: if the name exists append -2, -3 ... */
    private fun uploadUnique(up: Uploader, f: File) {
        val dot = f.name.lastIndexOf('.')
        val stem = if (dot > 0) f.name.substring(0, dot) else f.name
        val ext = if (dot > 0) f.name.substring(dot) else ""
        val mime = if (ext.equals(".pdf", true)) "application/pdf" else "image/jpeg"
        var n = 1
        while (true) {
            val name = if (n == 1) f.name else "$stem-$n$ext"
            try {
                up.upload(f, name, mime)
                return
            } catch (e: AlreadyExistsException) {
                if (n >= 50) throw UploadException("Troppi file con lo stesso nome", permanent = true)
                n++
            }
        }
    }

    private fun fail(msg: String) = Result.failure(workDataOf(KEY_ERROR to msg))

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_TAG = "upload"
        const val LABEL_PREFIX = "label:"
        const val KEY_DIR = "dir"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_RESULT = "result"
        private const val MAX_ATTEMPTS = 20

        /** Queues the upload of a prepared job directory. [label] is shown in the UI. */
        fun enqueue(ctx: Context, jobDir: File, label: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_DIR to jobDir.absolutePath))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .addTag(LABEL_PREFIX + label)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
