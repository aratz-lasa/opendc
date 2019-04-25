/*
 * MIT License
 *
 * Copyright (c) 2017 atlarge-research
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

plugins {
    kotlin("jvm") version "1.3.30" apply false
    id("org.jetbrains.dokka") version "0.9.18" apply false
    id("org.jlleitschuh.gradle.ktlint") version "7.4.0" apply false
}

allprojects {
    group = "com.atlarge.opendc"
    version = "2.0.0"


    extra["junitJupiterVersion"] = "5.4.2"
    extra["junitPlatformVersion"] = "1.4.2"
    extra["githubUrl"] = "https://github.com/atlarge-research/${rootProject.name}"
}

tasks.wrapper {
    gradleVersion = "5.1"
}

// Wait for children to evaluate so we can configure tasks that are dependant on children
project.evaluationDependsOnChildren()

apply {
    from("gradle/jacoco.gradle")
    from("gradle/dokka.gradle")
}
