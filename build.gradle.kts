plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.graalvm.buildtools.native") version "0.10.5"
    application
}

group = "com.zcode"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.zcode.qwen35gw.MainKt") }

tasks.test { useJUnitPlatform() }

graalvmNative {
    binaries {
        named("main") {
            imageName.set("qwen35-gw")
            mainClass.set("com.zcode.qwen35gw.MainKt")
            buildArgs.add("--no-fallback")
        }
    }
}