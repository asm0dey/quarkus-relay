plugins {
    id("io.quarkus")
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-websockets")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":shared"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.testcontainers:testcontainers:1.19.8")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("ch.qos.logback:logback-classic:1.5.13")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("org.glassfish.tyrus:tyrus-server:2.1.5")
    testImplementation("org.glassfish.tyrus:tyrus-container-grizzly-server:2.2.2")
}

quarkus {
}
