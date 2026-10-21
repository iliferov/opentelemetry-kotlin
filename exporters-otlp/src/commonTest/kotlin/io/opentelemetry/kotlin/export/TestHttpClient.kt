package io.opentelemetry.kotlin.export

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.util.ContentEncoder
import io.ktor.util.GZipEncoder
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestDispatcher
import kotlin.coroutines.CoroutineContext

internal fun createTestHttpClient(
    requestTimeoutMs: Long,
    engine: MockEngine,
    dispatcher: TestDispatcher,
): HttpClient {
    val testGzip = object : ContentEncoder by GZipEncoder {
        override fun encode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
            // Keep gzip on the test scheduler without replacing the request Job.
            GZipEncoder.encode(source, coroutineContext + dispatcher)
    }
    return createDefaultHttpClient(requestTimeoutMs, engine).use {
        it.config {
            install(ContentEncoding) {
                customEncoder(testGzip)
            }
        }
    }
}
