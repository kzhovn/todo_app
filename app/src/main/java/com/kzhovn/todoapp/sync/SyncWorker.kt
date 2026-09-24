package com.kzhovn.todoapp.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.widget.TodoWidget
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TodoApp
        val config = SyncSettings.config(app) ?: return Result.success()
        val outcome = runCatching { app.syncClient.sync(config) }
        SyncSettings.recordResult(app, outcome)
        if ((outcome.getOrNull() ?: 0) > 0) TodoWidget().updateAll(app)
        // Only the periodic chain re-arms itself, so on-demand syncs never grow it.
        if (inputData.getBoolean(KEY_PERIODIC, false)) enqueue(app, PERIODIC, 5, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val NOW = "sync-now"
        private const val KEY_PERIODIC = "periodic"

        // PeriodicWorkRequest can't go below 15 minutes, so the 5-minute cadence is a self-renewing
        // one-time chain. Doze still defers it while the phone is idle.
        fun ensurePeriodic(context: Context) = enqueue(context, PERIODIC, 5, ExistingWorkPolicy.KEEP)

        // REPLACE doubles as a debounce: a burst of edits collapses into one sync after the last.
        fun requestSoon(context: Context, delaySeconds: Long = 3) =
            enqueue(context, NOW, delaySeconds, ExistingWorkPolicy.REPLACE, unit = TimeUnit.SECONDS)

        private fun enqueue(
            context: Context, name: String, delay: Long, policy: ExistingWorkPolicy, unit: TimeUnit = TimeUnit.MINUTES
        ) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delay, unit)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_PERIODIC to (name == PERIODIC)))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }
    }
}

object SyncSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun config(context: Context): SyncConfig? {
        val prefs = prefs(context)
        val url = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
        return SyncConfig(url, token)
    }

    fun save(context: Context, url: String, token: String) =
        prefs(context).edit().putString("url", url.trim()).putString("token", token.trim()).apply()

    fun status(context: Context): String? = prefs(context).getString("status", null)

    fun recordResult(context: Context, outcome: kotlin.Result<Int>) {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        val text = outcome.fold({ "Last synced $time ($it changes pulled)" }, { "Sync failed at $time: ${it.message ?: it.javaClass.simpleName}" })
        prefs(context).edit().putString("status", text).apply()
    }
}
