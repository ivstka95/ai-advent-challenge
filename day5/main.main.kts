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

// ─── Models & prompt ────────────────────────────────────────────────────────

data class ModelEntry(
    val id: String,
    val label: String,
    val inputPricePer1M: Double,
    val outputPricePer1M: Double
)

val MODELS = listOf(
    ModelEntry("claude-haiku-4-5-20251001", "Haiku 4.5 (weak)",   1.00,  5.00),
    ModelEntry("claude-sonnet-4-6",          "Sonnet 4.6 (med)",   3.00, 15.00),
    ModelEntry("claude-opus-4-7",            "Opus 4.7 (strong)",  5.00, 25.00)
)

val MAX_TOKENS = 1024

val PROMPT = "A train leaves city A at 14:00 traveling at 60 km/h. Another train leaves city B (300 km away from city A) at 14:30, traveling toward city A at 90 km/h. At what time do they meet?"

// ─── Utilities (carried from day4) ──────────────────────────────────────────

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

// ─── Envelope — extended with usage ─────────────────────────────────────────

data class Envelope(
    val text: String?,
    val stopReason: String?,
    val stopSequence: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val thinkingTokens: Int
)

fun parseEnvelope(body: String): Envelope {
    val root = JsonParser.parseString(body).asJsonObject
    val stopReason    = root.get("stop_reason").asStringOrNull()
    val stopSequence  = root.get("stop_sequence").asStringOrNull()
    val text = root.getAsJsonArray("content")
        ?.firstOrNull { it.asJsonObject.get("type").asStringOrNull() == "text" }
        ?.asJsonObject?.get("text").asStringOrNull()
    val usage         = root.getAsJsonObject("usage")
    val inputTokens   = usage?.get("input_tokens")?.asInt ?: 0
    val outputTokens  = usage?.get("output_tokens")?.asInt ?: 0
    val thinkingTokens = usage
        ?.getAsJsonObject("output_tokens_details")
        ?.get("thinking_tokens")?.asInt ?: 0
    return Envelope(text, stopReason, stopSequence, inputTokens, outputTokens, thinkingTokens)
}

// ─── HTTP client & post ──────────────────────────────────────────────────────

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

// ─── Per-model result ────────────────────────────────────────────────────────

data class ModelResult(
    val label: String,
    val answer: String?,
    val timeMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val thinkingTokens: Int,
    val cost: Double
)

// ─── Run all three models ────────────────────────────────────────────────────

val results = mutableListOf<ModelResult>()

for (model in MODELS) {
    val body = """{"model":"${model.id}","max_tokens":$MAX_TOKENS,"messages":[{"role":"user","content":"${escapeJson(PROMPT)}"}]}"""

    val t0 = System.currentTimeMillis()
    val resp = post(body)
    val elapsedMs = System.currentTimeMillis() - t0

    if (resp.statusCode() != 200) {
        System.err.println("Error: ${model.label} returned HTTP ${resp.statusCode()} — skipping.")
        results += ModelResult(model.label, null, elapsedMs, 0, 0, 0, 0.0)
        continue
    }

    val env = parseEnvelope(resp.body())

    if (env.stopReason == "refusal") {
        System.err.println("Warning: ${model.label} refused to answer — skipping.")
        results += ModelResult(model.label, null, elapsedMs, env.inputTokens, env.outputTokens, env.thinkingTokens, 0.0)
        continue
    }

    val cost = (env.inputTokens * model.inputPricePer1M + env.outputTokens * model.outputPricePer1M) / 1_000_000.0
    results += ModelResult(model.label, env.text, elapsedMs, env.inputTokens, env.outputTokens, env.thinkingTokens, cost)
}

// ─── Print per-model sections ────────────────────────────────────────────────

println()
for (r in results) {
    println("===== ${r.label} =====")
    println()
    println("Answer: ${r.answer ?: "[no response]"}")
    println()
    val thinkingNote = if (r.thinkingTokens > 0) "  (thinking: ${r.thinkingTokens})" else ""
    println("Time:   ${r.timeMs} ms")
    println("Tokens: ${r.inputTokens} in / ${r.outputTokens} out$thinkingNote")
    println("Cost:   $" + "%.6f".format(r.cost))
    println()
}

// ─── Summary table ───────────────────────────────────────────────────────────

val col1 = 22; val col2 = 12; val col3 = 14; val col4 = 12
val divider = "-".repeat(col1 + col2 + col3 + col4 + 3)

println("=" .repeat(col1 + col2 + col3 + col4 + 3))
println("SUMMARY")
println("=" .repeat(col1 + col2 + col3 + col4 + 3))
println(
    String.format("%-${col1}s %${col2}s %${col3}s %${col4}s",
        "Model", "Time (ms)", "Tokens (in+out)", "Cost")
)
println(divider)
for (r in results) {
    val totalTokens = "${r.inputTokens}+${r.outputTokens}"
    println(
        String.format("%-${col1}s %${col2}d %${col3}s %${col4}s",
            r.label, r.timeMs, totalTokens, "$" + "%.6f".format(r.cost))
    )
}
println(divider)
