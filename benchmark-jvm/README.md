# benchmark-jvm

This module provides benchmarks of `opentelemetry-kotlin` using `kotlinx-benchmark`.
Common operations using the tracing/logging APIs are benchmarked on the JVM for:

1. `opentelemetry-kotlin` API, `implementation` (under the `kotlin` package)
2. `opentelemetry-kotlin` API, `opentelemetry-java` implementation (under the `compat` package)
3. `opentelemetry-java` API, `opentelemetry-java` implementation (under the `java` package)

Wherever possible the test case will attempt to invoke the same behavior across all implementations.
E.g. when creating a span the same parameters should be passed in. This will make result
comparison more meaningful.

Benchmarks can be run via `./gradlew jvmPerfBenchmark`

## OTLP memory profiling harness

The profiling harness sends deterministic batches of logs to a separate OTLP stub process. The
stub fully consumes every request, decompresses gzip-encoded bodies, and reports the actual content
encoding and sizes. It then responds with `503`, `503`, and `200` for each unique batch. Retry
responses include a configurable `Retry-After` header, leaving time to capture the client heap
between attempts.

Start the stub in the first terminal:

```sh
./gradlew :benchmark-jvm:runOtlpMemoryStub
```

Start the profiled client in the second terminal:

```sh
./gradlew :benchmark-jvm:runOtlpMemoryExperiment --args="label=current batches=8 batchSize=512 payloadCharacters=2048 payload=compressible"
```

The client first warms up the same Ktor/OkHttp instance that the exporter uses. It then generates
the log records, puts them in the processor queue, prints its PID, and waits without exporting.
Attach the profiler and start recording allocations, then press Enter in the client terminal to
export the queued batches. Wait until the stub reports `attempt=1` for every batch, then capture a
heap dump if needed:

```sh
jcmd <pid> GC.heap_dump /tmp/otel-current.hprof
```

Press Enter in the client terminal a second time to shut down the exporter and exit.

Client arguments:

- `endpoint`: OTLP base URL, default `http://localhost:4318`
- `batches`: number of concurrent export jobs, default `8`
- `batchSize`: log records per batch, default `512`
- `payloadCharacters`: body size of each log record, default `2048`
- `payload`: `compressible` or `pseudorandom`, default `compressible`
- `label`: experiment label used in output, default `current`

Stub arguments:

- `port`: HTTP port, default `4318`
- `statuses`: response sequence per unique batch, default `503,503,200`
- `retryAfterSeconds`: retry delay sent with retryable responses, default `60`
- `workers`: number of request-handling threads, default `64`
