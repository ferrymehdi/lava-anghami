plugins {
    java
    alias(libs.plugins.lavalink)
    kotlin("jvm")
    id("maven-publish")
}

group = "org.ferrymehdi"
version = "0.0.1-SNAPSHOT"

lavalinkPlugin {
    name = "lava-anghami"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

dependencies {
    compileOnly("dev.arbjerg:lavaplayer:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation(kotlin("stdlib-jdk8"))


    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    implementation("com.goterl:lazysodium-java:5.1.4")
    implementation("net.java.dev.jna:jna:5.13.0")
}
repositories {
    mavenCentral()

    maven (url = "https://maven.lavalink.dev/releases")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}