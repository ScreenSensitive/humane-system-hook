package com.penumbraos.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections

/**
 * Native incoming-text announcement for ironman ("Text from <sender>").
 *
 * Event-driven: hooks NotificationManager.insert(NotificationEntity), which fires
 * only when a genuinely NEW notification row is written. This avoids the Frida
 * bug where a stale /sdcard bridge file replayed an OLD text/call on reboot —
 * there is no persisted file here, and we additionally ignore any row whose
 * timestamp predates module init (startup guard) plus dedupe by UUID.
 *
 * Speaks via NarratorImpl.enqueueNarration, whose LinkedBlockingQueue + single
 * executor naturally serialize announcements: several texts arriving at once are
 * spoken ONE AT A TIME in order, never blurted together and never collapsed to one.
 */
object VoiceAnnounceHooks {
    private const val TAG = "PenumbraHook"

    private val startupMs = System.currentTimeMillis()
    private val announced = Collections.synchronizedSet(HashSet<String>())
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var narrator: Any? = null          // captured NarratorImpl
    @Volatile private var requestCls: Class<*>? = null    // NarratorRequest

    fun install(cl: ClassLoader) {
        try {
            requestCls = cl.loadClass("humaneinternal.system.narrator.NarratorRequest")

            // Capture a live NarratorImpl instance so we can enqueue our own narrations.
            val narratorImpl = cl.loadClass("humaneinternal.system.narrator.NarratorImpl")
            val enqueue = findMethod(narratorImpl, "enqueueNarration", 1)
            if (enqueue != null) {
                enqueue.isAccessible = true
                XposedBridge.hookMethod(enqueue, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (narrator == null) narrator = param.thisObject
                    }
                })
            }

            // Announce on each new inbound message notification.
            val nmCls = cl.loadClass("humaneinternal.system.notifications.NotificationManager")
            val entityCls = cl.loadClass("humaneinternal.system.notifications.room.NotificationEntity")
            val insert = nmCls.getDeclaredMethod("insert", entityCls)
            insert.isAccessible = true
            XposedBridge.hookMethod(insert, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onInsert(param.args.getOrNull(0) ?: return)
                    } catch (t: Throwable) {
                        Log.e(TAG, "announce-text onInsert failed (non-fatal): ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  VoiceAnnounceHooks installed (incoming-text announce)")

            // Incoming-CALL announce ("Call from <name>"). DETECTION happens in the dialer
            // (its TelephonyCallStateListener.onCallStateChanged reliably fires on RINGING
            // and resolves the contact name) — but the dialer's narrator is SILENT. So the
            // dialer broadcasts the resolved text to us, and we speak it here via the same
            // ironman NarratorImpl that text-announce uses (the only audible path).
            // (TelephonyManager.listen in ironman never fired on this firmware — Android 12
            // dialers use TelephonyCallback, not PhoneStateListener.listen.)
            registerCallerReceiver()
        } catch (t: Throwable) {
            Log.e(TAG, "  VoiceAnnounceHooks install failed: ${t.message}")
        }
    }

    @Volatile private var callerReceiverRegistered = false
    @Volatile private var callerRegisterAttempts = 0
    @Volatile private var lastCallerText: String? = null
    @Volatile private var lastCallerAt = 0L

    /**
     * Listen for the dialer's ANNOUNCE_CALLER broadcast and speak the resolved text via
     * the ironman narrator. The dialer (DialerHooks) does the detection + name resolution;
     * we just narrate, because only the ironman NarratorImpl actually produces audio.
     *
     * install() runs at instantiateApplication time — BEFORE the app has a Context — so
     * currentApplication() is null then and the receiver would silently never register
     * (the bug that made caller-announce do nothing while text-announce, a method hook,
     * worked). Retry until onCreate has run and a Context exists, then register.
     */
    private fun registerCallerReceiver() {
        if (callerReceiverRegistered) return
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx == null) {
                if (callerRegisterAttempts++ < 30) {
                    main.postDelayed({ registerCallerReceiver() }, 2000)
                } else {
                    Log.e(TAG, "  caller receiver: gave up waiting for app context")
                }
                return
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    if (!VoiceConfig.announceCaller()) return
                    val text = intent?.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
                    val now = System.currentTimeMillis()
                    // The dialer has two detection paths; dedupe identical text within 6s.
                    if (text == lastCallerText && now - lastCallerAt < 6000) return
                    lastCallerText = text; lastCallerAt = now
                    Log.w(TAG, "  caller-announce (from dialer): $text")
                    speak(text)
                }
            }
            ctx.registerReceiver(receiver, android.content.IntentFilter("com.penumbraos.hook.ANNOUNCE_CALLER"))
            callerReceiverRegistered = true
            Log.w(TAG, "  Registered ANNOUNCE_CALLER receiver (speaks via ironman narrator)")
        } catch (t: Throwable) {
            Log.e(TAG, "  registerCallerReceiver failed: ${t.message}")
        }
    }

    private fun onInsert(entity: Any) {
        if (!VoiceConfig.announceText()) return
        val expId = strGetter(entity, "experienceIdentifier") ?: return
        if (!expId.contains("messages")) return
        if (boolGetter(entity, "isSentBySelf")) return
        // Startup guard: never announce a notification older than module init.
        val ts = longGetter(entity, "timestamp")
        if (ts in 1 until startupMs) return
        // Dedupe (insert can be called for updates). uuid() returns a UUID, not a
        // String, so stringify it; fall back to ts+sender if it's somehow absent.
        val uuid = anyGetter(entity, "uuid")?.toString() ?: "$ts-${strGetter(entity, "sender")}"
        if (!announced.add(uuid)) return
        val sender = strGetter(entity, "sender")?.trim()?.ifEmpty { "someone" } ?: "someone"
        speak("Text from $sender")
    }

    /** Enqueue one narration; the Narrator serializes the queue (one at a time). */
    private fun speak(text: String) {
        val n = narrator ?: run { Log.w(TAG, "  no narrator captured yet, dropping: $text"); return }
        val rc = requestCls ?: return
        main.post {
            try {
                val req = rc.getMethod("createDeviceRequest", String::class.java, String::class.java)
                    .invoke(null, text, "aipin_announce")
                val enqueue = findMethod(n.javaClass, "enqueueNarration", 1) ?: return@post
                enqueue.isAccessible = true
                enqueue.invoke(n, req)
            } catch (t: Throwable) {
                Log.e(TAG, "  announce speak failed: ${t.message}")
            }
        }
    }

    // ── AutoValue getter reflection (experienceIdentifier()/sender()/timestamp()/…) ──
    private fun strGetter(obj: Any, name: String): String? =
        runCatching { findMethod(obj.javaClass, name, 0)?.invoke(obj) as? String }.getOrNull()

    private fun anyGetter(obj: Any, name: String): Any? =
        runCatching { findMethod(obj.javaClass, name, 0)?.invoke(obj) }.getOrNull()

    private fun boolGetter(obj: Any, name: String): Boolean =
        runCatching { (findMethod(obj.javaClass, name, 0)?.invoke(obj) as? Boolean) ?: false }.getOrDefault(false)

    private fun longGetter(obj: Any, name: String): Long =
        runCatching { (findMethod(obj.javaClass, name, 0)?.invoke(obj) as? Long) ?: 0L }.getOrDefault(0L)

    private fun findMethod(clazz: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
                ?.let { return it }
            c = c.superclass
        }
        return null
    }
}
