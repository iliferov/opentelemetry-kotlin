@file:OptIn(io.opentelemetry.kotlin.ExperimentalApi::class)

package io.opentelemetry.kotlin.benchmark.export.otlp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.compression.compress
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.logging.export.LogRecordProcessor
import io.opentelemetry.kotlin.logging.export.batchLogRecordProcessor
import io.opentelemetry.kotlin.logging.export.otlpHttpLogRecordExporter
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Generates deterministic log batches and keeps the JVM alive for manual heap inspection.
 *
 * This is a profiling harness, not a benchmark. Run [DeterministicOtlpStub] separately before
 * starting this process.
 */
fun main(args: Array<String>) {
    val httpClient = createExperimentHttpClient()
    try {
        runExperiment(args, httpClient)
    } finally {
        httpClient.close()
    }
    exitProcess(0)
}

private fun runExperiment(args: Array<String>, httpClient: HttpClient) {
    runBlocking {
        val config = ExperimentConfig.parse(args)
        lateinit var processor: LogRecordProcessor

        val openTelemetry = createOpenTelemetry {
            loggerProvider {
                export {
                    batchLogRecordProcessor(
                        exporter = otlpHttpLogRecordExporter(config.endpoint, httpClient),
                        maxQueueSize = config.recordCount,
                        scheduleDelayMs = ONE_HOUR_MS,
                        maxExportBatchSize = config.batchSize,
                    ).also { processor = it }
                }
            }
        }
        val logger = openTelemetry.loggerProvider.getLogger("otlp-memory-experiment")

        println("OTLP memory experiment '${config.label}'")
        println("PID: ${ProcessHandle.current().pid()}")
        println("Java: ${System.getProperty("java.version")}")
        println("Endpoint: ${config.endpoint}")
        println("Batches: ${config.batchCount}")
        println("Records per batch: ${config.batchSize}")
        println("Payload characters per record: ${config.payloadCharacters}")
        println("Payload kind: ${config.payloadKind.argumentValue}")
        print("Warming up the shared Ktor/OkHttp client... ")
        warmUpHttpClient(httpClient, config.endpoint)
        println("done")
        println("Generating ${config.recordCount} log records...")

        repeat(config.recordCount) { recordIndex ->
            logger.emit(
                body = createPayload(
                    recordIndex = recordIndex,
                    length = config.payloadCharacters,
                    kind = config.payloadKind,
                )
            )
        }

        println("Queue populated; no export has started.")
        println("Attach the profiler, start allocation recording, then press Enter to export.")

        if (readlnOrNull() == null) {
            println("Standard input closed; shutting down without exporting.")
            processor.shutdown()
            return@runBlocking
        }

        println("Forcing the queue to produce ${config.batchCount} batches...")
        println("forceFlush result: ${processor.forceFlush()}")
        println("Exports are asynchronous. Wait until the stub reports attempt 1 for every batch.")
        println("Then capture this process, for example:")
        println("  jcmd ${ProcessHandle.current().pid()} GC.heap_dump /tmp/otel-${config.label}.hprof")
        println("Press Enter to shut down the exporter and exit.")

        readlnOrNull()
        processor.shutdown()
    }
}

private fun createExperimentHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
    install(ContentNegotiation)
    install(ContentEncoding) {
        gzip()
        deflate()
    }
}

private suspend fun warmUpHttpClient(httpClient: HttpClient, endpoint: String) {
    val response = httpClient.post("$endpoint$WARMUP_PATH") {
        compress("gzip")
        contentType(ContentType.parse("application/x-protobuf"))
        setBody(ByteArray(WARMUP_BODY_SIZE))
    }
    check(response.status == HttpStatusCode.OK) {
        "HTTP client warmup failed with status ${response.status}"
    }
}

private data class ExperimentConfig(
    val endpoint: String,
    val batchCount: Int,
    val batchSize: Int,
    val payloadCharacters: Int,
    val payloadKind: PayloadKind,
    val label: String,
) {
    val recordCount: Int = Math.multiplyExact(batchCount, batchSize)

    companion object {
        fun parse(args: Array<String>): ExperimentConfig {
            val values = parseArguments(args)
            val endpoint = values["endpoint"]?.trimEnd('/') ?: "http://localhost:4318"
            val batchCount = values["batches"]?.toInt() ?: 8
            val batchSize = values["batchSize"]?.toInt() ?: 512
            val payloadCharacters = values["payloadCharacters"]?.toInt() ?: 2_048
            val payloadKind = PayloadKind.parse(values["payload"] ?: "compressible")
            val label = values["label"] ?: "current"

            require(endpoint.isNotBlank()) { "endpoint must not be blank" }
            require(batchCount > 0) { "batches must be greater than zero" }
            require(batchSize > 0) { "batchSize must be greater than zero" }
            require(payloadCharacters >= MIN_PAYLOAD_CHARACTERS) {
                "payloadCharacters must be at least $MIN_PAYLOAD_CHARACTERS"
            }
            require(label.matches(Regex("[A-Za-z0-9._-]+"))) {
                "label may contain only letters, digits, dots, underscores, and hyphens"
            }

            return ExperimentConfig(
                endpoint = endpoint,
                batchCount = batchCount,
                batchSize = batchSize,
                payloadCharacters = payloadCharacters,
                payloadKind = payloadKind,
                label = label,
            )
        }
    }
}

private enum class PayloadKind(val argumentValue: String) {
    COMPRESSIBLE("compressible"),
    PSEUDORANDOM("pseudorandom");

    companion object {
        fun parse(value: String): PayloadKind = entries.firstOrNull { it.argumentValue == value }
            ?: error("payload must be one of: ${entries.joinToString { it.argumentValue }}")
    }
}

private fun createPayload(recordIndex: Int, length: Int, kind: PayloadKind): String {
    val prefix = "record=$recordIndex;"
    return buildString(length) {
        append(prefix)
        var state = recordIndex + 1
        while (this.length < length) {
            when (kind) {
                PayloadKind.COMPRESSIBLE -> append('x')
                PayloadKind.PSEUDORANDOM -> {
                    state = state * RANDOM_MULTIPLIER + RANDOM_INCREMENT
                    append(
                        (PRINTABLE_ASCII_START + (state ushr RANDOM_SHIFT) % PRINTABLE_ASCII_COUNT)
                            .toChar()
                    )
                }
            }
        }
    }
}

private fun parseArguments(args: Array<String>): Map<String, String> = args.associate { argument ->
    val parts = argument.split('=', limit = 2)
    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        "Arguments must use key=value syntax, got '$argument'"
    }
    parts[0] to parts[1]
}

private const val ONE_HOUR_MS = 3_600_000L
private const val REQUEST_TIMEOUT_MS = 10_000L
private const val WARMUP_PATH = "/warmup"
private const val WARMUP_BODY_SIZE = 8 * 1024
private const val MIN_PAYLOAD_CHARACTERS = 32
private const val RANDOM_MULTIPLIER = 1_664_525
private const val RANDOM_INCREMENT = 1_013_904_223
private const val RANDOM_SHIFT = 24
private const val PRINTABLE_ASCII_START = 33
private const val PRINTABLE_ASCII_COUNT = 94
