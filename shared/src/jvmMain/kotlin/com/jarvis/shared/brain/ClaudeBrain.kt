package com.jarvis.shared.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.jarvis.shared.data.JarvisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Duration

/**
 * Jarvis's online brain: Claude via the official Anthropic SDK.
 *
 * "Smart mix": QUICK questions go to Claude Haiku 5.5 (fast, very cheap);
 * DEEP ones (night debrief, tutoring) go to Claude Opus 5.5 (smartest).
 * Each request is one self-contained message (context + recent conversation),
 * so switching models mid-conversation is always safe.
 */
class ClaudeBrain(
    private val apiKey: suspend () -> String?,
    private val quickModel: String = QUICK_MODEL,
    private val deepModel: String = DEEP_MODEL,
) : Brain {
    @Volatile
    private var cached: Pair<String, AnthropicClient>? = null

    override suspend fun reply(request: BrainRequest): BrainReply = withContext(Dispatchers.IO) {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BrainUnavailableException("no Claude API key is set")
        val deep = request.depth == Depth.DEEP

        val params = MessageCreateParams.builder()
            .model(if (deep) deepModel else quickModel)
            .maxTokens(if (deep) 16_000L else 4_000L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(Prompts.SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .outputConfig(
                OutputConfig.builder()
                    // Effort = how hard Claude thinks. Low keeps quick replies snappy.
                    .effort(if (deep) OutputConfig.Effort.MEDIUM else OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(replySchema).build())
                    .build(),
            )
            .addUserMessage(Prompts.buildUserMessage(request))
        if (deep) {
            // If a safety check declines on Opus, the server retries on its recommended fallback model.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val message: Message = try {
            clientFor(key).messages().create(params.build())
        } catch (e: UnauthorizedException) {
            throw BrainUnavailableException("the Claude API key was rejected", e)
        } catch (e: RateLimitException) {
            throw BrainUnavailableException("Claude is rate limited right now", e)
        } catch (e: AnthropicServiceException) {
            val detail = e.message.orEmpty()
            val reason = when {
                detail.contains("credit balance", ignoreCase = true) -> "your Claude credit has run out"
                e.statusCode() == 529 || e.statusCode() >= 500 -> "Claude's servers are busy"
                else -> "Claude returned an error (${e.statusCode()})"
            }
            throw BrainUnavailableException(reason, e)
        } catch (e: AnthropicIoException) {
            throw BrainUnavailableException("Claude couldn't be reached", e)
        }

        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            return@withContext BrainReply("I'm afraid I can't help with that one, ${request.context.addressAs}.")
        }
        val text = message.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }
        try {
            JarvisJson.decodeFromString(BrainReply.serializer(), text)
        } catch (e: Exception) {
            throw BrainUnavailableException("Claude's reply came back incomplete", e)
        }
    }

    private fun clientFor(key: String): AnthropicClient {
        cached?.let { (cachedKey, client) -> if (cachedKey == key) return client }
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(120))
            .maxRetries(1)
            .build()
        cached = key to client
        return client
    }

    companion object {
        const val QUICK_MODEL = "claude-haiku-5-5"
        const val DEEP_MODEL = "claude-opus-5-5"

        private val replySchema: JsonOutputFormat.Schema by lazy {
            val root = JarvisJson.parseToJsonElement(Prompts.REPLY_SCHEMA).jsonObject
            JsonOutputFormat.Schema.builder()
                .putAllAdditionalProperties(root.mapValues { (_, v) -> JsonValue.from(toJava(v)) })
                .build()
        }

        /** kotlinx JSON tree -> plain Java maps/lists, which the SDK's JsonValue understands. */
        private fun toJava(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonObject -> element.mapValues { (_, v) -> toJava(v) }
            is JsonArray -> element.map { toJava(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                else -> element.content.toDoubleOrNull()
            }
        }
    }
}
