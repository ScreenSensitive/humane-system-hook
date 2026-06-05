package com.penumbraos.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * Native catch-me-up + read-message voice commands for ironman — the permanent
 * replacement for the Frida voice_handlers catch-up / read flow.
 *
 * Hooks RegexInterpreter.interpret (the REGEX cascade stage, which runs first for
 * non-contextual utterances). As an AFTER hook it inspects the utterance and, for
 * our commands, replaces the result with a NarrateAction carrying deterministic
 * text built locally from the NotificationDatabase — so it's offline, never hangs,
 * and never reaches the dead SYNAPSE/LLM. For "catch me up" this also overrides the
 * native CatchMeUpAction (which would call the cloud NotificationSummarizer gRPC).
 *
 * Catch-up reads COUNTS + WHO ONLY (no content); content is read only on explicit
 * "read messages [from] <name>" / "read messages" (Siri-style).
 */
object VoiceReadHooks {
    private const val TAG = "PenumbraHook"

    private val CATCH_UP = Regex(
        "^(?:can you |please )?(?:catch me up|what did i miss|what'?s new|" +
            "what'?s been happening|any new messages|do i have (?:any )?(?:new )?messages|" +
            "any missed calls|did i miss anything)$"
    )
    private val READ_NEWEST = Regex(
        "^read (?:my )?(?:last |new |latest |the last |the )?messages?$|" +
            "^read (?:it|that|them)$"
    )
    // "read messages from Mom", "read my messages from Mom", "read Mom's messages",
    // "what did Mom say", "read messages Mom"
    private val READ_FROM = listOf(
        Regex("^(?:read (?:my )?(?:last )?messages? from |what did )(.+?)(?: (?:say|said|text|send|write))?$"),
        Regex("^read (.+?)'?s (?:last )?messages?$"),
        Regex("^read messages? (.+)$")
    )
    // "repeat" replays the last catch-up / read narration (Siri-style).
    private val REPEAT = Regex(
        "^(?:repeat(?: that)?|say (?:that|it) again|again|what was that|come again)$"
    )

    // The last catch-up summary, replayed by "repeat". Set ONLY by "catch me up"
    // (which purges the previous one); null when the last catch-up had nothing new.
    // So "repeat" always replays the current catch-up until the next "catch me up".
    @Volatile private var catchUpBuffer: String? = null

    fun install(cl: ClassLoader) {
        try {
            val regexInterpreter =
                cl.loadClass("humaneinternal.system.intent.interpreters.regex.RegexInterpreter")
            val eventsSnapshot = cl.loadClass("humaneinternal.system.intent.EventsSnapshot")
            val situation = cl.loadClass("humaneinternal.system.intent.situation.Situation")
            val interpret = regexInterpreter.getDeclaredMethod("interpret", eventsSnapshot, situation)
            interpret.isAccessible = true

            XposedBridge.hookMethod(interpret, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val events = param.args[0] ?: return
                        val interpreter = param.thisObject ?: return
                        val raw = normalizedUtterance(interpreter, events) ?: return
                        val u = raw.lowercase().trim().trimEnd('.', '?', '!').trim()
                        if (u.isEmpty()) return

                        val text: String = when {
                            REPEAT.matches(u) -> catchUpBuffer ?: "There's nothing to repeat."
                            CATCH_UP.matches(u) -> {
                                // Purge the old repeat buffer and set the fresh summary
                                // (null => nothing new => buffer cleared).
                                val summary = NotificationReader.catchUpSummary()
                                catchUpBuffer = summary
                                summary ?: "You're all caught up."
                            }
                            READ_NEWEST.matches(u) ->
                                NotificationReader.readNewest() ?: "No new messages."
                            else -> {
                                val name = matchReadFrom(u) ?: return
                                NotificationReader.readFrom(name) ?: "No messages from $name."
                            }
                        }

                        val ev = VoiceActions.narrate(cl, text) ?: return
                        param.result = ev
                        Log.w(TAG, "VoiceRead: \"$u\" -> narrate (SYNAPSE/LLM bypassed)")
                    } catch (t: Throwable) {
                        Log.e(TAG, "VoiceRead afterHook failed (non-fatal): ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  VoiceReadHooks installed on RegexInterpreter.interpret")
        } catch (t: Throwable) {
            Log.e(TAG, "  VoiceReadHooks install failed: ${t.message}")
        }
    }

    /** Returns the contact name if [u] is a read-from-contact command, else null. */
    private fun matchReadFrom(u: String): String? {
        for (re in READ_FROM) {
            val m = re.find(u) ?: continue
            val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
            // Guard: "read messages" with no name must not match as read-from.
            if (name.isNotEmpty() && name != "messages" && name != "message") return name
        }
        return null
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
