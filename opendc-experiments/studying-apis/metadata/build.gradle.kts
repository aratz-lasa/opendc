import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * Copyright (c) 2019 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

description = "Experiments for the Explicit Metadata extension"

/* Build configuration */
plugins {
    `experiment-conventions`
    kotlin("jvm")
}

dependencies {
    api(projects.opendcHarness.opendcHarnessApi)
    api(projects.opendcCompute.opendcComputeWorkload)

    implementation(projects.opendcTrace.opendcTraceAzure)
    implementation(projects.opendcTrace.opendcTraceWtf)
    implementation(projects.opendcCommon)

    implementation(projects.opendcExperiments.opendcExperimentsCapelin)
    implementation(projects.opendcWeb.opendcWebRunner)
    implementation(projects.opendcSimulator.opendcSimulatorCore)
    implementation(projects.opendcSimulator.opendcSimulatorCompute)
    implementation(projects.opendcCompute.opendcComputeSimulator)
    implementation(projects.opendcTelemetry.opendcTelemetrySdk)
    implementation(projects.opendcTelemetry.opendcTelemetryCompute)

    implementation(libs.config)
    implementation(libs.kotlin.logging)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.csv)
    implementation(kotlin("reflect"))
    implementation(libs.opentelemetry.semconv)

    runtimeOnly(projects.opendcTrace.opendcTraceOpendc)

    testImplementation(libs.log4j.slf4j)

    api(projects.opendcTrace.opendcTraceApi)

    implementation(projects.opendcTrace.opendcTraceParquet)
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
