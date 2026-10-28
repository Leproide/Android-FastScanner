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
        if (!cfg.isConfigured) return@withContext fail(applicationContext.getString(R.string.err_not_configured))

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
                Prefs.setJobError(applicationContext, jobDir.name, null)
                Result.success(workDataOf(KEY_RESULT to up.destination))
            }
        } catch (e: UploadException) {
            Log.w(TAG, "Upload failed", e)
            retryOrFail(jobDir, e, e.permanent)
        } catch (e: IOException) {
            // Network / TLS / timeout: transient unless we've tried too often.
            Log.w(TAG, "Upload I/O error", e)
            retryOrFail(jobDir, e, false)
        } catch (e: Exception) {
            Log.e(TAG, "Upload unexpected error", e)
            retryOrFail(jobDir, e, true)
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
                if (n >= 50) throw UploadException(R.string.err_too_many, permanent = true)
                n++
            }
        }
    }

    private fun fail(msg: String) = Result.failure(workDataOf(KEY_ERROR to msg))

    /** Remembers the error (shown in the list while retrying) and retries or fails. */
    private fun retryOrFail(jobDir: File, e: Throwable, permanent: Boolean): Result {
        val msg = e.userMessage(applicationContext)
        Prefs.setJobError(applicationContext, jobDir.name, msg)
        return if (permanent || runAttemptCount + 1 >= MAX_ATTEMPTS) fail(msg) else Result.retry()
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_TAG = "upload"
        const val LABEL_PREFIX = "label:"
        const val JOB_PREFIX = "job:"
        const val TIME_PREFIX = "t:"
        const val KEY_DIR = "dir"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_RESULT = "result"
        // 30s, 1m, 2m, 4m, 8m, 16m, 32m, 64m -> definitive failure after ~2 hours
        private const val MAX_ATTEMPTS = 8

        /** Queues the upload of a prepared job directory. [label] is shown in the UI. */
        fun enqueue(ctx: Context, jobDir: File, label: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_DIR to jobDir.absolutePath))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .addTag(LABEL_PREFIX + label)
                .addTag(JOB_PREFIX + jobDir.name) // lets the UI retry / delete files of failed jobs
                .addTag(TIME_PREFIX + System.currentTimeMillis()) // newest record wins after a retry
                .build()
            jobDir.setLastModified(System.currentTimeMillis()) // restarts the failed-retention clock
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
