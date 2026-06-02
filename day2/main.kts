#!/usr/bin/env kotlin

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

// Escape a raw string for safe embedding inside a JSON string literal
fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")  // must be first
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

// Extract content[0].text by scanning for the first "text" key and decoding the quoted value
// character-by-character to handle JSON escape sequences.
// Limitation: assumes the first "text" field is the assistant reply; skips \uXXXX Unicode escapes.
fun extractFirstTextField(json: String): String? {
    val keyIndex = json.indexOf("\"text\"")
    if (keyIndex == -1) return null
    val colonIndex = json.indexOf(':', keyIndex + 6)
    if (colonIndex == -1) return null
    val quoteStart = json.indexOf('"', colonIndex + 1)
    if (quoteStart == -1) return null
    val sb = StringBuilder()
    var i = quoteStart + 1
    while (i < json.length) {
        val c = json[i]
        when {
            c == '\\' && i + 1 < json.length -> {
                i++
                when (json[i]) {
                    'n'  -> sb.append('\n')
                    't'  -> sb.append('\t')
                    'r'  -> sb.append('\r')
                    '"'  -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/'  -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(json[i]) }
                }
                i++
            }
            c == '"' -> break
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

val client = HttpClient.newHttpClient()

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

// Send the full conversation history with each request — the API has no memory of its own.
fun sendHistory(history: List<Pair<String, String>>): String? {
    val msgs = history.joinToString(",") { (role, content) ->
        """{"role":"$role","content":"${escapeJson(content)}"}"""
    }
    val body = """{"model":"claude-haiku-4-5-20251001","max_tokens":1024,"messages":[$msgs]}"""
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

    return if (resp.statusCode() == 200) {
        extractFirstTextField(resp.body())
    } else {
        null
    }
}

// In-memory conversation history — lost when the script exits
val history = mutableListOf<Pair<String, String>>()

println("Claude chat  |  model: claude-haiku-4-5-20251001  |  empty line or 'exit' to quit\n")

while (true) {
    System.err.print("You: ")
    val line = readLine()

    // null = EOF (Ctrl+D), blank line, or explicit quit command
    if (line == null || line.isBlank() || line.trim().lowercase() in setOf("exit", "quit")) {
        println("Goodbye!")
        exitProcess(0)
    }

    val input = line.trim()
    history += "user" to input

    try {
        val reply = sendHistory(listOf("user" to input))
        if (reply != null) {
            history += "assistant" to reply
            println("\nClaude: $reply\n")
        } else {
            history.removeLastOrNull()  // drop failed turn so the user can retry
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        history.removeLastOrNull()
    }
}
