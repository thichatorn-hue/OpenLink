package com.carlink.core.connection

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import com.andrerinas.openheadunit.utils.Settings
import com.carlink.core.connection.aa.AaEngineHooks
import com.carlink.core.connection.aa.AaProtocol
import com.carlink.core.core.PhoneKind
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.entry.UsbWatcher
import com.shilapi.xcertplay.AirPlayPersistence

private const val INITIAL_CAR_NAME = "OpenLink"

interface CarLinkHost {
    enum class OpenReason {
        BOOT,
        PHONE,
        UNPLUGGED,
        ENGINE_START,
    }

    fun openHome(context: Context, reason: OpenReason, address: String? = null, kind: PhoneKind? = null, name: String? = null)
    fun onCallMicrophoneConflict(context: Context) {}
}

class CarLinkConfig(
    val accessoryName: String,
    val host: CarLinkHost,
    val waitingView: ((Context) -> View)? = null,
)

object CarLinkCore {
    @Volatile private var installedApp: Application? = null
    @Volatile private var installedBase: CarLinkConfig? = null

    val config: CarLinkConfig
        get() {
            val base = installedBase ?: error("CarLinkCore.install(application, config) was not called")
            val app = installedApp ?: return base
            val activeName = resolveConfiguredName(app, INITIAL_CAR_NAME)
            return if (activeName == base.accessoryName) {
                base
            } else {
                CarLinkConfig(
                    accessoryName = activeName,
                    host = base.host,
                    waitingView = base.waitingView,
                )
            }
        }

    fun resolveConfiguredName(context: Context, fallback: String = INITIAL_CAR_NAME): String {
        val model = runCatching { AirPlayPersistence.loadModel(context) }.getOrNull()?.trim()
        if (!model.isNullOrEmpty() && model != AirPlayPersistence.DEFAULT_MODEL) {
            return model
        }
        val manufacturer = runCatching { AirPlayPersistence.loadManufacturer(context) }.getOrNull()?.trim()
        if (!manufacturer.isNullOrEmpty() && manufacturer != AirPlayPersistence.DEFAULT_MANUFACTURER) {
            return manufacturer
        }
        return INITIAL_CAR_NAME
    }

    fun install(app: Application, config: CarLinkConfig) {
        installedApp = app
        nameCarForIphone(app, INITIAL_CAR_NAME)
        val initialName = resolveConfiguredName(app, INITIAL_CAR_NAME)
        installedBase = CarLinkConfig(
            accessoryName = initialName,
            host = config.host,
            waitingView = config.waitingView,
        )
        com.carlink.core.detect.UsbAttachTimes.watch(app)
        if (isAndroidAutoProcess(app)) {
            nameCarForPhone(app, initialName)
            AaEngineHooks.install(app)
        } else {
            EventLog.attachFile(java.io.File(app.filesDir, "logs"))
            val paths = PathMemory(app)
            com.carlink.core.detect.KnownPhones(app).dropUnprovenAndroid { paths.lastWorking(com.carlink.core.core.Engine.ANDROID_AUTO, it) != null }
                .takeIf { it.isNotEmpty() }?.let { EventLog.add("CarLink", "dropped ${it.size} guessed Android phone type(s)") }
            com.carlink.core.detect.ExternalBluetoothProbe.start(app)
            CarPlayAdapter.install(app, Connection.vehicleProfile(app))
            CarPlayWaitingView.register(config.waitingView)
            UsbWatcher.get(app).start()
            HeadUnitSignals.install(app)
            app.registerComponentCallbacks(MemoryTrimmer)
            deferStartupWork(app)
        }
    }

    fun isAndroidAutoProcess(context: Context): Boolean = processName(context).endsWith(AaProtocol.ENGINE_PROCESS_SUFFIX)

    private fun deferStartupWork(app: Application) {
        Handler(Looper.getMainLooper()).postDelayed({
            Connection.prewarm(app)
            Thread({ runCatching { DeviceCapabilities.read(app) } }, "carlink-capabilities").apply {
                priority = Thread.MIN_PRIORITY
            }.start()
            GpuProbe.ensure(app)
        }, STARTUP_DEFER_MILLIS)
    }

    private object MemoryTrimmer : android.content.ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                DeviceCapabilities.trimCaches()
            }
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() { DeviceCapabilities.trimCaches() }
    }

    private fun nameCarForPhone(app: Application, name: String) {
        val prefs = app.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_VEHICLE_DISPLAY_NAME)) {
            prefs.edit().putString(KEY_VEHICLE_DISPLAY_NAME, name).apply()
        }
    }

    private fun nameCarForIphone(app: Application, name: String) {
        if (AirPlayPersistence.loadManufacturer(app) == AirPlayPersistence.DEFAULT_MANUFACTURER) {
            AirPlayPersistence.saveManufacturer(app, name)
        }
        if (AirPlayPersistence.loadModel(app) == AirPlayPersistence.DEFAULT_MODEL) {
            AirPlayPersistence.saveModel(app, name)
        }
    }

    private fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val pid = Process.myPid()
        return runCatching {
            java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault(context.packageName)
    }

    private const val STARTUP_DEFER_MILLIS = 2_500L
    private const val KEY_VEHICLE_DISPLAY_NAME = "vehicle-display-name"
}
