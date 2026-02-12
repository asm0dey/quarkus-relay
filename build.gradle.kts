plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.allopen") version "2.3.0"
    id("io.quarkus") version "3.31.3" apply false
    idea
}

repositories {
    mavenCentral()
    mavenLocal()
}

allprojects {
    group = "org.relay"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.allopen")

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        testImplementation(kotlin("test"))
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    allOpen {
        annotation("jakarta.ws.rs.Path")
        annotation("jakarta.enterprise.context.ApplicationScoped")
        annotation("jakarta.ws.rs.Produces")
        annotation("jakarta.ws.rs.Consumes")
        annotation("io.quarkus.test.junit.QuarkusTest")
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
            javaParameters = true
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
