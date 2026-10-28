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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Local storage housekeeping, so scans never pile up on the phone:
 *  - uploaded files are deleted by [UploadWorker] right after each successful upload;
 *  - files of FAILED jobs are kept [FAILED_RETENTION_MS] so the user can tap "Retry",
 *    then deleted automatically;
 *  - orphan job folders (no job record, e.g. app killed mid-copy or record pruned
 *    by WorkManager) are deleted;
 *  - leftovers of the scanner in the cache older than [CACHE_MAX_AGE_MS] are deleted.
 * Jobs waiting for network / retrying are never touched.
 */
object Storage {
    private const val TAG = "Storage"
    val FAILED_RETENTION_MS = TimeUnit.DAYS.toMillis(3)
    private val ORPHAN_GRACE_MS = TimeUnit.MINUTES.toMillis(10) // job being created right now
    private val CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24)

    fun pendingRoot(ctx: Context) = File(ctx.filesDir, "pending")

    fun jobDir(ctx: Context, jobId: String) = File(pendingRoot(ctx), jobId)

    /** Job id (folder name) carried by a WorkInfo tag. */
    fun jobIdOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(UploadWorker.JOB_PREFIX) }?.removePrefix(UploadWorker.JOB_PREFIX)

    /** Automatic cleanup; safe to call often (app start). */
    suspend fun cleanup(ctx: Context) {
        val infos = WorkManager.getInstance(ctx).getWorkInfosByTagFlow(UploadWorker.WORK_TAG).first()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // Newest record per job (a retried job has several records).
            val byJob = infos.groupBy { jobIdOf(it) }.filterKeys { it != null }
            val active = byJob.filterValues { list -> list.any { !it.state.isFinished } }.keys
            val failed = byJob.filterValues { list -> list.all { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED } }.keys

            pendingRoot(ctx).listFiles()?.forEach { dir ->
                val age = now - dir.lastModified()
                val delete = when (dir.name) {
                    in active -> false
                    in failed -> age > FAILED_RETENTION_MS
                    else -> age > ORPHAN_GRACE_MS // succeeded (already empty) or orphan
                }
                if (delete) {
                    Log.i(TAG, "Deleting local job ${dir.name}")
                    dir.deleteRecursively()
                }
            }
            deleteOlderThan(ctx.cacheDir, now - CACHE_MAX_AGE_MS)
        }
    }

    /** Deletes the local files of one job (user choice on a failed upload). */
    fun deleteJob(ctx: Context, jobId: String) {
        jobDir(ctx, jobId).deleteRecursively()
    }

    fun jobHasFiles(ctx: Context, jobId: String): Boolean =
        jobDir(ctx, jobId).listFiles()?.any { it.isFile } == true

    /** Total bytes waiting on the device (shown in the UI). */
    fun pendingBytes(ctx: Context): Long =
        pendingRoot(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun deleteOlderThan(dir: File, limit: Long) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                deleteOlderThan(f, limit)
                if (f.listFiles().isNullOrEmpty()) f.delete()
            } else if (f.lastModified() < limit) {
                f.delete()
            }
        }
    }
}
