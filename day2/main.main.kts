#!/usr/bin/env kotlin

@file:DependsOn("com.google.code.gson:gson:2.10.1")

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

val apiKey = System.getenv("ANTHROPIC_API_KEY")
if (apiKey.isNullOrBlank()) {
    System.err.println("Error: ANTHROPIC_API_KEY is not set or blank.")
    exitProcess(1)
}

val structuredMode = "--structured" in args
val historyMode    = "--history"    in args

fun argValue(flag: String): Int? =
    args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }

val maxTokensArg = argValue("--max-tokens")
val maxTokens    = maxTokensArg ?: 1024
val maxWords     = argValue("--max-words")

fun argList(flag: String): List<String>? =
    args.indexOf(flag).takeIf { it >= 0 }
        ?.let { args.getOrNull(it + 1) }
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }

val stopSequences = argList("--stop")

// Escape a raw string for safe embedding inside a JSON string literal
fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")  // must be first
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

fun prettyJson(json: String): String {
    val sb = StringBuilder()
    var indent = 0
    var inString = false
    var i = 0
    while (i < json.length) {
        val c = json[i]
        when {
            inString -> {
                sb.append(c)
                if (c == '\\' && i + 1 < json.length) { i++; sb.append(json[i]) }
                else if (c == '"') inString = false
            }
            c == '"' -> { sb.append(c); inString = true }
            c == '{' || c == '[' -> {
                sb.append(c); indent++
                val next = json.drop(i + 1).trimStart().firstOrNull()
                if (next != null && next != '}' && next != ']')
                    sb.append('\n').append("  ".repeat(indent))
            }
            c == '}' || c == ']' -> { indent--; sb.append('\n').append("  ".repeat(indent)).append(c) }
            c == ',' -> sb.append(',').append('\n').append("  ".repeat(indent))
            c == ':' -> sb.append(": ")
            c == ' ' || c == '\n' || c == '\r' || c == '\t' -> Unit
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

fun logNet(label: String, headers: Map<String, List<String>>, body: String) {
    System.err.println("\n── $label ──────────────────────────────────")
    headers.filter { (k, _) -> !k.equals("x-api-key", ignoreCase = true) }
           .forEach { (k, vs) -> System.err.println("  $k: ${vs.joinToString(", ")}") }
    System.err.println()
    System.err.println(if (body.trimStart().firstOrNull() in listOf('{', '[')) prettyJson(body) else body)
    System.err.println("────────────────────────────────────────────\n")
}

fun JsonElement?.asStringOrNull(): String? = if (this == null || this.isJsonNull) null else this.asString

data class Envelope(val text: String?, val stopReason: String?, val stopSequence: String?)

fun parseEnvelope(body: String): Envelope {
    val root = JsonParser.parseString(body).asJsonObject
    val stopReason   = root.get("stop_reason").asStringOrNull()
    val stopSequence = root.get("stop_sequence").asStringOrNull()
    val text = root.getAsJsonArray("content")
        ?.get(0)?.asJsonObject
        ?.get("text").asStringOrNull()
    return Envelope(text, stopReason, stopSequence)
}

val client = HttpClient.newHttpClient()

fun post(body: String): HttpResponse<String> {
    val req = HttpRequest.newBuilder()
        .uri(URI.create("https://api.anthropic.com/v1/messages"))
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    logNet("REQUEST  POST ${req.uri()}", req.headers().map(), body)
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    logNet("RESPONSE ${resp.statusCode()}", resp.headers().map(), resp.body())
    return resp
}

fun sendPlain(messages: List<Pair<String, String>>): String? {
    val msgs = messages.joinToString(",") { (role, content) ->
        """{"role":"$role","content":"${escapeJson(content)}"}"""
    }
    val systemPart = if (maxWords != null) """"system":"${escapeJson("Answer in at most $maxWords words.")}",""" else ""
    val stopPart   = stopSequences?.joinToString(",", prefix = "\"stop_sequences\":[", postfix = "],") { "\"${escapeJson(it)}\"" } ?: ""
    val body = """{"model":"claude-haiku-4-5-20251001","max_tokens":$maxTokens,${systemPart}${stopPart}"messages":[$msgs]}"""
    val resp = post(body)
    if (resp.statusCode() != 200) return null
    val envelope = parseEnvelope(resp.body())
    if (envelope.stopReason == "stop_sequence")
        println("\n[Stopped on sequence: \"${envelope.stopSequence}\"]\n")
    return envelope.text
}

data class StructuredResponse(val summary: String, val details: String)

fun sendStructured(messages: List<Pair<String, String>>): StructuredResponse? {
    val msgs = messages.joinToString(",") { (role, content) ->
        """{"role":"$role","content":"${escapeJson(content)}"}"""
    }
    val wordLimit = if (maxWords != null) " Use at most $maxWords words total." else ""
    val system = "In the summary field, give a one-sentence answer, as short as possible, " +
        "just enough to answer the question, with no filler openers; in the details field, " +
        "give a more detailed explanation in 2-4 sentences.$wordLimit"
    val schema   = """{"type":"object","properties":{"summary":{"type":"string","description":"One-sentence answer, as short as possible"},"details":{"type":"string","description":"Detailed explanation, 2-4 sentences"}},"required":["summary","details"],"additionalProperties":false}"""
    val stopPart = stopSequences?.joinToString(",", prefix = "\"stop_sequences\":[", postfix = "],") { "\"${escapeJson(it)}\"" } ?: ""
    val body = """{"model":"claude-haiku-4-5-20251001","max_tokens":$maxTokens,"system":"${escapeJson(system)}",${stopPart}"messages":[$msgs],"output_config":{"format":{"type":"json_schema","schema":$schema}}}"""
    val resp = post(body)
    if (resp.statusCode() != 200) return null
    val (text, stopReason, stopSequence) = parseEnvelope(resp.body())
    when (stopReason) {
        "max_tokens"    -> { System.err.println("Warning: response truncated (max_tokens). JSON may be incomplete."); return null }
        "refusal"       -> { System.err.println("Warning: model refused to answer."); return null }
        "stop_sequence" -> println("\n[Stopped on sequence: \"$stopSequence\"]\n")
    }
    return text?.let {
        try { Gson().fromJson(it, StructuredResponse::class.java) }
        catch (e: Exception) {
            System.err.println("Warning: JSON parsing failed (likely cut by stop sequence): ${e.message}")
            null
        }
    }
}

val history: MutableList<Pair<String, String>>? = if (historyMode) mutableListOf() else null
val modeLabel      = if (structuredMode) "structured" else "plain"
val historyLabel   = if (historyMode) "on" else "off"
val maxTokensLabel = maxTokensArg?.let { "$it" } ?: "default"
val maxWordsLabel  = maxWords?.let { "$it" } ?: "off"
val stopLabel      = stopSequences?.let { "[${it.joinToString(", ")}]" } ?: "off"
println("Claude chat  |  model: claude-haiku-4-5-20251001  |  mode: $modeLabel  |  history: $historyLabel  |  max_tokens: $maxTokensLabel  |  max_words: $maxWordsLabel  |  stop: $stopLabel  |  empty line or 'exit' to quit\n")

while (true) {
    System.err.print("You: ")
    val line = readLine()

    if (line == null || line.isBlank() || line.trim().lowercase() in setOf("exit", "quit")) {
        println("Goodbye!")
        exitProcess(0)
    }

    val input = line.trim()
    val messages = if (history != null) {
        history.add("user" to input)
        history.toList()
    } else {
        listOf("user" to input)
    }

    try {
        if (structuredMode) {
            val result = sendStructured(messages)
            if (result != null) {
                history?.add("assistant" to "${result.summary} ${result.details}")
                println("\nSummary: ${result.summary}\n\nDetails: ${result.details}\n")
            } else {
                history?.removeLastOrNull()
            }
        } else {
            val reply = sendPlain(messages)
            if (reply != null) {
                history?.add("assistant" to reply)
                println("\nClaude: $reply\n")
            } else {
                history?.removeLastOrNull()
            }
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        history?.removeLastOrNull()
    }
}
