package nl.rass.player.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun updateRepository(): UpdateRepository
}

/** Daily silent check. It only records a flag (see [UpdateFlag]); it posts no notification. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val repo = EntryPointAccessors.fromApplication(applicationContext, UpdateEntryPoint::class.java).updateRepository()
        repo.check(url)
        when (val s = repo.state.value) {
            is UpdateState.Available -> UpdateFlag.set(applicationContext, s.versionName)
            is UpdateState.UpToDate -> UpdateFlag.set(applicationContext, null)
            else -> Unit
        }
        return Result.success()
    }

    companion object {
        const val KEY_URL = "update_url"
    }
}

/** Persisted "update available" flag, readable by UI without a network call. */
object UpdateFlag {
    private const val PREFS = "rass_update"
    private const val KEY = "available_version_name"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(context: Context, versionName: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (versionName == null) remove(KEY) else putString(KEY, versionName)
        }.apply()
    }
}

object UpdateScheduler {
    private const val NAME = "rass_update_check"

    fun schedule(context: Context, updateUrl: String) {
        val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(UpdateCheckWorker.KEY_URL to updateUrl))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
