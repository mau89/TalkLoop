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

// День 2: сравнение ответа без ограничений и с ограничениями формата.
tasks.register<JavaExec>("day2") {
    group = "application"
    description = "День 2: один и тот же запрос без ограничений и с ограничениями формата"
    mainClass = "com.mau89.talkloop.cli.Day2Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir  // secrets.properties лежит в корне репозитория
}

// День 3: одна задача — четыре способа рассуждения.
tasks.register<JavaExec>("day3") {
    group = "application"
    description = "День 3: одна задача четырьмя способами рассуждения"
    mainClass = "com.mau89.talkloop.cli.Day3Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir  // secrets.properties лежит в корне репозитория
}
