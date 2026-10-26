package uk.tomlowndes.capture

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Thrown when sorting can't reach Claude right now; the dump stays in the queue for the next signal. */
class OfflineException(cause: Throwable) : Exception(cause)

/**
 * Claude refused the model id. The user sees [message]; the model can be changed in Settings.
 * A retired model hits this instead of failing with a raw API error.
 */
class ModelRejectedException(val model: String, cause: Throwable) : Exception(
    "Claude rejected the model \"$model\". It may have been retired — set a current model in Settings.",
    cause
)

/** One messages call. Tests pass a fake; the app uses [AnthropicSortTransport]. */
fun interface SortTransport {
    /** The assistant text, or throws [AnthropicIoException] / [AnthropicServiceException]. */
    fun complete(model: String, system: String, user: String, schema: JSONObject): String
}

/** Sends a brain dump to Claude and reads back tasks, ideas and a note ([Sorting] has the prompt and schema). */
object SortClient {
    fun sort(
        apiKey: String,
        dump: String,
        tags: List<String>,
        today: LocalDate = LocalDate.now(),
        model: String = Sorting.MODEL,
        transport: SortTransport = AnthropicSortTransport(apiKey),
    ): SortResult {
        val id = model.trim().ifEmpty { Sorting.MODEL }
        val text = try {
            transport.complete(id, Sorting.SYSTEM, Sorting.userMessage(dump, today, tags), Sorting.SCHEMA)
        } catch (e: AnthropicIoException) {
            throw OfflineException(e)
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 429 || e.statusCode() >= 500) throw OfflineException(e)
            if (modelRejected(e)) throw ModelRejectedException(id, e)
            throw e
        }
        return Sorting.parse(text)
    }

    /** 404, or a 400 whose body says the model id is the problem. Other 400s are left as they are. */
    internal fun modelRejected(e: AnthropicServiceException): Boolean {
        val type = runCatching { e.errorType().orElse(null)?.value() }.getOrNull()
        if (e.statusCode() == 404 || type == ErrorType.Value.NOT_FOUND_ERROR) return true
        if (e.statusCode() != 400) return false
        val detail = (e.message ?: "") + e.body()
        return detail.contains("model", ignoreCase = true)
    }
}

/**
 * The real call. [baseUrl] and [maxRetries] are for tests that point this at a local server;
 * the app leaves them at the defaults (api.anthropic.com, two retries).
 */
internal class AnthropicSortTransport(
    private val apiKey: String,
    private val baseUrl: String? = null,
    private val maxRetries: Int = 2,
) : SortTransport {
    override fun complete(model: String, system: String, user: String, schema: JSONObject): String {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .maxRetries(maxRetries)
            .build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .system(system)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schemaOf(schema)).build())
                    .build()
            )
            .addUserMessage(user)
            .build()
        val msg = try {
            client.messages().create(params)
        } finally {
            client.close()
        }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) error("Claude couldn't sort this one")
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    private fun schemaOf(schema: JSONObject): JsonOutputFormat.Schema {
        val b = JsonOutputFormat.Schema.builder()
        schema.keys().forEach { k -> b.putAdditionalProperty(k, JsonValue.from(plain(schema.get(k)))) }
        return b.build()
    }

    /** org.json values as plain maps and lists, which [JsonValue.from] takes. */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
