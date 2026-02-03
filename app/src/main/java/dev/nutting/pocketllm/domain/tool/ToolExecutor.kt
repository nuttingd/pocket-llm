package dev.nutting.pocketllm.domain.tool

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolExecutor {

    private const val TAG = "ToolExecutor"
    private const val TOOL_TIMEOUT_MS = 30_000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(toolName: String, argumentsJson: String): String {
        return try {
            withTimeout(TOOL_TIMEOUT_MS) {
                val args = json.parseToJsonElement(argumentsJson).jsonObject
                when (toolName) {
                    "calculator" -> {
                        val expression = args["expression"]?.jsonPrimitive?.content
                            ?: return@withTimeout "Error: missing 'expression' argument"
                        CalculatorTool.evaluate(expression)
                    }
                    "web_fetch" -> {
                        val url = args["url"]?.jsonPrimitive?.content
                            ?: return@withTimeout "Error: missing 'url' argument"
                        WebFetchTool.fetch(url)
                    }
                    else -> "Error: unknown tool '$toolName'"
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Tool '$toolName' timed out after ${TOOL_TIMEOUT_MS}ms", e)
            "Error: tool '$toolName' timed out"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute tool '$toolName'", e)
            "Error executing tool '$toolName': ${e.message}"
        }
    }
}
