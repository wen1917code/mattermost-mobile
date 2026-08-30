package com.wen.struggle


import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.ReleaseLevel
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.modules.network.OkHttpClientProvider
import com.mattermost.networkclient.RCTOkHttpClientFactory
import com.mattermost.rnshare.helpers.RealPathUtil
import com.mattermost.turbolog.TurboLog
import com.mattermost.turbolog.ConfigureOptions
import io.sentry.react.RNSentrySDK
import com.nozbe.watermelondb.jsi.WatermelonDBJSIPackage
import com.wen.struggle.services.KeepAliveService
import com.wix.reactnativenotifications.core.AppLaunchHelper
import com.wix.reactnativenotifications.core.AppLifecycleFacade
import com.wix.reactnativenotifications.core.JsIOHelper
import com.wix.reactnativenotifications.core.notification.INotificationsApplication
import com.wix.reactnativenotifications.core.notification.IPushNotification
import expo.modules.ApplicationLifecycleDispatcher
import expo.modules.ExpoReactHostFactory
import expo.modules.image.okhttp.ExpoImageOkHttpClientGlideModule
import java.io.File

class MainApplication : Application(), ReactApplication, INotificationsApplication {

    override val reactHost: ReactHost by lazy {
        DefaultNewArchitectureEntryPoint.releaseLevel = try {
            ReleaseLevel.valueOf(BuildConfig.REACT_NATIVE_RELEASE_LEVEL.uppercase())
        } catch (e: IllegalArgumentException) {
            ReleaseLevel.STABLE
        }
        ExpoReactHostFactory.getDefaultReactHost(
            context = applicationContext,
            packageList = PackageList(this).packages.apply {
                add(WatermelonDBJSIPackage())
                add(InstallApkPackage())
            },
            jsMainModulePath = "index"
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Create notification channels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Custom channel for WebSocket push
            val msgChannel = NotificationChannel(
                "messages",
                "新消息",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                setBypassDnd(true)
                description = "消息推送通知"
            }
            manager.createNotificationChannel(msgChannel)
            // Default channel used by react-native-notifications
            val defaultChannel = NotificationChannel(
                "channel_01",
                "消息通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                setBypassDnd(true)
                description = "接收新消息通知"
            }
            manager.createNotificationChannel(defaultChannel)
        }

        // 启动保活前台服务（幂等：服务已在运行时只是重新评估策略）
        KeepAliveService.start(this)

        // WorkManager 兜底体检：部分 ROM 杀闹钟但放过 JobScheduler
        try {
            val request = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES,
            ).build()
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "struggle_keepalive",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            android.util.Log.e("KeepAlive", "WorkManager 调度失败: ${e.message}")
        }

        // Initialize Sentry early for native crash reporting
        RNSentrySDK.init(this)

        // Delete any previous temp files created by the app
        val tempFolder = File(applicationContext.cacheDir, RealPathUtil.CACHE_DIR_NAME)
        RealPathUtil.deleteTempFiles(tempFolder)
        TurboLog.configure(options = ConfigureOptions(logsDirectory = applicationContext.cacheDir.absolutePath + "/logs", logPrefix = applicationContext.packageName))

        TurboLog.i("ReactNative", "Cleaning temp cache " + tempFolder.absolutePath)

        // Tells React Native to use our RCTOkHttpClientFactory which builds an OKHttpClient
        // with a cookie jar defined in APIClientModule and an interceptor to intercept all
        // requests that originate from React Native's OKHttpClient
        OkHttpClientProvider.setOkHttpClientFactory(RCTOkHttpClientFactory())
        // ExpoImageOkHttpClientGlideModule.okHttpClient = RCTOkHttpClientFactory().createNewNetworkModuleClient()

        loadReactNative(this)
        ApplicationLifecycleDispatcher.onApplicationCreate(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ApplicationLifecycleDispatcher.onConfigurationChanged(this, newConfig)
    }

    override fun getPushNotification(
        context: Context?,
        bundle: Bundle?,
        defaultFacade: AppLifecycleFacade?,
        defaultAppLaunchHelper: AppLaunchHelper?
    ): IPushNotification {
        return CustomPushNotification(
            context!!,
            bundle!!,
            defaultFacade!!,
            defaultAppLaunchHelper!!,
            JsIOHelper()
        )
    }

    @SuppressLint("VisibleForTests")
    private fun runOnJSQueueThread(action: () -> Unit) {
        reactHost.currentReactContext?.runOnJSQueueThread {
            action()
        } ?: UiThreadUtil.runOnUiThread {
            reactHost.currentReactContext?.runOnJSQueueThread {
                action()
            }
        }
    }
}
