package io.github.arnavdugad.metropulse.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.arnavdugad.metropulse.BuildConfig
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun buildInfo(): AppBuildInfo = AppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        updateFeedUrl = BuildConfig.UPDATE_FEED_URL,
        updatesEnabled = BuildConfig.UPDATES_ENABLED && BuildConfig.UPDATE_FEED_URL.isNotBlank(),
        dataManifestUrl = BuildConfig.DATA_MANIFEST_URL,
        mtaRealtime = BuildConfig.MTA_REALTIME,
    )
}
