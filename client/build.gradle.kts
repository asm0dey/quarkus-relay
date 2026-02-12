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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":shared"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.testcontainers:testcontainers:1.19.8")
}

quarkus {
}
