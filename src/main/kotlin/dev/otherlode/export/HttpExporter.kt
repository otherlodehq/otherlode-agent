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
 * Sends every payload shape to a receiver at [endpoint] over plain HTTP/1.1.
 * This uses the JDK's built-in [HttpClient]. It avoids a shaded gRPC/Netty
 * dependency.
 *
 * Each send makes up to [maxAttempts] attempts, with capped exponential
 * backoff, but only for failures that a retry can plausibly fix: a
 * connection or timeout error, a 5xx, a 408, or a 429. Any other 4xx fails
 * at once. The receiver has already read the bytes and rejected them, so
 * resending the same bytes a moment later cannot change its answer; it only
 * burns the flush budget. Such a 4xx is a refusal, and the
 * [ExportFailedException] it throws says so ([ExportFailedException.refused]).
 * Only a 202 counts as stored. Any other 2xx ends the send at once, without a retry, with an
 * [ExportFailedException] that is not a refusal, so the payload is sent again at the next flush.
 * A 202 may carry `Otherlode-Notice` headers, which [notices] logs. The response body is discarded.
 * A 408, 429 or 5xx may carry a `Retry-After` header, in seconds or as an HTTP date. A value of at
 * most [maxRetryAfter] replaces the backoff for that wait. A longer value ends the send at once with
 * the same failure an exhausted retry gives, so the next flush resends. A missing, malformed or past
 * value leaves the backoff in place.
 * If every attempt fails, the last failure propagates to the caller; a retryable status that
 * failed every attempt is not a refusal. The caller is expected to be [ExportScheduler]. It
 * treats the exception as a signal to leave the registry baseline untouched, and retries the
 * whole delta on the next flush.
 *
 * The client is built on the first send, on whichever thread sends, not when the exporter is
 * constructed: building a JDK [HttpClient] initialises the default `SSLContext` even for an `http`
 * URL, which is a cost the `premain` thread should not pay. Exporters given the same [lazyClient]
 * share one client. The client's connect timeout and each request's [requestTimeout] are both
 * bounded by default. Without a timeout, a receiver that accepts a
 * connection but never responds would block a send indefinitely. This can
 * happen if the receiver is wedged, or if the network silently drops
 * packets. An indefinite block would stall the [ExportScheduler] send it
 * runs on, and the flush waiting for it, liveness heartbeat included,
 * instead of failing into the retry/backoff path above.
 *
 * The receiver may require a bearer token, passed as [authToken]. A 401 or 403 fails that send at
 * once, as a refusal. The scheduler offers the payload again on the next flush, so a rotated token
 * heals without a restart.
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

    /**
     * Sends [request] and waits at most [requestTimeout] for the whole exchange, response body
     * included. The JDK request timeout covers only the wait for the response headers. A send that
     * passes the bound is cancelled and fails with [HttpTimeoutException], so it is never a success.
     */
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
        /**
         * A client built on first use, with the default connect timeout; pass one to several exporters to
         * share it. It is pinned to HTTP/1.1: the JDK client's default is HTTP/2, which on a plain `http`
         * URL sends an `Upgrade: h2c` header on every connection, and some proxies mishandle that on a POST.
         */
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

        /** The wait a `Retry-After` value asks for, or null when it is missing, malformed or already past. */
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

        /** 408 and 429 are the two 4xx codes that describe the server's state at that moment, not the request itself. */
        private fun isRetryableStatus(status: Int): Boolean = status >= 500 || status == 408 || status == 429
    }
}

/**
 * [statusCode] is null when the failure was not an HTTP response at all. A send whose every attempt
 * threw without a response rethrows that exception instead, so a caller sees a connection or timeout
 * error as itself, not as this type.
 * [refused] is true when the receiver answered with a 4xx the exporter does not retry, which is a
 * refusal of that one request; it is false for every other failure, a retryable status that failed
 * on every attempt included.
 */
class ExportFailedException(
    message: String,
    val statusCode: Int? = null,
    val refused: Boolean = false,
) : RuntimeException(message)

private class HeaderRefusedException(
    message: String,
) : RuntimeException(message, null, false, false)
