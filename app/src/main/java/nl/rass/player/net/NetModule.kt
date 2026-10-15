package nl.rass.player.net

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Plain OkHttpClient WITHOUT the private-connection wiring. Only for net/ internals (latency probes through
 * a specific test port, subscription download with an explicit route). App code uses the shared client
 * from di/HttpModule.kt.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class NetBindings {
    @Binds abstract fun runtime(impl: XrayController): ProxyRuntime
    @Binds abstract fun tester(impl: LatencyTester): ConfigTester
    @Binds abstract fun routeSource(impl: ProxyManager): RouteSource
}

@Module
@InstallIn(SingletonComponent::class)
object NetModule {

    @Provides
    @Singleton
    @PlainHttpClient
    fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @ProxyScope
    fun proxyScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun timing(): ProxyTiming = ProxyTiming()

    @Provides
    @Singleton
    fun engineFactory(@ApplicationContext context: Context): XrayEngineFactory = LibXrayEngine.Factory(context)

    @Provides
    fun ports(): PortAllocator = LoopbackPortAllocator

    @Provides
    @Singleton
    fun httpProbe(@PlainHttpClient plain: OkHttpClient): HttpProbe = OkHttpProbe(plain)

    @Provides
    @Singleton
    fun fetcher(@PlainHttpClient plain: OkHttpClient): SubscriptionFetcher = OkHttpSubscriptionFetcher(plain)

    @Provides
    @Singleton
    fun store(@ApplicationContext context: Context): ProxyStore = SecureConfigStore(
        file = File(context.noBackupFilesDir, SecureConfigStore.FILE_NAME),
        cipher = AesGcmBlobCipher(
            object : () -> javax.crypto.SecretKey {
                @Volatile private var key: javax.crypto.SecretKey? = null
                override fun invoke(): javax.crypto.SecretKey =
                    key ?: AndroidKeystoreKey.getOrCreate(SecureConfigStore.KEY_ALIAS).also { key = it }
            },
        ),
    )
}
