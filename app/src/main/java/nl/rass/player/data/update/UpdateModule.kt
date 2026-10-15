package nl.rass.player.data.update

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import nl.rass.player.BuildConfig
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides @Singleton
    fun device(): DeviceInfo =
        DeviceInfo(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    @Provides @Singleton
    fun inspector(@ApplicationContext c: Context): ApkInspector = AndroidApkInspector(c)

    @Provides @Singleton
    fun installer(@ApplicationContext c: Context): ApkInstaller = SessionApkInstaller(c)

    @Provides @Singleton
    fun updateDir(@ApplicationContext c: Context): UpdateDir = UpdateDir(File(c.cacheDir, "update"))
}
