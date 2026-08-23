import kotlinx.benchmark.gradle.benchmark
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

configure<AllOpenExtension> {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvmToolchain(11)
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
                implementation(project(":core"))
                implementation(project(":implementation"))
                implementation(project(":benchmark-fixtures"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(project.dependencies.platform(libs.opentelemetry.bom))
                implementation(libs.opentelemetry.api)
                implementation(libs.opentelemetry.sdk)
                implementation(project(":core"))
                implementation(project(":compat"))
                implementation(project(":implementation"))
                implementation(project(":benchmark-fixtures"))
                implementation(project(":java-typealiases"))
                implementation(project(":exporters-otlp"))
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines)
            }
        }
    }
    compilerOptions.optIn.add("io.opentelemetry.kotlin.ExperimentalApi")
}

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        // trade off between accuracy & speed
        register("perf") {
            warmups = 1
            iterations = 5
            mode = "avgt"
            outputTimeUnit = "ns"
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}

tasks.register<JavaExec>("runOtlpMemoryExperiment") {
    dependsOn("jvmMainClasses")
    val compilation = kotlin.targets.named("jvm").get().compilations.named("main").get()
    classpath(
        compilation.output.allOutputs,
        compilation.runtimeDependencyFiles
    )
    mainClass.set("io.opentelemetry.kotlin.benchmark.export.otlp.OtlpMemoryExperimentKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    standardInput = System.`in`
}

tasks.register<JavaExec>("runOtlpMemoryStub") {
    dependsOn("jvmMainClasses")
    val compilation = kotlin.targets.named("jvm").get().compilations.named("main").get()
    classpath(
        compilation.output.allOutputs,
        compilation.runtimeDependencyFiles
    )
    mainClass.set("io.opentelemetry.kotlin.benchmark.export.otlp.DeterministicOtlpStub")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    standardInput = System.`in`
}
