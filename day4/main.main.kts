#!/usr/bin/env kotlin

@file:DependsOn("com.google.code.gson:gson:2.10.1")

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

// ─── API key ────────────────────────────────────────────────────────────────

val apiKey = System.getenv("ANTHROPIC_API_KEY")
if (apiKey.isNullOrBlank()) {
    System.err.println("Error: ANTHROPIC_API_KEY is not set or blank.")
    exitProcess(1)
}

// ─── Constants ──────────────────────────────────────────────────────────────

val MODEL = "claude-haiku-4-5-20251001"
val MAX_TOKENS = 512
// 27 API calls total (3 prompts × 3 temperatures × 3 runs) — within the 50-req limit; don't run twice quickly
val PROMPTS = listOf(
    "Invent a name for a coffee shop. Reply with only the name, no explanation." to "Coffee shop name",
    "Complete the phrase with a single word: \"Love is ___\". Reply with only the word." to "Love is ___",
    "Invent a slogan for a sports shoe, max 5 words. Reply with only the slogan, no explanation." to "Sports shoe slogan"
)
val TEMPERATURES = listOf(0.0, 0.7, 1.0)
val RUNS_PER_TEMP = 3

// ─── Utilities (carried from day3) ──────────────────────────────────────────

fun escapeJson(s: String): String = s
    .replace("\\", "\\\\")
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

// ─── askModel ────────────────────────────────────────────────────────────────

// Double.toString() is locale-independent in Kotlin/JVM → "0.0", "0.7", "1.0"
fun askModel(prompt: String, temperature: Double): String? {
    val tempStr = temperature.toString()
    val body = """{"model":"$MODEL","max_tokens":$MAX_TOKENS,"temperature":$tempStr,"messages":[{"role":"user","content":"${escapeJson(prompt)}"}]}"""
    val resp = post(body)
    if (resp.statusCode() != 200) {
        System.err.println("Warning: API returned ${resp.statusCode()} for temperature $temperature — skipping.")
        return null
    }
    val envelope = parseEnvelope(resp.body())
    if (envelope.stopReason == "refusal") {
        System.err.println("Warning: model refused for temperature $temperature.")
        return null
    }
    return envelope.text
}

// ─── Run 27 calls, collect results ──────────────────────────────────────────

data class Result(val promptLabel: String, val temperature: Double, val run: Int, val answer: String?)

val results = mutableListOf<Result>()
for ((promptText, promptLabel) in PROMPTS) {
    for (temp in TEMPERATURES) {
        for (run in 1..RUNS_PER_TEMP) {
            results += Result(promptLabel, temp, run, askModel(promptText, temp))
        }
    }
}

// ─── Print answers only ──────────────────────────────────────────────────────

println()

for ((_, promptLabel) in PROMPTS) {
    println("##### TASK: $promptLabel #####")
    println()
    for (temp in TEMPERATURES) {
        println("===== TEMPERATURE $temp =====")
        println()
        for (r in results.filter { it.promptLabel == promptLabel && it.temperature == temp }) {
            val answer = r.answer?.trim() ?: "[no response]"
            println("[Run ${r.run}] $answer")
        }
        println()
    }
}
