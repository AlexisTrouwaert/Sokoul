package com.sokoul.pc.control

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What a tool can affect. It decides who may use it without asking: an assistant reads and moves around freely, but
 * starting a stream uses an IPTV connection and a setting stays until changed. Nothing here gives access to a key.
 */
enum class ToolRisk { Read, Navigate, Playback, Setting }

data class ToolParam(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = true,
    val choices: List<String> = emptyList(),
)

/** One thing the app can do or tell, usable the same way by the live checks and by a future assistant. */
class AppTool(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val params: List<ToolParam> = emptyList(),
    val run: suspend (JsonObject) -> JsonElement,
) {
    /** The parameters as a JSON Schema: the form language models expect their tools in. */
    fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            params.forEach { p ->
                putJsonObject(p.name) {
                    put("type", p.type)
                    put("description", p.description)
                    if (p.choices.isNotEmpty()) putJsonArray("enum") { p.choices.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
    }
}

sealed interface ToolResult {
    data class Done(val value: JsonElement) : ToolResult
    /** The policy did not allow it: the assistant must ask the user. */
    data class Refused(val reason: String) : ToolResult
    data class Failed(val message: String) : ToolResult
}

/** A tool cannot run with what it was given, such as a title that does not exist. */
class ToolProblem(message: String) : Exception(message)

/** Who may run what. */
fun interface ToolPolicy {
    fun allows(tool: AppTool): Boolean

    companion object {
        /** The live checks. */
        val Everything = ToolPolicy { true }

        /** An assistant on its own: anything that opens a stream or changes a setting waits for the user's yes. */
        val AssistantAlone = ToolPolicy { it.risk == ToolRisk.Read || it.risk == ToolRisk.Navigate }
    }
}

class ToolRegistry(val tools: List<AppTool>) {
    init {
        require(tools.map { it.name }.toSet().size == tools.size) { "Two tools share a name" }
    }

    suspend fun call(name: String, args: JsonObject, policy: ToolPolicy): ToolResult {
        val tool = tools.firstOrNull { it.name == name } ?: return ToolResult.Failed("Unknown tool: $name")
        if (!policy.allows(tool)) return ToolResult.Refused("$name needs the user's confirmation")
        tool.params.firstOrNull { it.required && it.name !in args }?.let { return ToolResult.Failed("Missing parameter: ${it.name}") }
        return try {
            ToolResult.Done(tool.run(args))
        } catch (c: CancellationException) {
            throw c
        } catch (p: ToolProblem) {
            ToolResult.Failed(p.message.orEmpty())
        } catch (e: Exception) {
            // The class alone: a message from a network failure can carry an address with a key in it.
            ToolResult.Failed(e.javaClass.simpleName)
        }
    }
}

fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull ?: throw ToolProblem("Missing parameter: $name")

fun JsonObject.textOr(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

fun JsonObject.number(name: String): Int = textOr(name)?.toIntOrNull() ?: throw ToolProblem("Parameter $name must be a number")

fun JsonObject.numberOr(name: String): Int? = textOr(name)?.toIntOrNull()
