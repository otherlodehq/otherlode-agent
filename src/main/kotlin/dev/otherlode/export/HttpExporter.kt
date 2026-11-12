package dev.otherlode.export

import dev.otherlode.bootstrap.OtherlodeLoggers
import dev.otherlode.instrumentation.BootstrapHolder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Sends every payload shape to a receiver at [endpoint] over plain HTTP/1.1, with up to
 * [maxAttempts] attempts and capped exponential backoff.
 *
 * It retries an attempt that throws, unless [authToken] is not a valid header value, which fails at
 * once. It retries a 5xx, a 408 or a 429 too. Any other 4xx fails at once with an
 * [ExportFailedException] whose [ExportFailedException.refused] is true. Only a 202 counts as stored.
 * Any other 2xx fails at once without a retry and without being a refusal. A 202 may carry
 * `Otherlode-Notice` headers, which [notices] logs. A `Retry-After` of at most [maxRetryAfter]
 * replaces the backoff for that wait. A longer one ends the send with the failure an exhausted
 * retry gives. If every attempt fails, the last failure propagates.
 *
 * Exporters given the same [lazyClient] share one client. [requestTimeout] bounds each attempt's
 * whole exchange, response body included.
 */
class HttpExporter(
    private val endpoint: String,
    private val authToken: String? = null,
    httpClient: Lazy<HttpClient> = lazyClient(),
    private val maxAttempts: Int = 5,
    private val initialBackoff: Duration = Duration.ofMillis(200),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
    private val requestTimeout: Duration = DEFAULT_TIMEOUT,
    private val maxRetryAfter: Duration = Duration.ofSeconds(2),
    private val notices: IngestNotices = IngestNotices(),
) : Exporter {
    private val client: HttpClient by httpClient

    override fun exportDeltaBatch(batch: DeltaBatch) {
        post("$endpoint/v1/otherlode/deltas", ProtoPayloadCodec.encode(batch))
    }

    override fun exportManifest(manifest: ProbeManifest) {
        post("$endpoint/v1/otherlode/manifest", ProtoPayloadCodec.encode(manifest))
    }

    override fun exportStaticBaseline(baseline: StaticBaseline) {
        post("$endpoint/v1/otherlode/static-baseline", ProtoPayloadCodec.encode(baseline))
    }

    override fun exportClassListing(listing: ClassListing) {
        post("$endpoint/v1/otherlode/class-listing", ProtoPayloadCodec.encode(listing))
    }

    private fun post(
        uri: String,
        body: ByteArray,
    ) {
        var backoff = initialBackoff
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            var retryAfter: Duration? = null
            var noticeValues: List<String> = emptyList()
            val status =
                try {
                    val requestBuilder =
                        HttpRequest
                            .newBuilder(URI.create(uri))
                            .timeout(requestTimeout)
                            .header("Content-Type", "application/x-protobuf")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    if (authToken != null) {
                        try {
                            requestBuilder.header("Authorization", "Bearer $authToken")
                        } catch (e: IllegalArgumentException) {
                            throw HeaderRefusedException("otherlode: the Authorization header value is not valid, so nothing can be sent")
                        }
                    }
                    val request = requestBuilder.build()
                    val response = sendWithin(request)
                    retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null))
                    noticeValues = response.headers().allValues("Otherlode-Notice")
                    response.statusCode()
                } catch (e: HeaderRefusedException) {
                    throw ExportFailedException(e.message.orEmpty())
                } catch (e: Exception) {
                    lastError = e
                    null
                }
            if (status != null) {
                if (status == 202) {
                    notices.report(noticeValues)
                    return
                }
                if (status in 200..299) {
                    throw ExportFailedException("otherlode: $uri answered $status, not 202, so the payload counts as not stored", status)
                }
                if (!isRetryable(status)) {
                    throw ExportFailedException("otherlode: unexpected status $status from $uri", status, refused = status in 400..499)
                }
                lastError = ExportFailedException("otherlode: unexpected status $status from $uri", status)
                if (retryAfter != null && retryAfter > maxRetryAfter) break
            }
            if (attempt < maxAttempts) {
                val wait = retryAfter?.takeIf { status != null && it <= maxRetryAfter }
                if (wait != null) {
                    Thread.sleep(wait.toMillis())
                } else {
                    Thread.sleep(backoff.toMillis())
                }
                backoff = minOf(backoff.multipliedBy(2), maxBackoff)
            }
        }
        throw lastError ?: ExportFailedException("otherlode: export to $uri failed")
    }

    private fun sendWithin(request: HttpRequest): HttpResponse<Void> {
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        try {
            return future.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw HttpTimeoutException("otherlode: the exchange took longer than $requestTimeout")
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: e
        } catch (e: InterruptedException) {
            future.cancel(true)
            throw e
        }
    }

    private fun isRetryable(status: Int): Boolean = isRetryableStatus(status)

    companion object {
        /** A client built on first use, pinned to HTTP/1.1, with a 10-second connect timeout. */
        fun lazyClient(): Lazy<HttpClient> =
            lazy {
                if (BootstrapHolder.isInstalled()) OtherlodeLoggers.awaitGate()
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(DEFAULT_TIMEOUT)
                    .build()
            }

        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

        internal fun parseRetryAfter(value: String?): Duration? {
            val text = value?.trim()
            if (text.isNullOrEmpty()) return null
            if (text.all { it in '0'..'9' }) {
                val seconds = text.toLongOrNull() ?: return Duration.ofSeconds(Long.MAX_VALUE / 1000)
                return Duration.ofSeconds(minOf(seconds, Long.MAX_VALUE / 1000))
            }
            val date =
                try {
                    ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                } catch (e: DateTimeParseException) {
                    return null
                }
            val wait = Duration.between(ZonedDateTime.now(), date)
            return wait.takeIf { !it.isNegative && !it.isZero }
        }

        private fun isRetryableStatus(status: Int): Boolean = status >= 500 || status == 408 || status == 429
    }
}

/**
 * A failed send. [statusCode] is null when the failure was not an HTTP response. [refused] is true
 * when the receiver answered with a 4xx that is not retried, and false for every other failure.
 */
class ExportFailedException(
    message: String,
    val statusCode: Int? = null,
    val refused: Boolean = false,
) : RuntimeException(message)

private class HeaderRefusedException(
    message: String,
) : RuntimeException(message, null, false, false)
