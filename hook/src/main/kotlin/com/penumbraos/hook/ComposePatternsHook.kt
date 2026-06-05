package com.penumbraos.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.regex.Pattern

/**
 * Adds "text <name> <body>" and "tell <name> <body>" to the native ComposeMessage
 * recognition. The firmware's RegexIntentEngine only ships "send (a) message (to)
 * <name> saying <body>" / "message <name> saying <body>" — there is NO "text"/"tell"
 * phrasing, so "text mom hello" falls through to the dead cloud.
 *
 * We hook RegexIntentEngine.updateContactRegexesEntities(List<String>) — called by
 * RegexInterpreter whenever the contacts list changes — and, AFTER the engine
 * rebuilds its ComposeMessage patterns from the contact names, append our extra
 * patterns built from the SAME names (so <To> only matches real contacts). We
 * mirror the engine's own bookkeeping exactly: compile the pattern, extract its
 * named groups via the engine's `namedGroupExtractor`, register them in
 * `groupNamesByRegex`, and add the pattern to the `compiledRegexes["ComposeMessage"]`
 * list. Re-runs on every contacts update (the engine replaces the list each time).
 *
 * Result: "text mom hello" / "tell dad I'm on my way" recognize natively and emit
 * a real ComposeMessage action — same draft+confirm flow as "send message to ...".
 */
object ComposePatternsHook {
    private const val TAG = "PenumbraHook"

    fun install(cl: ClassLoader) {
        try {
            val engineCls =
                cl.loadClass("humaneinternal.system.intent.interpreters.regex.RegexIntentEngine")
            val method = engineCls.getDeclaredMethod("updateContactRegexesEntities", List::class.java)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val entities = param.args.getOrNull(0) as? List<String> ?: return
                        if (entities.isEmpty()) return
                        addTextPatterns(param.thisObject, entities)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ComposePatterns afterHook failed (non-fatal): ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  ComposePatternsHook installed on RegexIntentEngine.updateContactRegexesEntities")
        } catch (t: Throwable) {
            Log.e(TAG, "  ComposePatternsHook install failed: ${t.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addTextPatterns(engine: Any, entities: List<String>) {
        val strJoin = entities.joinToString("|")
        if (strJoin.isBlank()) return

        // Mirror the engine's own ComposeMessage pattern shape, for "text"/"tell".
        val extra = listOf(
            "(can you |please )?text (?<To>$strJoin)( that says| saying)? (?<Message>.+)",
            "(can you |please )?text (?<To>$strJoin)\$",
            "(can you |please )?tell (?<To>$strJoin)( that| to say| saying)? (?<Message>.+)",
        )

        val cls = engine.javaClass
        val compiledF = cls.getDeclaredField("compiledRegexes").apply { isAccessible = true }
        val groupF = cls.getDeclaredField("groupNamesByRegex").apply { isAccessible = true }
        val extractorF = cls.getDeclaredField("namedGroupExtractor").apply { isAccessible = true }

        val compiled = compiledF.get(engine) as MutableMap<String, List<Pattern>>
        val groupNames = groupF.get(engine) as MutableMap<Pattern, List<String>>
        val extractor = extractorF.get(null) as Pattern   // private static final

        val base = compiled["ComposeMessage"] ?: return
        val merged = ArrayList(base)
        var added = 0
        for (regex in extra) {
            try {
                val pat = Pattern.compile(regex)
                val names = ArrayList<String>()
                val m = extractor.matcher(regex)
                while (m.find()) {
                    for (i in 1..m.groupCount()) m.group(i)?.let { names.add(it) }
                }
                groupNames[pat] = names
                merged.add(pat)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "  bad compose pattern skipped: ${e.message}")
            }
        }
        compiled["ComposeMessage"] = merged
        Log.w(TAG, "  Added $added text/tell ComposeMessage patterns (${entities.size} contacts)")
    }
}
