package com.penumbraos.hook

import android.content.Intent
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * Native compose-message confirmation for ironman — the permanent replacement
 * for the Frida `voice_handlers.js` "yes" / "send it" flow.
 *
 * The intent cascade (InterpreterOrchestrator.interpret) runs, first non-null
 * result wins:  REGEX -> SEMANTIC -> CONFIRMATION -> SYNAPSE(dead LLM) -> FALLBACK.
 * A compose draft awaiting confirmation makes the orchestrator skip REGEX/SEMANTIC
 * (the action is "contextual"), so the confirm utterance lands on CONFIRMATION and
 * would otherwise fall through to the dead SYNAPSE/LLM. We intercept at CONFIRMATION
 * and emit the same ConfirmSendMessage/CancelSendMessage action the cloud once did,
 * which routes natively to humane.experience.messages and sends. No LLM, no Frida.
 *
 * ────────────────────────── EMERGENCY-CALL SAFETY ──────────────────────────
 * This NEVER touches emergency-call or factory-reset confirmation. Guarantees:
 *  1. We are an AFTER hook and act ONLY when the native method returned null.
 *     Emergency-call and factory-reset confirmations return NON-null, so for
 *     those utterances this code never runs at all.
 *  2. We act ONLY when the last observation is a compose draft awaiting send
 *     ("...must be confirmed before it can be sent"), and we explicitly BAIL if
 *     the observation carries an EMERGENCY or RESET context tag.
 *  3. We ONLY ever emit ConfirmSendMessage / CancelSendMessage — never any
 *     emergency or reset action.
 * The native emergency/reset paths are therefore completely unchanged.
 */
object VoiceComposeHooks {
    private const val TAG = "PenumbraHook"

    // From ComposeMessageActionHandler.OBSERVATION_SUCCESS — the draft-awaiting-confirm marker.
    private const val CONFIRM_DRAFT_MARKER = "must be confirmed before it can be sent"

    // @Action(nameForModel=...) values — the action strings the orchestrator/messages route on.
    private const val ACTION_CONFIRM = "ConfirmSendMessage"
    private const val ACTION_CANCEL = "CancelSendMessage"
    private const val ACTION_COMPOSE = "ComposeMessage"

    // MISSING_MESSAGE state ("what do you want to say?") — the next utterance is the body.
    private const val PROVIDE_CONTENTS_MARKER = "next request will probably contain the message contents"

    // When we're about to dictate a message BODY (prompted compose / edit), force the
    // ASR to formatting mode (punctuation) instead of contact-biasing mode — they're
    // mutually exclusive in the firmware, and the body doesn't need contact names.
    @Volatile private var expectingBodyUntil = 0L
    private fun expectingBody() = System.currentTimeMillis() < expectingBodyUntil
    private fun setExpectingBody(on: Boolean) {
        expectingBodyUntil = if (on) System.currentTimeMillis() + 30_000L else 0L
    }

    // Recipient of the current compose, captured from the command, so we can emit a
    // VALID ComposeMessage (To is effectively required) when we supply the body.
    @Volatile private var lastRecipient: String? = null
    private val RECIPIENT = listOf(
        Regex("^(?:can you |please |hey )?send (?:a |an )?(?:message|text) to (.+?)(?: (?:saying|that says|to say) .*)?$"),
        Regex("^(?:can you |please |hey )?(?:text|message|tell) (.+?)(?: (?:saying|that says|to say) .*)?$"),
    )

    // "edit" at the confirm prompt re-dictates the body — unified with the prompted
    // "send message to X" -> "what do you want to say?" flow via the awaiting-body state.
    private val EDIT_PHRASES = setOf(
        "edit", "edit it", "edit that", "edit the message", "edit message",
        "change it", "change that", "change the message", "redo", "redo it",
        "rewrite", "rewrite it", "let me redo it", "let me try again", "try again",
        "no edit it", "wait edit", "no change it"
    )

    // Confirm aliases — saying any of these sends the pending draft (never the LLM).
    private val CONFIRM_PHRASES = setOf(
        "yes", "yeah", "yep", "yup", "sure", "send it", "send", "send that",
        "send away", "do it", "go ahead", "go", "confirm", "confirmed", "ok",
        "okay", "sounds good", "looks good", "that works", "send message",
        "yes send it", "please send", "send the message", "ok send it"
    )

    // Cancel aliases — discards the pending draft (never the LLM).
    private val CANCEL_PHRASES = setOf(
        "no", "nope", "nah", "cancel", "cancel that", "scratch that", "stop",
        "never mind", "nevermind", "forget it", "don't", "dont", "do not send",
        "dont send", "don't send", "abort", "discard"
    )

    // Leading-word fallbacks. ASR on the hands-free listening window often repeats or
    // appends words ("yes yes yes yes", "yeah send it", "no thanks"), so after collapsing
    // repeats we also match on the FIRST word, not just exact phrases.
    private val CONFIRM_LEAD = setOf("yes", "yeah", "yep", "yup", "sure", "send", "confirm", "confirmed", "ok", "okay", "go")
    private val CANCEL_LEAD = setOf("no", "nope", "nah", "cancel", "stop", "abort", "discard", "nevermind")
    private val EDIT_LEAD = setOf("edit", "change", "redo", "rewrite")

    /** "yes yes yes." -> "yes" : strip trailing punctuation, collapse consecutive dup words. */
    private fun normalizeFollowup(s: String): String {
        val cleaned = s.lowercase().trim().trimEnd('.', '!', '?', ',').trim()
        val out = ArrayList<String>()
        for (w in cleaned.split(Regex("\\s+"))) if (w.isNotBlank() && out.lastOrNull() != w) out.add(w)
        return out.joinToString(" ")
    }

    fun install(cl: ClassLoader) {
        try {
            val confirmInterpreter =
                cl.loadClass("humaneinternal.system.intent.interpreters.ConfirmationInterpreter")
            val eventsSnapshot = cl.loadClass("humaneinternal.system.intent.EventsSnapshot")
            val situation = cl.loadClass("humaneinternal.system.intent.situation.Situation")

            val interpret = confirmInterpreter.getDeclaredMethod("interpret", eventsSnapshot, situation)
            interpret.isAccessible = true

            XposedBridge.hookMethod(interpret, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // SAFETY (1): native already produced a result (e.g. emergency
                        // call / factory reset confirmation) — never override it.
                        if (param.result != null) return

                        val events = param.args[0] ?: return
                        val interpreter = param.thisObject ?: return

                        val utterance = normalizedUtterance(interpreter, events)
                        val obs = lastObservationText(events)
                        val u = utterance?.lowercase()?.trim().orEmpty()
                        // Diagnostic: see exactly what the confirm interpreter received.
                        Log.w(TAG, "VoiceCompose interpret: u='$u' obsNull=${obs == null} " +
                            "draft=${obs?.contains(CONFIRM_DRAFT_MARKER) == true} " +
                            "emerg=${obs?.contains("EMERGENCY") == true || obs?.contains("RESET") == true}")

                        if (u.isBlank()) return
                        if (obs == null) return

                        // SAFETY (2): never act on emergency / reset confirmation contexts.
                        if (obs.contains("EMERGENCY") || obs.contains("RESET")) return

                        // To is effectively required for a valid ComposeMessage; supply the
                        // recipient we captured from the original command.
                        val toInput = lastRecipient?.let { mapOf("To" to listOf(it)) } ?: emptyMap()

                        // AWAITING BODY ("what do you want to say?") — from a prompted
                        // "send message to X" OR after "edit". THIS utterance is the message.
                        if (obs.contains(PROVIDE_CONTENTS_MARKER)) {
                            val nu = normalizeFollowup(u)
                            if (CANCEL_PHRASES.contains(nu) || nu.substringBefore(" ") in CANCEL_LEAD) {
                                VoiceActions.events(cl, ACTION_CANCEL)?.let { param.result = it }
                                AutoListenHooks.disarmCompose()
                                Log.w(TAG, "VoiceCompose: body cancelled"); return
                            }
                            val body = utterance ?: return
                            setExpectingBody(false)   // body captured
                            val out = VoiceActions.events(cl, ACTION_COMPOSE, mapOf("Message" to body), toInput) ?: return
                            param.result = out
                            AutoListenHooks.armForCompose()   // the "send it?" confirm prompt is next
                            Log.w(TAG, "VoiceCompose: body=\"$body\" -> ComposeMessage(To+Message)")
                            return
                        }

                        // Only the confirm (READY_TO_SEND) state remains.
                        if (!obs.contains(CONFIRM_DRAFT_MARKER)) return

                        // Normalize the (possibly repeated/padded) hands-free utterance and
                        // also consider its leading word.
                        val un = normalizeFollowup(u)
                        val lead = un.substringBefore(" ")

                        // "edit" -> re-prompt for a new body: emit ComposeMessage with To but
                        // NO Message; the handler returns to MISSING_MESSAGE and asks again,
                        // then the awaiting-body branch above captures the new body.
                        if (EDIT_PHRASES.contains(un) || lead in EDIT_LEAD) {
                            val out = VoiceActions.events(cl, ACTION_COMPOSE, emptyMap(), toInput) ?: return
                            param.result = out
                            setExpectingBody(true)   // a new body will be dictated next
                            AutoListenHooks.armForCompose()   // body re-prompt is next
                            Log.w(TAG, "VoiceCompose: \"$u\" -> edit (re-prompt for body)")
                            return
                        }

                        val action = when {
                            CONFIRM_PHRASES.contains(un) || lead in CONFIRM_LEAD -> ACTION_CONFIRM
                            CANCEL_PHRASES.contains(un) || lead in CANCEL_LEAD -> ACTION_CANCEL
                            else -> { Log.w(TAG, "  VoiceCompose: '$u' (norm '$un') not confirm/cancel/edit"); return }
                        }

                        val out = VoiceActions.events(cl, action) ?: return
                        param.result = out
                        AutoListenHooks.disarmCompose()   // dialogue done (sent or cancelled)
                        Log.w(TAG, "VoiceCompose: \"$u\" -> $action (SYNAPSE/LLM bypassed)")
                    } catch (t: Throwable) {
                        // Never destabilize the interpreter; just decline.
                        Log.e(TAG, "VoiceCompose afterHook failed (non-fatal): ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  VoiceComposeHooks installed on ConfirmationInterpreter.interpret")

            // Capture the recipient from compose commands (so we can build a valid
            // ComposeMessage when supplying the body for prompted/edit flows).
            val regexInterpreter =
                cl.loadClass("humaneinternal.system.intent.interpreters.regex.RegexInterpreter")
            val riInterpret = regexInterpreter.getDeclaredMethod("interpret", eventsSnapshot, situation)
            riInterpret.isAccessible = true
            XposedBridge.hookMethod(riInterpret, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val events = param.args[0] ?: return
                        val interpreter = param.thisObject ?: return
                        val u = normalizedUtterance(interpreter, events)
                            ?.lowercase()?.trim()?.trimEnd('.', '?', '!')?.trim() ?: return
                        captureRecipient(u)
                    } catch (_: Throwable) {}
                }
            })
            Log.w(TAG, "  VoiceComposeHooks recipient-capture installed on RegexInterpreter.interpret")

            // ASR body punctuation: when we're expecting a dictated message body, force
            // the recognizer into formatting mode (punctuation) instead of contact
            // biasing — only for that session, so command/contact biasing is untouched.
            try {
                val transcriber = cl.loadClass("humaneinternal.system.voice.service.AndroidTranscriber")
                val getIntent = findMethod(transcriber, "getSpeechRecognizerIntent", 0)
                if (getIntent != null) {
                    getIntent.isAccessible = true
                    XposedBridge.hookMethod(getIntent, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (!expectingBody()) return
                                val intent = param.result as? Intent ?: return
                                intent.putExtra("android.speech.extra.ENABLE_FORMATTING", true)
                                intent.putExtra("android.speech.extras.ENABLE_CHARACTER_BIASING", false)
                                Log.w(TAG, "VoiceCompose: ASR formatting forced for body dictation")
                            } catch (_: Throwable) {}
                        }
                    })
                    Log.w(TAG, "  VoiceComposeHooks ASR-formatting hook installed")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "  ASR-formatting hook failed: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "  VoiceComposeHooks install failed: ${t.message}")
        }
    }

    /**
     * events.getCurrent() -> SynapseChatTurn ;
     * interpreter.normalizeUtteranceFromChatTurn(turn) -> normalized utterance.
     * (normalizeUtteranceFromChatTurn lives on the Interpreter base class.)
     */
    private fun captureRecipient(u: String) {
        for (re in RECIPIENT) {
            val r = re.find(u)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (r.isNotEmpty() && r != "message" && r != "a message") {
                lastRecipient = r
                // No inline body ("send message to X" alone) -> the Pin will prompt for
                // the body, so ready ASR formatting for that dictation.
                val hasInlineBody = u.contains(" saying ") || u.contains(" that says ") || u.contains(" to say ")
                setExpectingBody(!hasInlineBody)
                // A compose follow-up prompt (body or "send it?") is coming — let
                // auto-listen re-open the mic after it (gated by the auto_listen flag).
                AutoListenHooks.armForCompose()
                Log.w(TAG, "VoiceCompose: captured recipient='$r' expectingBody=${!hasInlineBody}")
                return
            }
        }
    }

    private fun normalizedUtterance(interpreter: Any, events: Any): String? {
        return try {
            val current = events.javaClass.getMethod("getCurrent").invoke(events) ?: return null
            val m = findMethod(interpreter.javaClass, "normalizeUtteranceFromChatTurn", 1) ?: return null
            m.isAccessible = true
            m.invoke(interpreter, current) as? String
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * events.getLastObservation() -> SynapseChatTurn ;
     * turn.getObservation().getObservation() -> observation text
     * (same chain ConfirmationInterpreter uses for emergency/reset detection).
     */
    private fun lastObservationText(events: Any): String? {
        return try {
            val turn = events.javaClass.getMethod("getLastObservation").invoke(events) ?: return null
            val observation = turn.javaClass.getMethod("getObservation").invoke(turn) ?: return null
            observation.javaClass.getMethod("getObservation").invoke(observation) as? String
        } catch (t: Throwable) {
            null
        }
    }

    /** Find a method by name + parameter count (robust to generated overloads). */
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
