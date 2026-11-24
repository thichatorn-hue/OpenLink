package com.carlink.core.connection

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.diagnostics.InterfaceIpv6
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WirelessPath
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus
import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline
import java.util.concurrent.CopyOnWriteArrayList

class CarPlayAdapter internal constructor(
    private val hooks: CarPlayEngineHooks,
    private val externalBluetooth: () -> Boolean = { false },
    private val onFailure: (String?) -> Unit = {},
    private val appContext: Context? = null,
) : EngineAdapter {

    constructor(context: Context) : this(
        DiPlayEngineHooks(context.applicationContext),
        {
            com.carlink.core.detect.ExternalBluetoothProbe.current(context.applicationContext).classification.hasSignals
        },
        { WifiDirectSupport.noteFailure(context.applicationContext, it) },
        context.applicationContext,
    )

    override val engine: Engine = Engine.CARPLAY

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((AdapterEvent) -> Unit)? = null
    private var tracker: CarPlaySessionTracker? = null
    private var plan: SessionPlan? = null

    val lastFailure: CarPlayFailure? get() = tracker?.lastFailure

    private val statusListener: (CarPlayStatus?) -> Unit = { status -> main.post { onStatus(status) } }
    private val hostStateListener: (HostState) -> Unit = { state -> main.post { onHostState(state) } }
    private val milestoneListener: (WirelessStartupTimeline.Phase) -> Unit = { phase -> main.post { onMilestone(phase) } }

    private val linkWatch = CarPlayLinkWatch()
    private var watching = false
    private val clientsListener: (Int, Int) -> Unit = { clients, leased ->
        main.post { if (watching) linkWatch.onClients(clients, leased, SystemClock.uptimeMillis()) }
    }
    private val startAgainWanted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val linkPoll = object : Runnable {
        override fun run() {
            if (!watching) return
            if (linkWatch.startAgain(SystemClock.uptimeMillis())) {
                com.carlink.core.diagnostics.EventLog.add("CarLink", "the iPhone joined the car's Wi-Fi but opened no CarPlay connection; asking it to start once more")
                startAgainWanted.set(true)
            }
            linkWatch.verdict(SystemClock.uptimeMillis())?.let(::onLinkVerdict)
            if (watching) main.postDelayed(this, CarPlayLinkWatch.POLL_MILLIS)
        }
    }

    override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
        require(plan.engine == Engine.CARPLAY) { "CarPlayAdapter cannot start ${plan.engine}" }
        appContext?.let { syncAccessoryName(it) }
        if (hooks.hasSession()) {
            Log.w(TAG, "start while a CarPlay session still runs; stopping it first")
            hooks.stopSession { start(plan, listener) }
            return
        }
        detach()
        val tracker = CarPlaySessionTracker(plan.transport,
            externalBluetooth = plan.transport == Transport.WIRELESS && runCatching(externalBluetooth).getOrDefault(false))
        this.tracker = tracker
        this.plan = plan
        this.listener = listener
        hooks.addHostStateListener(hostStateListener)
        hooks.addStatusListener(statusListener)
        hooks.setMilestoneListener(milestoneListener)
        Log.i(TAG, "start transport=${plan.transport} path=${plan.path} attempt=${plan.attempt} " +
            "phone=${plan.phoneAddress != null} usb=${plan.usbAttach != null}")
        if (plan.transport == Transport.WIRELESS && plan.phoneAddress != null) {
            hooks.selectPhone(plan.phoneAddress, plan.phoneName ?: plan.phoneAddress)
        }
        hooks.setWirelessEnabled(plan.transport == Transport.WIRELESS)
        if (plan.transport == Transport.WIRELESS) hooks.holdWifi(true)
        if (plan.transport == Transport.WIRELESS && plan.path == WirelessPath.WIFI_DIRECT) {
            linkWatch.reset()
            watching = true
            hooks.watchClients(clientsListener)
            startAgainWanted.set(false)
            hooks.setStartSessionAgain { startAgainWanted.getAndSet(false) }
            main.postDelayed(linkPoll, CarPlayLinkWatch.POLL_MILLIS)
        }
        deliver(listOf(tracker.started()))
        hooks.openHost(plan.usbAttach)?.let { error ->
            Log.w(TAG, "could not open CarPlay: ${error.javaClass.simpleName}")
            deliver(listOf(AdapterEvent.Failed(FailureReason.ENGINE, error.message)))
            this.listener = null
            detach()
        }
    }

    override fun stop(onStopped: () -> Unit) {
        listener = null
        Log.i(TAG, "stop requested hasSession=${hooks.hasSession()}")
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(why: String) {
            if (!finished.compareAndSet(false, true)) return
            Log.i(TAG, why)
            if (listener == null) detach()
            onStopped()
        }
        val timeout = Runnable {
            com.carlink.core.diagnostics.EventLog.add("CarLink", "CarPlay stop did not finish in ${STOP_TIMEOUT_MILLIS / 1000} s; going on")
            finish("stop timed out")
        }
        main.postDelayed(timeout, STOP_TIMEOUT_MILLIS)
        hooks.stopSession {
            main.post {
                main.removeCallbacks(timeout)
                finish("stopped")
            }
        }
    }

    override fun hasSession(): Boolean = hooks.hasSession()

    private fun onStatus(status: CarPlayStatus?) {
        val tracker = tracker ?: return
        if (listener == null) return
        if (status is CarPlayStatus.HotspotReady) logApIpv6(status)
        deliver(tracker.onStatus(status))
    }

    private fun logApIpv6(status: CarPlayStatus.HotspotReady) {
        if (status.backend != WirelessHotspotBackend.MANUAL_HOTSPOT.label &&
            status.backend != WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT.label) return
        Thread({
            val iface = runCatching {
                java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(status.address))?.name
            }.getOrNull() ?: return@Thread
            com.carlink.core.diagnostics.EventLog.add("CarLink", "car hotspot ${InterfaceIpv6.describe(iface)}")
        }, "carlink-ap-ipv6").apply { isDaemon = true }.start()
    }

    private fun onMilestone(phase: WirelessStartupTimeline.Phase) {
        val tracker = tracker ?: return
        if (listener == null) return
        if (watching) linkWatch.onMilestone(phase, SystemClock.uptimeMillis())
        deliver(tracker.onMilestone(phase))
    }

    private fun onHostState(state: HostState) {
        val tracker = tracker ?: return
        if (listener == null) return
        Log.i(TAG, "host state $state")
        when (state) {
            is HostState.Retrying -> linkWatch.reset()
            HostState.FirstFrame -> { linkWatch.onConnected(); hooks.setStartSessionAgain(null) }
            else -> Unit
        }
        deliver(tracker.onHostState(state))
    }

    private fun onLinkVerdict(verdict: CarPlayLinkWatch.Verdict) {
        val tracker = tracker ?: return
        if (listener == null) return
        Log.i(TAG, "link watch $verdict")
        deliver(when (verdict) {
            CarPlayLinkWatch.Verdict.NO_START_REQUEST -> listOf(AdapterEvent.Stalled(FailureReason.PHONE_NEVER_REACHED_CAR,
                "the iPhone took the Wi-Fi details but did not start CarPlay"))
            CarPlayLinkWatch.Verdict.NO_TCP -> listOf(AdapterEvent.Stalled(FailureReason.PHONE_NEVER_REACHED_CAR,
                "the iPhone joined the car's Wi-Fi but did not open CarPlay"))
            CarPlayLinkWatch.Verdict.NOT_JOINED -> tracker.fail(FailureReason.PHONE_WIFI_OFF,
                "the iPhone did not join the car's Wi-Fi: turn its Wi-Fi on in Settings")
            CarPlayLinkWatch.Verdict.PHONE_LEFT -> if (plan?.phoneAddress?.let(hooks::phoneLinked) == true) {
                listOf(AdapterEvent.Stalled(FailureReason.LINK_TOO_WEAK, "the iPhone left the car's Wi-Fi"))
            } else tracker.phoneLeft()
        })
    }

    private fun deliver(events: List<AdapterEvent>) {
        for (event in events) {
            Log.i(TAG, "event $event")
            if (event is AdapterEvent.Failed) onFailure(event.detail)
            listener?.invoke(event)
        }
        if (tracker?.finished == true) {
            listener = null
            detach()
        }
    }

    private fun detach() {
        if (watching) {
            watching = false
            main.removeCallbacks(linkPoll)
            hooks.watchClients(null)
            hooks.setStartSessionAgain(null)
        }
        hooks.holdWifi(false)
        hooks.removeStatusListener(statusListener)
        hooks.removeHostStateListener(hostStateListener)
        hooks.setMilestoneListener(null)
    }

    companion object {
        private const val TAG = "CarLink-CarPlay"
        private const val STOP_TIMEOUT_MILLIS = 20_000L
        private const val INITIAL_CAR_NAME = "OpenLink"

        internal fun resolveAccessoryName(context: Context): String {
            return CarLinkCore.resolveConfiguredName(context, INITIAL_CAR_NAME)
        }

        internal fun syncAccessoryName(context: Context): String {
            val resolved = resolveAccessoryName(context)
            CarPlayHostIntegration.accessoryName = resolved
            return resolved
        }

        fun install(context: Context, profile: VehicleProfile) {
            val app = context.applicationContext
            syncAccessoryName(app)
            CarPlayHostIntegration.resetDebugLogsOnBootstrap = false
            CarPlayHostIntegration.wifiP2pStableIdentity = true
            val marks = app.getSharedPreferences("carlink_migrations", Context.MODE_PRIVATE)
            if (!marks.getBoolean("forget_ipv6_guess", false)) {
                app.getSharedPreferences("diplay_wireless", Context.MODE_PRIVATE).edit().remove("hotspot_address_family").apply()
                marks.edit().putBoolean("forget_ipv6_guess", true).apply()
            }
            CarPlayHostIntegration.vehicleIntegrations = profile == VehicleProfile.BYD_DILINK
            CarPlayHostIntegration.onHostState = HostStates::dispatch
            com.shilapi.xcertplay.orchestration.CarPlayController.usbAttachAgeMillis =
                { com.carlink.core.detect.UsbAttachTimes.ageMillis(it.deviceName) }
            com.shilapi.xcertplay.orchestration.CarPlayController.resetWiredUsb = { UsbReset.reset(app, it) }
            val resume = ResumeMusic(app.getSharedPreferences("carlink_resume_music", Context.MODE_PRIVATE), "carplay_playing")
            com.shilapi.xcertplay.orchestration.CarPlayController.onPhonePlaying = { playing ->
                if (playing == null) resume.onSessionEnded() else resume.onPhonePlaying(playing)
            }
            HostStates.add { state ->
                if (state == HostState.FirstFrame) {
                    resume.scheduleAfterFirstPicture(CarPlaySettingsPolicy.resumeMusic, CarPlayHostIntegration::pressPlay, TAG)
                }
            }
            WirelessStartupTimeline.beforeBluetoothDial = BluetoothWarmup::awaitPage
            CarPlayWaitingView.install()
            com.shilapi.xcertplay.CarPlayRotation.setEnabled(app, com.carlink.core.detect.HeadUnitDetector.handheld(app))
            if (profile == VehicleProfile.BYD_DILINK) runCatching { BydNavigationOutputs.onAppOpened(app) }
        }
    }
}

interface CarPlayEngineHooks {
    fun hasSession(): Boolean
    fun stopSession(completion: () -> Unit)
    fun addStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun removeStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun addHostStateListener(listener: (HostState) -> Unit)
    fun removeHostStateListener(listener: (HostState) -> Unit)
    fun selectPhone(address: String, name: String)
    fun setWirelessEnabled(enabled: Boolean)
    fun openHost(usbAttach: Intent?): Throwable?
    fun setMilestoneListener(listener: ((WirelessStartupTimeline.Phase) -> Unit)?) {}
    fun setStartSessionAgain(hook: (() -> Boolean)?) {}
    fun watchClients(listener: ((Int, Int) -> Unit)?) {}
    fun phoneLinked(address: String): Boolean = false
    fun holdWifi(on: Boolean) {}
}

object HostStates {
    private val listeners = CopyOnWriteArrayList<(HostState) -> Unit>()

    fun add(listener: (HostState) -> Unit) { listeners.addIfAbsent(listener) }
    fun remove(listener: (HostState) -> Unit) { listeners.remove(listener) }

    internal fun dispatch(state: HostState) = listeners.forEach { it(state) }
}

internal class DiPlayEngineHooks(private val app: Context) : CarPlayEngineHooks {
    override fun hasSession(): Boolean = CarPlayHostIntegration.hasSession()

    @Volatile private var pendingOpen: Application.ActivityLifecycleCallbacks? = null

    private fun cancelPendingOpen() {
        pendingOpen?.let { (app as Application).unregisterActivityLifecycleCallbacks(it) }
        pendingOpen = null
    }

    override fun stopSession(completion: () -> Unit) {
        cancelPendingOpen()
        val main = Handler(Looper.getMainLooper())
        if (Looper.myLooper() == Looper.getMainLooper()) CarPlayHostIntegration.stopSession(completion)
        else main.post { CarPlayHostIntegration.stopSession(completion) }
    }

    override fun addStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.addListener(listener)
    override fun removeStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.removeListener(listener)
    override fun addHostStateListener(listener: (HostState) -> Unit) = HostStates.add(listener)
    override fun removeHostStateListener(listener: (HostState) -> Unit) = HostStates.remove(listener)

    override fun selectPhone(address: String, name: String) = CarPlayHostIntegration.selectPhone(app, address, name)

    override fun setWirelessEnabled(enabled: Boolean) = AirPlayPersistence.saveWirelessEnabled(app, enabled)

    override fun setMilestoneListener(listener: ((WirelessStartupTimeline.Phase) -> Unit)?) {
        WirelessStartupTimeline.milestoneListener = listener
    }

    override fun setStartSessionAgain(hook: (() -> Boolean)?) {
        WirelessStartupTimeline.startSessionAgain = hook
    }

    private var clientsReceiver: BroadcastReceiver? = null

    override fun watchClients(listener: ((Int, Int) -> Unit)?) {
        clientsReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        clientsReceiver = null
        if (listener == null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val group = runCatching { intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP) }
                    .getOrNull()?.takeIf { it.isGroupOwner } ?: return
                val clients = group.clientList
                val leased = if (Build.VERSION.SDK_INT >= 35) clients.count { it.ipAddress != null } else 0
                listener(clients.size, leased)
            }
        }
        clientsReceiver = receiver
        ContextCompat.registerReceiver(app, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun phoneLinked(address: String): Boolean = BluetoothWarmup.linked(app, address)

    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        app.getSystemService(WifiManager::class.java)?.createWifiLock(mode, "CarLink:CarPlay")?.apply { setReferenceCounted(false) }
    }

    override fun holdWifi(on: Boolean) {
        runCatching { if (on) wifiLock?.acquire() else if (wifiLock?.isHeld == true) wifiLock?.release() }
            .onFailure { Log.w("CarLink-CarPlay", "Wi-Fi lock: ${it.javaClass.simpleName}") }
    }

    override fun openHost(usbAttach: Intent?): Throwable? {
        com.shilapi.xcertplay.CarPlayRotation.setEnabled(app, com.carlink.core.detect.HeadUnitDetector.handheld(app))
        val host = Intent(app, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        usbAttach?.let { host.action = it.action; it.extras?.let(host::putExtras) }
        cancelPendingOpen()
        if (!inFront()) {
            Log.i("CarLink-CarPlay", "CarLink not in front yet; opening CarPlay when it resumes")
            val application = app as Application
            pendingOpen = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    application.unregisterActivityLifecycleCallbacks(this)
                    if (pendingOpen === this) pendingOpen = null
                    runCatching { activity.startActivity(host) }
                        .onFailure { Log.w("CarLink-CarPlay", "deferred CarPlay open failed: ${it.javaClass.simpleName}") }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }.also { application.registerActivityLifecycleCallbacks(it) }
            return null
        }
        return runCatching { app.startActivity(host) }.exceptionOrNull()
    }

    private fun inFront(): Boolean = Build.VERSION.SDK_INT < 29 ||
        app.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
}
