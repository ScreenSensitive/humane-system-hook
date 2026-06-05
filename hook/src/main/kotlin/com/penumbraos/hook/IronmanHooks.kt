package com.penumbraos.hook

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.security.cert.X509Certificate

/**
 * Hooks for ironman.
 *
 *
 * Ordering requirement: [hookCredentialManager] MUST run before
 * [ChannelFactoryBypass.install] — without it, ironman crash-loops
 * due to missing device certificates before any gRPC redirect fires.
 */
object IronmanHooks {

    private const val TAG = "PenumbraHook"

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing ironman hooks...")
        Log.w(TAG, "  Mock server: ${ChannelFactoryBypass.MOCK_SERVER_URI}")

        // Bootloop guard: a touchpad-gesture intent that reaches CentralService before
        // AppController is initialized NPEs (AppController.touchpadActionManager() == null)
        // and crashes ironman -> restart -> the intent redelivers -> white-LED bootloop.
        // Suppress that specific early-gesture NPE so ironman survives the startup race.
        guardGestureCrash(cl)

        // Credential hooks must be installed first — without these, ironman
        // crash-loops before ChannelFactory hooks ever get a chance to run.
        hookCredentialManager(cl)

        // Redirect all gRPC traffic to mock server
        ChannelFactoryBypass.install(cl)

        // Hook DAC signature generation as a safety net
        hookDacSignature(cl)

        // Encryption bypass: make all data protection calls return plaintext
        hookDataProtector(cl)

        // Ephemeral encryption bypass: short-circuit prepare(), make encrypt/decrypt
        // pass plaintext through EncryptedData envelopes so the mock server can
        // read encrypted RPC endpoints (weather, nearby, chat, etc.) in the clear.
        EphemeralProtectionBypass.install(cl)

        // Provisioning state fix: only force NORMAL mode and DUC_PROVISIONED=1
        // if onboarding has already completed. On a fresh device, leave these
        // alone so the onboarding UI runs naturally.
        hookApplicationOnCreate(cl)

        // Block Datadog SDK initialization (RUM, tracing, crash reports, log forwarding
        // to browser-intake-datadoghq.com)
        DatadogBypass.install(cl)

        // Block Microsoft Cognitive Services Speech SDK telemetry
        // (1DS protocol to mobile.events.data.microsoft.com)
        MsTelemetryBypass.install(cl)

        // Block Humane connectivity check phone-home
        // (HTTP GET to connectivity-check.prod.humane.cloud — cosmetic only)
        ConnectivityCheckBypass.install(cl)

        // Silence Memfault RemoteMetricsService
        hookRemoteMetricsService(cl)

        // Bypass blocking IWlcService.disableTx binder calls
        WirelessChargingBypass.install(cl)

        // Native voice compose-message confirmation ("yes"/"send it"/"send"/"confirm"
        // -> ConfirmSendMessage; "no"/"cancel" -> CancelSendMessage), intercepted at
        // the CONFIRMATION cascade stage so it never reaches the dead SYNAPSE/LLM.
        // Replaces the Frida voice_handlers confirm flow. Does NOT touch emergency-call
        // or factory-reset confirmation (see VoiceComposeHooks safety notes).
        VoiceComposeHooks.install(cl)

        // Native catch-me-up + read-message voice commands (deterministic, offline,
        // counts+who only; content on explicit "read messages [name]"). Emits a
        // NarrateAction so it never reaches the LLM.
        VoiceReadHooks.install(cl)

        // Announce incoming texts ("Text from <sender>"), event-driven on the
        // notification insert (no stale-replay), gated on the announce_text flag.
        VoiceAnnounceHooks.install(cl)

        // Add native "text <name> <body>" / "tell <name> <body>" compose recognition
        // (firmware only ships "send message to <name> saying <body>").
        ComposePatternsHook.install(cl)

        // Native "play <song>" recognition -> PlayMusic(Track=...) so it reaches the
        // music experience (and our NewPipe provider) instead of the dead cloud.
        MusicIntentHook.install(cl)

        // Hands-free follow-ups: after a compose prompt ("Send it?" / "What would you
        // like to say?") re-open the mic so "yes/send/no/edit/<body>" needs no second
        // touch-and-hold. Gated by the auto_listen flag (default off).
        AutoListenHooks.install(cl)

        // Spotify engine: runs librespot ("Ai Pin" Connect device) + a local HTTP WAV server
        // (127.0.0.1:27089) that streams the current track's PCM. The music app's ExoPlayer
        // plays that URL (MusicHooks returns it for spotify), so Spotify rides the native
        // pipeline (now-playing/queue/controls). No phone needed.
        SpotifyControl.install(cl)

        Log.w(TAG, "Ironman hooks installed")
    }

    /**
     * Patch AbstractCredentialKeyManager to survive missing device credentials.
     *
     * The device's hardware keystore (TEE) may have no provisioned cert chain
     * for the "DeviceUserCreds" alias. When this happens:
     *
     * - getCertificateChain(): HumaneCertificate.getCertificateChain() returns null,
     *   and the for-each loop over null throws NPE. This crashes newChannel() in
     *   the TLS path, AND getLeafCertificate() -> addCertHeader() in the plaintext
     *   path (called by NetworkManager.newServiceStub()).
     *
     * - getPrivateKey(): if getKeyPair() throws, the catch block sets keyPair=null,
     *   then keyPair.getPrivate() NPEs. Hit during SSLContext.init() in the TLS path.
     *
     * We suppress both NPEs so ironman can proceed to the plaintext/redirect path.
     */
    /**
     * Suppress the early-gesture NullPointerException in CentralService.dispatchGestureIntent
     * (AppController not yet initialized). The NPE otherwise escapes onStartCommand and
     * crash-loops ironman. Dropping the too-early gesture is harmless — once AppController is
     * up, gestures dispatch normally. Defensive against the startup race regardless of cause.
     */
    private fun guardGestureCrash(cl: ClassLoader) {
        try {
            val cls = cl.loadClass("humaneinternal.system.CentralService")
            val m = cls.getDeclaredMethod("dispatchGestureIntent", Intent::class.java)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable is NullPointerException) {
                        param.throwable = null
                        Log.w(TAG, "Suppressed early-gesture NPE (AppController not ready) — bootloop guard")
                    }
                }
            })
            Log.w(TAG, "  Hooked CentralService.dispatchGestureIntent (bootloop guard)")
        } catch (t: Throwable) {
            Log.e(TAG, "  guardGestureCrash failed: ${t.message}")
        }
    }

    private fun hookCredentialManager(cl: ClassLoader) {
        val className = "humaneinternal.system.credentials.AbstractCredentialKeyManager"
        val clazz = try {
            cl.loadClass(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "  $className not found, skipping credential hooks")
            return
        }

        // getCertificateChain(String) -> X509Certificate[]
        // Suppress NPE from for-each over null cert chain; return empty array
        // so callers like getLeafCertificate() get Optional.empty() instead of crashing.
        try {
            val method = clazz.getDeclaredMethod("getCertificateChain", String::class.java)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable is NullPointerException) {
                        param.throwable = null
                        param.result = emptyArray<X509Certificate>()
                        Log.w(TAG, "getCertificateChain() NPE suppressed — device cert chain missing, returning empty array")
                    }
                }
            })
            Log.w(TAG, "  Hooked $className.getCertificateChain()")
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to hook getCertificateChain: ${t.message}")
        }

        // getPrivateKey(String) -> PrivateKey
        // Suppress NPE from keyPair.getPrivate() when keyPair is null after catch;
        // return null so SSLContext silently skips client cert presentation.
        try {
            val method = clazz.getDeclaredMethod("getPrivateKey", String::class.java)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable is NullPointerException) {
                        param.throwable = null
                        param.result = null
                        Log.w(TAG, "getPrivateKey() NPE suppressed — device keypair missing, returning null")
                    }
                }
            })
            Log.w(TAG, "  Hooked $className.getPrivateKey()")
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to hook getPrivateKey: ${t.message}")
        }
    }

    /**
     * Hook Application.onCreate() to run provisioning fix once we have a Context.
     *
     * instantiateApplication() runs before attachBaseContext(), so we can't
     * access ContentResolver there. onCreate() runs after attach, giving us
     * full Context access.
     *
     * Only runs in the main process — the :voiceinteractor process doesn't
     * need provisioning fixes and shouldn't call setBaselineMode().
     */
    private fun hookApplicationOnCreate(cl: ClassLoader) {
        try {
            val appClass = cl.loadClass("humaneinternal.system.MainApplication")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            onCreateMethod.isAccessible = true
            XposedBridge.hookMethod(onCreateMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Application
                    val processName = app.applicationInfo?.processName ?: ""
                    val myProcess = Application.getProcessName() ?: ""
                    Log.w(TAG, "Application.onCreate() — process=$myProcess")

                    // Only run in main process (not :voiceinteractor)
                    if (myProcess.contains(":")) {
                        Log.w(TAG, "  Skipping provisioning fix in subprocess: $myProcess")
                        return
                    }

                    try {
                        fixProvisioningState(app, cl)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Provisioning fix failed (non-fatal)", t)
                    }
                    // Native compose recognition: rebuild RegexIntentEngine's
                    // ComposeMessage patterns with the user's contacts.
                    try {
                        scheduleContactsRefresh(app)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Contacts-refresh scheduling failed (non-fatal)", t)
                    }
                    // Boot-persist the on-demand hooks (dialer/music): the injector's
                    // boot mutation doesn't take for processes that start on-demand, so
                    // ironman (always hooked) fires the same INJECT the manual step does.
                    try {
                        scheduleHookInjects(app)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Hook-inject scheduling failed (non-fatal)", t)
                    }
                }
            })
            Log.w(TAG, "  Hooked MainApplication.onCreate() for provisioning fix")
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to hook MainApplication.onCreate(): ${t.message}")
            // Fallback: try hooking android.app.Application.onCreate() directly
            try {
                val appClass = Application::class.java
                val onCreateMethod = appClass.getDeclaredMethod("onCreate")
                XposedBridge.hookMethod(onCreateMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        val myProcess = Application.getProcessName() ?: ""
                        if (myProcess.contains(":")) return

                        try {
                            fixProvisioningState(app, cl)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Provisioning fix failed (non-fatal)", t)
                        }
                    }
                })
                Log.w(TAG, "  Hooked Application.onCreate() (fallback) for provisioning fix")
            } catch (t2: Throwable) {
                Log.e(TAG, "  Failed to hook Application.onCreate() fallback: ${t2.message}")
            }
        }
    }

    /**
     * Native compose recognition: fire NOTIFY_CONTACTS_LIST_CHANGE so ironman's
     * RegexInterpreter.ContactsChanged() re-reads the on-device contacts DB and
     * rebuilds the ComposeMessage regex WITH the user's real contact names — then
     * "send message to <contact> saying <body>" matches in the REGEX stage (no LLM).
     *
     * Contacts sync (via PenumbraOS ContactsHooks) lands asynchronously after boot,
     * and the regex engine may have compiled before they arrived. We re-fire a few
     * times so a late sync still gets picked up. This replaces the manual
     * PinFrida.refreshContacts the control app fired from the phone.
     */
    /**
     * Boot-persist the on-demand experience hooks. The injector's boot-time PMS
     * mutation doesn't reliably take for packages that start ON DEMAND (dialer when
     * a call rings, music when you say "play") — only a force-restart inject works.
     * So ironman (which IS hooked at every boot) fires the same INJECT broadcast the
     * manual step uses, for each on-demand target, a few seconds after boot. This is
     * the "bundle the inject into the hook" — no manual adb step, ever.
     *
     * InjectReceiver is exported with action com.penumbraos.hook.INJECT (verified),
     * so an explicit-component broadcast from ironman reaches it in system_server.
     */
    private fun scheduleHookInjects(app: Application) {
        val targets = listOf("humane.experience.dialer", "humane.experience.music")
        val handler = Handler(Looper.getMainLooper())
        for (delayMs in longArrayOf(12_000L, 45_000L)) {
            handler.postDelayed({
                for (pkg in targets) {
                    try {
                        val intent = Intent("com.penumbraos.hook.INJECT")
                            .setClassName(
                                "com.penumbraos.hook.injector",
                                "com.penumbraos.hook.injector.InjectReceiver"
                            )
                            .putExtra("package", pkg)
                        app.sendBroadcast(intent)
                        Log.w(TAG, "Fired INJECT for $pkg (boot-persist on-demand hook)")
                    } catch (t: Throwable) {
                        Log.e(TAG, "  INJECT broadcast for $pkg failed: ${t.message}")
                    }
                }
            }, delayMs)
        }
    }

    private fun scheduleContactsRefresh(app: Application) {
        val action = "humaneinternal.system.contacts.NOTIFY_CONTACTS_LIST_CHANGE"
        val handler = Handler(Looper.getMainLooper())
        for (delayMs in longArrayOf(20_000L, 60_000L, 150_000L)) {
            handler.postDelayed({
                try {
                    app.sendBroadcast(Intent(action))
                    Log.w(TAG, "Fired NOTIFY_CONTACTS_LIST_CHANGE (native compose recognition)")
                } catch (t: Throwable) {
                    Log.e(TAG, "  contacts broadcast failed: ${t.message}")
                }
            }, delayMs)
        }
    }

    /**
     * Fix the provisioning state iff onboarding has already completed.
     *
     * If DUC_PROVISIONED is already 1, this is a reboot after successful onboarding.
     * We ensure baseline mode stays NORMAL (recovery from any mode drift).
     *
     * If DUC_PROVISIONED is 0, onboarding has NOT completed yet. We leave
     * everything alone so the onboarding UI can run naturally.
     */
    private fun fixProvisioningState(app: Application, cl: ClassLoader) {
        val resolver = app.contentResolver
        val key = "humane.settings.global.DUC_PROVISIONED"
        val current = Settings.Global.getInt(resolver, key, 0)

        Log.w(TAG, "=== Provisioning state check: DUC_PROVISIONED=$current ===")

        if (current == 0) {
            Log.w(TAG, "  Device not yet provisioned — letting onboarding run naturally")
            return
        }

        // Already provisioned — ensure baseline mode is NORMAL after reboot
        Log.w(TAG, "  Device already provisioned — ensuring NORMAL mode")
        fixBaselineMode(cl)

        Log.w(TAG, "=== Provisioning fix complete ===")
    }

    /**
     * Call ISystemModeService.setBaselineMode(0) via reflection on the AIDL proxy.
     *
     * The AIDL-generated classes are in ironman's classloader:
     *   - ISystemModeService.Stub.asInterface(binder) returns the proxy
     *   - proxy.setBaselineMode((byte) 0) sets NORMAL mode
     *
     * setBaselineMode() in SystemModeService (system_server) will:
     *   1. Validate mode is baseline (0 or 1)
     *   2. Write to SharedPreferences: putInt("baselineMode", 0)
     *   3. If current mode != 0, call resetMode() which transitions to NORMAL
     *   4. resetMode() calls __setModeInternal() which broadcasts via IModeCallback
     *   5. TouchpadActionManager receives onModeSet() and updates mMode to 0
     */
    private fun fixBaselineMode(cl: ClassLoader) {
        try {
            // Get the SystemModeService binder
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "humane.service.SystemModeService") as? IBinder

            if (binder == null) {
                Log.w(TAG, "  SystemModeService binder not found — service may not be ready yet")
                return
            }

            // Load ISystemModeService.Stub and call asInterface()
            val stubClass = cl.loadClass("humane.sysmode.ISystemModeService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)
                ?: run {
                    Log.w(TAG, "  ISystemModeService.Stub.asInterface() returned null")
                    return
                }

            // Check current baseline mode first (idempotent)
            val getBaselineMethod = service.javaClass.getMethod("getBaselineMode")
            val currentBaseline = getBaselineMethod.invoke(service) as Byte

            Log.w(TAG, "  Current baseline mode: $currentBaseline")

            if (currentBaseline == 0.toByte()) {
                Log.w(TAG, "  Baseline already NORMAL (0), no change needed")
                return
            }

            // Set baseline to NORMAL (0)
            val setBaselineMethod = service.javaClass.getMethod("setBaselineMode", Byte::class.javaPrimitiveType)
            setBaselineMethod.invoke(service, 0.toByte())
            Log.w(TAG, "  setBaselineMode(0) — SUCCESS — mode transitioned from $currentBaseline to NORMAL")

        } catch (t: Throwable) {
            Log.e(TAG, "  setBaselineMode failed: ${t.javaClass.simpleName}: ${t.message}")
            // Non-fatal — device continues with whatever mode it had
        }
    }

    /**
     * Bypass Krypton data protection so all captured data arrives as plaintext.
     */
    private fun hookDataProtector(cl: ClassLoader) {
        DataProtectorBypass.install(cl)
    }

    /**
     * Ensure DUC_PROVISIONED is set to 1 in Settings.Global.
     *
     * This is the flag that controls:
     * - SystemUI TouchpadEventReceiver: mOnboardingComplete (enables all gestures)
     * - PersistenceService: bindCentralIfProvisioned() (starts CentralService)
     * - LaserSoundFeedbackManager: switches from PROACTIVE to SUBTLE guidance
     *
     * The ContentObserver in SystemUI fires immediately on change.
     */
    @Suppress("unused")
    private fun fixDucProvisioned(app: Application) {
        try {
            val resolver = app.contentResolver
            val key = "humane.settings.global.DUC_PROVISIONED"
            val current = Settings.Global.getInt(resolver, key, 0)

            Log.w(TAG, "  Current DUC_PROVISIONED: $current")

            if (current != 0) {
                Log.w(TAG, "  DUC_PROVISIONED already set ($current), no change needed")
                return
            }

            val success = Settings.Global.putInt(resolver, key, 1)
            if (success) {
                Log.w(TAG, "  DUC_PROVISIONED set to 1 — SUCCESS")
            } else {
                Log.w(TAG, "  DUC_PROVISIONED putInt returned false — may lack permission")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  DUC_PROVISIONED fix failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Hook DeviceAttestationManager.generateVerifierSignature() as a safety net.
     *
     * During onboarding, UserBindingManager calls dacManager.generateVerifierSignature()
     * to sign the device ID with the DAC private key. If the DAC private key is
     * missing from the HumaneKeyStore TEE, this would crash.
     *
     * We hook it to return a dummy signature if it throws. The mock server
     * ignores signatures anyway.
     */
    private fun hookDacSignature(cl: ClassLoader) {
        val className = "humaneinternal.system.credentials.DeviceAttestationManager"
        val clazz = try {
            cl.loadClass(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "  $className not found, skipping DAC signature hook")
            return
        }

        // generateVerifierSignature(byte[]) -> byte[]
        // It calls CryptoUtils.generateSignature(payload, dacPrivateKey) internally
        try {
            val method = clazz.getDeclaredMethod("generateVerifierSignature", ByteArray::class.java)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) {
                        // DAC private key missing — return a dummy ECDSA-like signature
                        param.throwable = null
                        param.result = ByteArray(64) { 0x01 }
                        Log.w(TAG, "  DAC generateVerifierSignature() threw ${param.throwable?.javaClass?.simpleName} — returning dummy signature")
                    } else {
                        Log.w(TAG, "  DAC generateVerifierSignature() succeeded with real signature")
                    }
                }
            })
            Log.w(TAG, "  Hooked $className.generateVerifierSignature() (safety net)")
        } catch (t: Throwable) {
            Log.e(TAG, "  Failed to hook generateVerifierSignature: ${t.message}")
        }
    }

    /**
     * Silence Memfault's embedded RemoteMetricsService.
     *
     * The Memfault reporting library is compiled into ironman (not in the Bort APK).
     * It records metrics via record$reporting_lib_release() and periodically
     * finishes reports via finishReport$reporting_lib_release(). Both call
     * withRemoteLogger() which looks up the "memfault_structured" binder service.
     * With the daemon stopped, every call logs:
     *   "Unable to get a handle to memfault_structured"
     *
     * Hook both entry points to no-op, eliminating the binder lookup and log noise.
     */
    private fun hookRemoteMetricsService(cl: ClassLoader) {
        val className = "com.memfault.bort.reporting.RemoteMetricsService"
        val clazz = try {
            cl.loadClass(className)
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "  $className not found, skipping RemoteMetrics hook")
            return
        }

        // record$reporting_lib_release(MetricValue) -> void
        try {
            val metricValueClass = cl.loadClass("com.memfault.bort.reporting.MetricValue")
            HookUtils.hookMethodBefore(clazz, "record\$reporting_lib_release", arrayOf(metricValueClass)) { param ->
                param.result = null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to hook RemoteMetricsService.record: ${t.message}")
        }

        // finishReport$reporting_lib_release(String, long, boolean) -> boolean
        try {
            HookUtils.hookMethodBefore(
                clazz,
                "finishReport\$reporting_lib_release",
                arrayOf(String::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            ) { param ->
                param.result = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to hook RemoteMetricsService.finishReport: ${t.message}")
        }

        Log.w(TAG, "  RemoteMetricsService hooks installed (metrics silenced)")
    }

}
