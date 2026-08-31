plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "com.mau89.talkloop"
version = "1.0.0"

application {
    mainClass = "com.mau89.talkloop.cli.MainKt"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.anthropic.java)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`          // без этого CLI не читает ввод из терминала
    workingDir = rootProject.projectDir  // secrets.properties лежит в корне репозитория
}
