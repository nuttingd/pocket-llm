package dev.nutting.pocketllm.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(toolName: String, argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObject
            when (toolName) {
                "calculator" -> {
                    val expression = args["expression"]?.jsonPrimitive?.content
                        ?: return "Error: missing 'expression' argument"
                    CalculatorTool.evaluate(expression)
                }
                "web_fetch" -> {
                    val url = args["url"]?.jsonPrimitive?.content
                        ?: return "Error: missing 'url' argument"
                    WebFetchTool.fetch(url)
                }
                else -> "Error: unknown tool '$toolName'"
            }
        } catch (e: Exception) {
            "Error executing tool '$toolName': ${e.message}"
        }
    }
}
