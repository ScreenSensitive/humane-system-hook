package com.penumbraos.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands-free follow-ups (ironman). When the Pin asks a compose follow-up question
 * ("Send it?", "What would you like to say?"), re-open the mic automatically so the
 * user can say "yes / send / no / edit / <body>" WITHOUT touch-and-holding again.
 * Gated behind the `auto_listen` flag (/sdcard/aipin_voice_config.json, default OFF).
 *
 * ─────────────────────────────── How it works ───────────────────────────────
 * A touch-and-hold ultimately calls VoiceManager.understandRouteVoiceAction(
 * TaoEventRegistrar, runId, VisionRequested) — it opens the mic, transcribes, and
 * routes the (endpointed) transcript through the NLU cascade. We replay exactly that
 * call to re-arm, using instances captured from the user's real interactions.
 *
 * CRITICAL scoping: we re-arm ONLY inside a "compose window". [VoiceComposeHooks]
 * opens it ([armForCompose]) the moment it sees a compose command/prompt and closes
 * it ([disarmCompose]) on send/cancel; Arbitrator.clearInteractiveSession() also
 * closes it. An earlier version re-armed after EVERY narration in any active session
 * (which is almost always), so the mic kept opening and grabbed unrelated speech that
 * then fell to the LLM. Now it only fires while a compose dialogue is genuinely live.
 *
 * Everything is wrapped so a reflection miss only disables auto-listen — it never
 * destabilizes ironman. When the toggle is off / outside a compose window it no-ops.
 */
object AutoListenHooks {
    private const val TAG = "PenumbraHook"
    private const val COMPOSE_WINDOW_MS = 45_000L   // a compose dialogue is short-lived
    private const val REARM_COOLDOWN_MS = 2_500L
    private const val IDLE_SETTLE_MS = 350L
    private const val MAX_WAIT_ATTEMPTS = 40        // ~14s ceiling waiting for silence

    private const val LISTEN_WINDOW_MS = 2500L      // "release" the mic this long after re-arm (snappy; short confirms finish well within)

    @Volatile private var voiceManager: Any? = null
    @Volatile private var taoRegistrar: Any? = null
    @Volatile private var understandRoute: Method? = null
    @Volatile private var stopTranscription: Method? = null
    @Volatile private var visionUnspecified: Any? = null
    @Volatile private var narrator: Any? = null
    @Volatile private var isNarrating: Method? = null

    // The compose dialogue window — set by VoiceComposeHooks, the only thing that
    // knows a compose follow-up is live. Re-arm fires only while now < this.
    @Volatile private var composeWindowUntil = 0L
    @Volatile private var lastRearmAt = 0L

    private val rearmGen = AtomicLong(0)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun composeActive() = System.currentTimeMillis() < composeWindowUntil

    /** Called by VoiceComposeHooks when a compose command/prompt is in play. */
    fun armForCompose() { composeWindowUntil = System.currentTimeMillis() + COMPOSE_WINDOW_MS }

    /** Called by VoiceComposeHooks on send/cancel — the dialogue is over. */
    fun disarmCompose() { composeWindowUntil = 0L; rearmGen.incrementAndGet() }

    fun install(cl: ClassLoader) {
        try {
            captureVoiceManager(cl)
            trackSessionEnd(cl)
            hookNarratorIdle(cl)
            Log.w(TAG, "  AutoListenHooks installed (compose follow-ups, default off)")
        } catch (t: Throwable) {
            Log.e(TAG, "  AutoListenHooks install failed: ${t.message}")
        }
    }

    /** Capture the live VoiceManager (`this`), TaoEventRegistrar (arg0), the method, and UNSPECIFIED. */
    private fun captureVoiceManager(cl: ClassLoader) {
        try {
            val vmCls = cl.loadClass("humaneinternal.system.voice.client.VoiceManager")
            val m = vmCls.declaredMethods.firstOrNull {
                it.name == "understandRouteVoiceAction" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == String::class.java
            } ?: run { Log.e(TAG, "  AutoListen: understandRouteVoiceAction(3) not found"); return }
            m.isAccessible = true
            stopTranscription = vmCls.declaredMethods.firstOrNull {
                it.name == "stopTranscription" && it.parameterTypes.isEmpty()
            }?.also { it.isAccessible = true }
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    voiceManager = param.thisObject
                    taoRegistrar = param.args.getOrNull(0)
                    understandRoute = m
                    if (visionUnspecified == null) runCatching {
                        @Suppress("UNCHECKED_CAST")
                        visionUnspecified = java.lang.Enum.valueOf(
                            m.parameterTypes[2] as Class<out Enum<*>>, "UNSPECIFIED")
                    }
                }
            })
            Log.w(TAG, "  AutoListen: capturing VoiceManager via understandRouteVoiceAction")
        } catch (t: Throwable) {
            Log.e(TAG, "  AutoListen captureVoiceManager failed: ${t.message}")
        }
    }

    /** Close the compose window when the interactive session ends. */
    private fun trackSessionEnd(cl: ClassLoader) {
        try {
            val arb = cl.loadClass("humaneinternal.system.tao.Arbitrator")
            arb.getDeclaredMethod("clearInteractiveSession").let {
                it.isAccessible = true
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { composeWindowUntil = 0L }
                })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  AutoListen trackSessionEnd failed: ${t.message}")
        }
    }

    /**
     * Each narration enqueue schedules a one-shot idle-wait; when the Narrator goes
     * quiet AND we're inside a compose window, re-arm. Capturing the NarratorImpl here
     * also gives us isNarrating() for the idle check.
     */
    private fun hookNarratorIdle(cl: ClassLoader) {
        try {
            val narratorCls = cl.loadClass("humaneinternal.system.narrator.NarratorImpl")
            isNarrating = narratorCls.getDeclaredMethod("isNarrating").also { it.isAccessible = true }
            val reqCls = cl.loadClass("humaneinternal.system.narrator.NarratorRequest")
            val enqueue = narratorCls.getDeclaredMethod("enqueueNarration", reqCls)
            enqueue.isAccessible = true
            XposedBridge.hookMethod(enqueue, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    narrator = param.thisObject
                    if (!VoiceConfig.autoListen() || !composeActive()) return
                    scheduleRearmWhenIdle()
                }
            })
            Log.w(TAG, "  AutoListen: watching NarratorImpl for compose prompts")
        } catch (t: Throwable) {
            Log.e(TAG, "  AutoListen hookNarratorIdle failed: ${t.message}")
        }
    }

    private fun scheduleRearmWhenIdle() {
        val gen = rearmGen.incrementAndGet()
        scheduler.schedule({ waitIdleThenRearm(gen, 0) }, 400, TimeUnit.MILLISECONDS)
    }

    private fun waitIdleThenRearm(gen: Long, attempt: Int) {
        if (gen != rearmGen.get()) return
        if (attempt >= MAX_WAIT_ATTEMPTS) return
        val narrating = runCatching { isNarrating?.invoke(narrator) as? Boolean ?: false }.getOrDefault(false)
        if (narrating) {
            scheduler.schedule({ waitIdleThenRearm(gen, attempt + 1) }, IDLE_SETTLE_MS, TimeUnit.MILLISECONDS)
            return
        }
        if (!VoiceConfig.autoListen() || !composeActive()) return
        val now = System.currentTimeMillis()
        if (now - lastRearmAt < REARM_COOLDOWN_MS) return
        lastRearmAt = now
        main.post { rearm() }
    }

    /** Replicate the touchpad's understandRouteVoiceAction call to open the mic. */
    private fun rearm() {
        val vm = voiceManager; val reg = taoRegistrar; val m = understandRoute; val vr = visionUnspecified
        if (vm == null || reg == null || m == null || vr == null) {
            Log.w(TAG, "  AutoListen: not armed yet (no captured VoiceManager) — skip re-arm"); return
        }
        try {
            m.invoke(vm, reg, UUID.randomUUID().toString(), vr)
            Log.w(TAG, "  AutoListen: re-armed mic for compose follow-up")
            // "Release" the mic after a short window so the utterance commits promptly,
            // mirroring the touchpad (press-hold-speak-RELEASE -> stopTranscription). Without
            // this the firmware waited ~13s to endpoint, so the user repeated "yes" and it
            // arrived as "yes yes yes yes". stopTranscription forces a clean, quick finalize.
            stopTranscription?.let { stop ->
                main.postDelayed({
                    runCatching { stop.invoke(vm) }
                        .onFailure { Log.e(TAG, "  AutoListen stop failed: ${it.message}") }
                    Log.w(TAG, "  AutoListen: released mic (stopTranscription)")
                }, LISTEN_WINDOW_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  AutoListen re-arm failed: ${t.message}")
        }
    }
}
