plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "com.mau89.talkloop"
version = "1.0.0"
application {
    mainClass = "com.mau89.talkloop.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.mcp.kotlin.server)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientCio)
    testImplementation(libs.mcp.kotlin.client)
    testImplementation(libs.kotlin.testJunit)
}
