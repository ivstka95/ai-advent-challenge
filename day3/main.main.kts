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

// ─── API key ────────────────────────────────────────────────────────────────

val apiKey = System.getenv("ANTHROPIC_API_KEY")
if (apiKey.isNullOrBlank()) {
    System.err.println("Error: ANTHROPIC_API_KEY is not set or blank.")
    exitProcess(1)
}

// ─── Constants ──────────────────────────────────────────────────────────────

val MODEL = "claude-haiku-4-5-20251001"
val MAX_TOKENS = 1024

val PROBLEM = "A train leaves city A at 14:00 traveling at 60 km/h. Another train leaves city B (300 km away from city A) at 14:30, traveling toward city A at 90 km/h. At what time do they meet?"

// ─── Utilities (carried from day2) ──────────────────────────────────────────

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

fun askModel(system: String?, userMessage: String): String? {
    val systemPart = if (system != null) """"system":"${escapeJson(system)}",""" else ""
    val body = """{"model":"$MODEL","max_tokens":$MAX_TOKENS,${systemPart}"messages":[{"role":"user","content":"${escapeJson(userMessage)}"}]}"""
    val resp = post(body)
    if (resp.statusCode() != 200) {
        System.err.println("Warning: API returned ${resp.statusCode()} — skipping this method.")
        return null
    }
    val envelope = parseEnvelope(resp.body())
    if (envelope.stopReason == "refusal") {
        System.err.println("Warning: model refused to answer.")
        return null
    }
    return envelope.text
}

// ─── Output helpers ──────────────────────────────────────────────────────────

fun section(title: String) = println("\n===== $title =====\n")

fun lastLine(answer: String?): String =
    answer?.trimEnd()?.lines()?.lastOrNull { it.isNotBlank() } ?: "(see full output above)"

// ─── Run all methods ─────────────────────────────────────────────────────────

// METHOD 1: DIRECT
section("METHOD 1: DIRECT")
val method1 = askModel(system = null, userMessage = PROBLEM)
println(method1 ?: "[no response]")

// METHOD 2: STEP-BY-STEP (CHAIN-OF-THOUGHT)
section("METHOD 2: STEP-BY-STEP (CHAIN-OF-THOUGHT)")
val method2 = askModel(
    system = "Reason step by step before giving the final answer.",
    userMessage = PROBLEM
)
println(method2 ?: "[no response]")

// METHOD 3: META-PROMPTING (2 calls)
section("METHOD 3: META-PROMPTING")
val generatedPrompt = askModel(
    system = "Write an effective prompt for solving this mathematical word problem. Return ONLY the prompt text, nothing else.",
    userMessage = PROBLEM
)
println("[Generated prompt:]")
println(generatedPrompt ?: "[no response]")
println()
val method3Solution = if (generatedPrompt != null) {
    askModel(system = null, userMessage = generatedPrompt)
} else null
println("[Solution using generated prompt:]")
println(method3Solution ?: "[no response]")

// METHOD 4a: PANEL — SINGLE CALL
section("METHOD 4a: PANEL (single call)")
val method4a = askModel(
    system = "You are three expert personas responding together: an Analyst, an Engineer, and a Critic. Have each persona give their take on the problem, then provide a consolidated final answer.",
    userMessage = PROBLEM
)
println(method4a ?: "[no response]")

// METHOD 4b: PANEL — SEPARATE CALLS
section("METHOD 4b: PANEL (separate calls)")

val analystAnswer = askModel(system = "You are an expert analyst.", userMessage = PROBLEM)
println("[Analyst:]")
println(analystAnswer ?: "[no response]")
println()

val engineerAnswer = askModel(system = "You are an expert engineer.", userMessage = PROBLEM)
println("[Engineer:]")
println(engineerAnswer ?: "[no response]")
println()

val criticAnswer = askModel(system = "You are a critic.", userMessage = PROBLEM)
println("[Critic:]")
println(criticAnswer ?: "[no response]")
println()

val consolidationInput = buildString {
    append("Three independent experts answered the same problem:\n\n")
    append("Analyst:\n${analystAnswer ?: "(no answer)"}\n\n")
    append("Engineer:\n${engineerAnswer ?: "(no answer)"}\n\n")
    append("Critic:\n${criticAnswer ?: "(no answer)"}\n\n")
    append("Original problem: $PROBLEM\n\n")
    append("Provide a consolidated final answer.")
}
val method4bConsolidated = askModel(system = null, userMessage = consolidationInput)
println("[Consolidated:]")
println(method4bConsolidated ?: "[no response]")

// ─── Comparison footer ───────────────────────────────────────────────────────

println()
println("=" .repeat(60))
println("COMPARISON FOOTER")
println("=" .repeat(60))
println("Correct answer: 16:18")
println()
println("  Method 1 (Direct):              ${lastLine(method1)}")
println("  Method 2 (CoT):                 ${lastLine(method2)}")
println("  Method 3 (Meta-prompting):      ${lastLine(method3Solution)}")
println("  Method 4a (Panel, single call): ${lastLine(method4a)}")
println("  Method 4b (Panel, separate):    ${lastLine(method4bConsolidated)}")
println("=" .repeat(60))
