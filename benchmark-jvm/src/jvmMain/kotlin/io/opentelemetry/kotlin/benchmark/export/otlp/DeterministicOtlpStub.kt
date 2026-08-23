package io.opentelemetry.kotlin.benchmark.export.otlp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * An OTLP/HTTP stub that returns a deterministic status sequence for each unique request body.
 */
object DeterministicOtlpStub {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = StubConfig.parse(args)
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val executor = Executors.newFixedThreadPool(config.workers)
        val server = HttpServer.create(InetSocketAddress(config.port), 0).apply {
            createContext("/") { exchange ->
                handleRequest(exchange, config, attempts)
            }
            this.executor = executor
            start()
        }

        println("Deterministic OTLP stub")
        println("PID: ${ProcessHandle.current().pid()}")
        println("Java: ${System.getProperty("java.version")}")
        println("Listening on http://localhost:${config.port}")
        println("Statuses per unique batch: ${config.statuses.joinToString()}")
        println("Retry-After: ${config.retryAfterSeconds}s")
        println("Press Enter to stop.")

        readlnOrNull()
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun handleRequest(
    exchange: HttpExchange,
    config: StubConfig,
    attempts: ConcurrentHashMap<String, AtomicInteger>,
) {
    try {
        if (exchange.requestURI.path == WARMUP_PATH) {
            val fingerprint = exchange.requestFingerprint()
            exchange.sendEmptyResponse(200)
            println(
                "$WARMUP_PATH status=200 encodedBytes=${fingerprint.encodedBytes} " +
                    "decodedBytes=${fingerprint.decodedBytes} encoding=${fingerprint.contentEncoding}"
            )
            return
        }

        if (exchange.requestURI.path !in OTLP_PATHS) {
            exchange.sendEmptyResponse(404)
            return
        }

        val fingerprint = exchange.requestFingerprint()
        val attempt = attempts.computeIfAbsent(fingerprint.hash) { AtomicInteger() }.incrementAndGet()
        val status = config.statuses.getOrElse(attempt - 1) { config.statuses.last() }

        if (status in RETRYABLE_STATUSES && config.retryAfterSeconds > 0) {
            exchange.responseHeaders.add("Retry-After", config.retryAfterSeconds.toString())
        }
        exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
        exchange.sendEmptyResponse(status)

        println(
            "${exchange.requestURI.path} batch=${fingerprint.hash.take(HASH_DISPLAY_LENGTH)} " +
                "attempt=$attempt status=$status encodedBytes=${fingerprint.encodedBytes} " +
                "protobufBytes=${fingerprint.decodedBytes} encoding=${fingerprint.contentEncoding}"
        )
    } catch (error: Throwable) {
        System.err.println("Failed to handle ${exchange.requestURI}: ${error.message}")
        runCatching { exchange.sendEmptyResponse(500) }
    } finally {
        exchange.close()
    }
}

private fun HttpExchange.requestFingerprint(): RequestFingerprint {
    val countedInput = CountingInputStream(requestBody)
    val contentEncoding = requestHeaders.getFirst("Content-Encoding").orEmpty()
    val decodedInput = if (contentEncoding.contains("gzip", ignoreCase = true)) {
        GZIPInputStream(countedInput)
    } else {
        countedInput
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var decodedBytes = 0L

    DigestInputStream(decodedInput, digest).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            decodedBytes += count
        }
    }

    return RequestFingerprint(
        hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        encodedBytes = countedInput.bytesRead,
        decodedBytes = decodedBytes,
        contentEncoding = contentEncoding.ifBlank { "identity" },
    )
}

private fun HttpExchange.sendEmptyResponse(status: Int) {
    sendResponseHeaders(status, -1)
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0
        private set

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) {
            bytesRead++
        }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { count ->
            if (count > 0) {
                bytesRead += count
            }
        }
}

private data class RequestFingerprint(
    val hash: String,
    val encodedBytes: Long,
    val decodedBytes: Long,
    val contentEncoding: String,
)

private data class StubConfig(
    val port: Int,
    val statuses: List<Int>,
    val retryAfterSeconds: Int,
    val workers: Int,
) {
    companion object {
        fun parse(args: Array<String>): StubConfig {
            val values = parseStubArguments(args)
            val port = values["port"]?.toInt() ?: 4318
            val statuses = values["statuses"]
                ?.split(',')
                ?.map(String::toInt)
                ?: listOf(503, 503, 200)
            val retryAfterSeconds = values["retryAfterSeconds"]?.toInt() ?: 60
            val workers = values["workers"]?.toInt() ?: 64

            require(port in 1..65_535) { "port must be between 1 and 65535" }
            require(statuses.isNotEmpty() && statuses.all { it in 100..599 }) {
                "statuses must be a comma-separated list of valid HTTP status codes"
            }
            require(retryAfterSeconds >= 0) { "retryAfterSeconds must not be negative" }
            require(workers > 0) { "workers must be greater than zero" }

            return StubConfig(port, statuses, retryAfterSeconds, workers)
        }
    }
}

private fun parseStubArguments(args: Array<String>): Map<String, String> = args.associate { argument ->
    val parts = argument.split('=', limit = 2)
    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        "Arguments must use key=value syntax, got '$argument'"
    }
    parts[0] to parts[1]
}

private val OTLP_PATHS = setOf("/v1/logs", "/v1/traces")
private val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
private const val WARMUP_PATH = "/warmup"
private const val HASH_DISPLAY_LENGTH = 12
