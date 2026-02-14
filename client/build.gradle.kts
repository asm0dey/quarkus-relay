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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":shared"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("ch.qos.logback:logback-classic:1.5.31")
    testImplementation("org.glassfish.tyrus:tyrus-server:2.2.2")
    testImplementation("org.glassfish.tyrus:tyrus-container-grizzly-server:2.2.2")
}

quarkus {
}
