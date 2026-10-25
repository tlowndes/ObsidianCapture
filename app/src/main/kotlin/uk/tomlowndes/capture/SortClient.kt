package uk.tomlowndes.capture

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Thrown when sorting can't reach Claude right now; the dump stays in the queue for the next signal. */
class OfflineException(cause: Throwable) : Exception(cause)

/** Sends a brain dump to Claude Haiku and reads back tasks, ideas and a note ([Sorting] has the prompt and schema). */
object SortClient {
    fun sort(apiKey: String, dump: String, tags: List<String>, today: LocalDate = LocalDate.now()): SortResult {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        val params = MessageCreateParams.builder()
            .model(Sorting.MODEL)
            .maxTokens(4000L)
            .system(Sorting.SYSTEM)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema()).build())
                    .build()
            )
            .addUserMessage(Sorting.userMessage(dump, today, tags))
            .build()
        val msg = try {
            client.messages().create(params)
        } catch (e: AnthropicIoException) {
            throw OfflineException(e)
        } catch (e: AnthropicServiceException) {
            if (e.statusCode() == 429 || e.statusCode() >= 500) throw OfflineException(e)
            throw e
        } finally {
            client.close()
        }
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) error("Claude couldn't sort this one")
        val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return Sorting.parse(text)
    }

    private fun schema(): JsonOutputFormat.Schema {
        val b = JsonOutputFormat.Schema.builder()
        val s = Sorting.SCHEMA
        s.keys().forEach { k -> b.putAdditionalProperty(k, JsonValue.from(plain(s.get(k)))) }
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
