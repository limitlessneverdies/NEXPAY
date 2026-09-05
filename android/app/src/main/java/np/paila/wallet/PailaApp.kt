package np.paila.wallet

import android.app.Application
import android.content.Context
import androidx.work.*
import np.paila.wallet.core.WalletRepository
import java.util.concurrent.TimeUnit

class PailaApp : Application() {
    val repository by lazy { WalletRepository(this) }
    override fun onCreate() {
        super.onCreate()
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("paila.sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = (applicationContext as PailaApp).repository
        if (repo.state.value.ready) { repo.sync(); if (!repo.state.value.connected) Result.retry() else Result.success() } else Result.success()
    } catch (_: Exception) { Result.retry() }
}
