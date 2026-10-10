package dev.nutting.pocketllm.domain

import android.util.Log
import dev.nutting.pocketllm.data.remote.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A reply being generated for a conversation, as the chat screen shows it. */
data class ActiveGeneration(
    val conversationId: String,
    val isLocal: Boolean,
    val startedAtMs: Long,
    val content: String = "",
    val thinking: String = "",
    val isCompacting: Boolean = false,
    val pendingToolCalls: List<ToolCall> = emptyList(),
    val toolCallResults: Map<String, String> = emptyMap(),
)

/**
 * Runs replies in an app-wide scope so they outlive the chat screen that started them. A screen
 * (re)attaches by observing [generation] for its conversation; leaving it doesn't stop the reply.
 *
 * At most one reply runs per conversation, and at most one on-device reply overall (there is one engine).
 */
class GenerationManager(
    /** Cancels the in-flight on-device inference (the native call isn't interrupted by coroutine cancellation). */
    private val cancelLocalInference: () -> Unit,
    /** Called once the first reply of a new conversation completes, to name the conversation. */
    private val generateTitle: suspend (conversationId: String, request: TitleRequest, reply: String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _active = MutableStateFlow<Map<String, ActiveGeneration>>(emptyMap())
    val active: StateFlow<Map<String, ActiveGeneration>> = _active.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun generation(conversationId: String): Flow<ActiveGeneration?> =
        _active.map { it[conversationId] }.distinctUntilChanged()

    /** The last failure for [conversationId], kept until [dismissError] so a screen opened later still sees it. */
    fun error(conversationId: String): Flow<String?> =
        _errors.map { it[conversationId] }.distinctUntilChanged()

    fun dismissError(conversationId: String) {
        _errors.update { it - conversationId }
    }

    /**
     * Starts collecting [stream] for [conversationId] in the background. Returns null when started,
     * otherwise why it couldn't start.
     */
    fun start(
        conversationId: String,
        isLocal: Boolean,
        title: TitleRequest? = null,
        stream: () -> Flow<StreamState>,
    ): String? {
        if (jobs.containsKey(conversationId)) return "A reply is already being generated for this chat"
        if (isLocal && _active.value.values.any { it.isLocal }) {
            return "Another chat is generating with the on-device model. Wait for it to finish or stop it first."
        }
        _errors.update { it - conversationId }
        _active.update { it + (conversationId to ActiveGeneration(conversationId, isLocal, startedAtMs = clock())) }
        jobs[conversationId] = scope.launch {
            try {
                stream().collect { state -> onState(conversationId, state, title) }
            } finally {
                approvals.remove(conversationId)?.cancel()
                jobs.remove(conversationId)
                _active.update { it - conversationId }
            }
        }
        return null
    }

    fun stop(conversationId: String) {
        if (_active.value[conversationId]?.isLocal == true) cancelLocalInference()
        jobs[conversationId]?.cancel()
    }

    /** Suspends until the user approves or declines [toolCalls]; wire to [ChatManager.toolApprovalCallback]. */
    suspend fun awaitToolApproval(conversationId: String, toolCalls: List<ToolCall>): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvals[conversationId] = deferred
        update(conversationId) { it.copy(pendingToolCalls = toolCalls, toolCallResults = emptyMap()) }
        return try {
            deferred.await()
        } finally {
            approvals.remove(conversationId, deferred)
            update(conversationId) { it.copy(pendingToolCalls = emptyList()) }
        }
    }

    fun resolveToolCalls(conversationId: String, approved: Boolean) {
        approvals[conversationId]?.complete(approved)
    }

    private fun onState(conversationId: String, state: StreamState, title: TitleRequest?) {
        when (state) {
            is StreamState.Compacting -> update(conversationId) { it.copy(isCompacting = true) }
            is StreamState.Delta -> update(conversationId) {
                it.copy(
                    isCompacting = false,
                    content = it.content + state.content,
                    thinking = it.thinking + (state.thinkingContent ?: ""),
                )
            }
            is StreamState.ToolCallsPending -> update(conversationId) { it.copy(content = "") }
            is StreamState.ToolCallResult -> update(conversationId) {
                it.copy(toolCallResults = it.toolCallResults + (state.toolCallId to state.result), content = "")
            }
            is StreamState.Error -> {
                Log.e(TAG, "Generation failed for ${conversationId.take(8)}: ${state.error}")
                _errors.update { it + (conversationId to state.error) }
            }
            is StreamState.Complete -> if (title != null) {
                scope.launch {
                    try {
                        generateTitle(conversationId, title, state.message.content)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Title generation failed", e)
                    }
                }
            }
        }
    }

    private fun update(conversationId: String, transform: (ActiveGeneration) -> ActiveGeneration) {
        _active.update { map -> map[conversationId]?.let { map + (conversationId to transform(it)) } ?: map }
    }

    companion object {
        private const val TAG = "GenerationManager"
    }
}

/** Server and model to ask for a conversation title, plus the message that started the conversation. */
data class TitleRequest(val serverId: String, val modelId: String, val userMessage: String)
