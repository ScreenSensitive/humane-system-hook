package com.penumbraos.hook

import android.telecom.Call
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections

/**
 * Hooks for the dialer experience (package: humane.experience.dialer).
 *
 * Native incoming-caller announcement ("Call from <name>").
 *
 * Hooking `InCallServiceImpl.onCallAdded` alone proved unreliable (a framework
 * override; AliuHook reported "hooked" but the callback never fired on a real
 * ringing call). So we instrument THREE entry points on the incoming-call path —
 * the telecom override plus two regular CallManager methods it calls
 * (onCallAdded -> handleCallInitialization) — log which actually fires, and
 * announce from whichever does (deduped per call). We announce when the call is
 * RINGING (checked now and via a Call.Callback for the transition).
 *
 * No persisted bridge file, so an old call can't be replayed (the Frida bug).
 * Caller name = telecom-resolved Call.Details.getContactDisplayName(), else number.
 * Speaks via a captured NarratorAccess (serialized Narrator queue).
 */
object DialerHooks {
    private const val TAG = "PenumbraHook"

    @Volatile private var narratorAccess: Any? = null
    private val announced = Collections.synchronizedSet(HashSet<Int>())

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing dialer hooks...")
        captureNarratorAccess(cl)
        // Telecom override (may be un-hookable) — try it.
        hookCallEntry(cl, "humane.experience.dialer.InCallServiceImpl", "onCallAdded",
            arrayOf(Call::class.java), "InCall.onCallAdded")
        // Regular CallManager methods on the same path — reliably hookable.
        hookCallEntry(cl, "humaneinternal.system.telephony.CallManager", "onCallAdded",
            arrayOf(Call::class.java), "CallManager.onCallAdded")
        hookCallEntry(cl, "humaneinternal.system.telephony.CallManager", "handleCallInitialization",
            arrayOf(Call::class.java), "CallManager.handleCallInitialization")
        // Most reliable signal: TelephonyCallStateListener.onCallStateChanged(int) fires
        // on RINGING (state 1) — it drives the incoming-call LED, so we know it runs and
        // is a regular method (hookable, unlike the framework-override onCallAdded).
        hookCallStateListener(cl)
        Log.w(TAG, "Dialer hooks installed")
    }

    private fun captureNarratorAccess(cl: ClassLoader) {
        try {
            val na = cl.loadClass("humane.system.NarratorAccess")
            XposedBridge.hookAllConstructors(na, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (narratorAccess == null) {
                        narratorAccess = param.thisObject
                        Log.w(TAG, "  Captured NarratorAccess for caller announce")
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "  NarratorAccess capture failed: ${t.message}")
        }
    }

    private fun hookCallEntry(cl: ClassLoader, cls: String, method: String, params: Array<Class<*>>, label: String) {
        try {
            val c = cl.loadClass(cls)
            val m = c.getDeclaredMethod(method, *params)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val call = param.args.getOrNull(0) as? Call ?: return
                        val state = call.details?.state ?: -1
                        Log.w(TAG, "[$label] fired: state=$state (RINGING=${Call.STATE_RINGING})")
                        maybeAnnounce(call)
                        call.registerCallback(object : Call.Callback() {
                            override fun onStateChanged(c2: Call, newState: Int) {
                                Log.w(TAG, "  [$label] onStateChanged: $newState")
                                if (newState == Call.STATE_RINGING) maybeAnnounce(c2)
                                if (newState == Call.STATE_DISCONNECTED) {
                                    announced.remove(System.identityHashCode(c2))
                                    runCatching { c2.unregisterCallback(this) }
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        Log.e(TAG, "  [$label] hook body failed: ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  Hooked $label for caller announce")
        } catch (t: Throwable) {
            Log.e(TAG, "  hook $label failed: ${t.message}")
        }
    }

    /**
     * Hook the telephony call-state listener (fires on RINGING, regular method). On
     * RINGING, find the ringing Call via CallManager's InCallService and announce.
     */
    private fun hookCallStateListener(cl: ClassLoader) {
        try {
            val cls = cl.loadClass("humane.experience.dialer.util.TelephonyCallStateListener")
            val m = cls.getDeclaredMethod("onCallStateChanged", Integer.TYPE)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = (param.args.getOrNull(0) as? Int) ?: return
                        if (state != 1) return   // 1 == CALL_STATE_RINGING
                        Log.w(TAG, "[CallStateListener] RINGING")
                        if (!VoiceConfig.announceCaller()) { Log.w(TAG, "  announce_caller off"); return }
                        val call = ringingCall(cl)
                        val id = if (call != null) System.identityHashCode(call) else -1
                        if (id != -1 && !announced.add(id)) return
                        val name = call?.details?.contactDisplayName?.takeIf { it.isNotBlank() }
                            ?: spokenNumber(call?.details?.handle?.schemeSpecificPart)
                            ?: "an unknown number"
                        Log.w(TAG, "  announcing (state-listener): Call from $name")
                        speak("Call from $name")
                    } catch (t: Throwable) {
                        Log.e(TAG, "  CallStateListener hook failed: ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  Hooked TelephonyCallStateListener.onCallStateChanged")
        } catch (t: Throwable) {
            Log.e(TAG, "  hookCallStateListener failed: ${t.message}")
        }
    }

    /** The current RINGING call, via CallManager.getInstance().mInCallService.getCalls(). */
    private fun ringingCall(cl: ClassLoader): Call? {
        return try {
            val cmCls = cl.loadClass("humaneinternal.system.telephony.CallManager")
            val cm = cmCls.getMethod("getInstance").invoke(null) ?: return null
            val field = cmCls.getDeclaredField("mInCallService").apply { isAccessible = true }
            val incall = field.get(cm) as? android.telecom.InCallService ?: return null
            incall.calls.firstOrNull { it.details?.state == Call.STATE_RINGING } ?: incall.calls.firstOrNull()
        } catch (t: Throwable) {
            Log.e(TAG, "  ringingCall lookup failed: ${t.message}")
            null
        }
    }

    private fun maybeAnnounce(call: Call) {
        try {
            if (!VoiceConfig.announceCaller()) { Log.w(TAG, "  announce_caller flag off, skipping"); return }
            val details = call.details ?: return
            if (details.state != Call.STATE_RINGING) return
            val id = System.identityHashCode(call)
            if (!announced.add(id)) return

            val name = details.contactDisplayName?.takeIf { it.isNotBlank() }
                ?: spokenNumber(details.handle?.schemeSpecificPart)
                ?: "an unknown number"
            Log.w(TAG, "  announcing: Call from $name")
            speak("Call from $name")
        } catch (t: Throwable) {
            Log.e(TAG, "  maybeAnnounce failed: ${t.message}")
        }
    }

    /** "+14255551234" -> "4 2 5 5 5 5 1 2 3 4" so TTS reads digit-by-digit. */
    private fun spokenNumber(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() }?.removePrefix("1") ?: return null
        if (digits.length != 10) return raw
        return digits.toCharArray().joinToString(" ")
    }

    /**
     * The dialer's NarratorAccess is silent (proven), so we DETECT here but SPEAK from
     * ironman: broadcast the resolved text to ironman, which narrates via NarratorImpl
     * (the audible path text-announce uses). Mirrors the OG dialer->ironman split.
     */
    private fun speak(text: String) {
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context ?: return
            ctx.sendBroadcast(
                android.content.Intent("com.penumbraos.hook.ANNOUNCE_CALLER")
                    .setPackage("hu.ma.ne.ironman")
                    .putExtra("text", text)
            )
            Log.w(TAG, "  caller -> broadcast to ironman: \"$text\"")
        } catch (t: Throwable) {
            Log.e(TAG, "  caller broadcast failed: ${t.message}")
        }
    }

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
