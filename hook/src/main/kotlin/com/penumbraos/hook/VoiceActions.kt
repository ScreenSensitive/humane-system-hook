package com.penumbraos.hook

import android.util.Log

/**
 * Shared reflection helper for emitting Humane intent actions from a hook.
 *
 * The hook APK doesn't compile against Humane's classes, so we go through the
 * target's classloader. We reuse the SAME native construction RegexInterpreter
 * uses: ActionContentFactory.of(name, stringInputs, listInputs) builds a valid
 * SynapseActionContent (correct source/fields/validity), then
 * SynapseChatTurnUtils.toSupervisorIntermediateEvents wraps it into the
 * List<IntermediateEvent> the InterpreterOrchestrator returns. Returning that
 * list from any interpreter stage makes it the winning result — the cascade
 * stops there and never reaches the dead SYNAPSE/LLM.
 */
object VoiceActions {
    private const val TAG = "PenumbraHook"

    /**
     * Build the orchestrator's List<IntermediateEvent> for a single device action.
     * [actionName] is the @Action nameForModel (e.g. "Narrate", "ConfirmSendMessage").
     * [stringInputs] are @Field values keyed by their nameForModel (e.g. "Narration").
     */
    fun events(
        cl: ClassLoader,
        actionName: String,
        stringInputs: Map<String, String> = emptyMap(),
        listInputs: Map<String, List<String>> = emptyMap(),
    ): Any? {
        return try {
            val factory = cl.loadClass("humaneinternal.system.intent.interpreters.ActionContentFactory")
            val chatTurnCls = cl.loadClass("humane.aibus.SynapseChatTurn")
            val actionContentCls = cl.loadClass("humane.aibus.SynapseActionContent")
            val utilsCls = cl.loadClass("humaneinternal.system.intent.SynapseChatTurnUtils")

            // ActionContentFactory.of(name, Map<String,String>, Map<String,List<String>>)
            val ofMethod = factory.getMethod("of", String::class.java, Map::class.java, Map::class.java)
            val actionContent = ofMethod.invoke(null, actionName, stringInputs, listInputs)!!

            // SynapseChatTurn.newBuilder().setAction(actionContent).build()
            val turnBuilder = chatTurnCls.getMethod("newBuilder").invoke(null)!!
            val withAction = turnBuilder.javaClass.getMethod("setAction", actionContentCls)
                .invoke(turnBuilder, actionContent)!!
            val turn = withAction.javaClass.getMethod("build").invoke(withAction)!!

            // SynapseChatTurnUtils.toSupervisorIntermediateEvents(List.of(turn))
            utilsCls.getMethod("toSupervisorIntermediateEvents", List::class.java)
                .invoke(null, java.util.Collections.singletonList(turn))
        } catch (t: Throwable) {
            Log.e(TAG, "VoiceActions.events($actionName) failed: ${t.message}")
            null
        }
    }

    /** Convenience: a NarrateAction that speaks [text] (and stops the cascade). */
    fun narrate(cl: ClassLoader, text: String): Any? =
        events(cl, "Narrate", mapOf("Narration" to text))
}
