plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
}
