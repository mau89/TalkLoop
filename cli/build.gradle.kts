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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.clientCio)
    implementation(libs.mcp.kotlin.client)
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

// День 4: один запрос — три температуры.
tasks.register<JavaExec>("day4") {
    group = "application"
    description = "День 4: один запрос с temperature 0, 0.7 и 1.2"
    mainClass = "com.mau89.talkloop.cli.Day4Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir  // secrets.properties лежит в корне репозитория
}

// День 5: один запрос — слабая, средняя и сильная модель.
tasks.register<JavaExec>("day5") {
    group = "application"
    description = "День 5: один запрос на Haiku, Sonnet и Opus"
    mainClass = "com.mau89.talkloop.cli.Day5Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir  // secrets.properties лежит в корне репозитория
}

// День 10: один сценарий сбора ТЗ с тремя стратегиями контекста.
tasks.register<JavaExec>("day10") {
    group = "application"
    description = "День 10: Sliding Window, Sticky Facts и Branching на одном сценарии"
    mainClass = "com.mau89.talkloop.cli.Day10Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 11: явные краткосрочная, рабочая и долговременная память.
tasks.register<JavaExec>("day11") {
    group = "application"
    description = "День 11: три слоя памяти и переход между задачами"
    mainClass = "com.mau89.talkloop.cli.Day11Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 12: один запрос для двух профилей и автоматическое повторное применение настроек.
tasks.register<JavaExec>("day12") {
    group = "application"
    description = "День 12: персонализация ответов для разных профилей"
    mainClass = "com.mau89.talkloop.cli.Day12Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 13: конечный автомат задачи, пауза и продолжение после перезапуска.
tasks.register<JavaExec>("day13") {
    group = "application"
    description = "День 13: состояние задачи, пауза и точное продолжение"
    mainClass = "com.mau89.talkloop.cli.Day13Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 14: отдельные инварианты, явная проверка и объяснимый отказ.
tasks.register<JavaExec>("day14") {
    group = "application"
    description = "День 14: инварианты и конфликтный запрос без вызова LLM"
    mainClass = "com.mau89.talkloop.cli.Day14Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 16: подключение к публичному MCP-серверу и запрос tools/list.
tasks.register<JavaExec>("day16") {
    group = "application"
    description = "День 16: подключиться к MCP и вывести доступные инструменты"
    mainClass = "com.mau89.talkloop.cli.Day16Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

// День 21: локальный Python/ONNX пайплайн с многоязычной E5.
tasks.register<JavaExec>("day21") {
    group = "application"
    description = "День 21: индексация русской кулинарной книги и сравнение chunking"
    mainClass = "com.mau89.talkloop.cli.Day21Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("day22") {
    group = "application"
    description = "День 22: первый RAG-запрос и сравнение на 10 вопросах"
    mainClass = "com.mau89.talkloop.cli.Day22Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("day23") {
    group = "application"
    description = "День 23: rewrite, фильтр сходства и сравнение двух режимов RAG"
    mainClass = "com.mau89.talkloop.cli.Day23Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("day24") {
    group = "application"
    description = "День 24: проверяемые цитаты, источники и режим не знаю"
    mainClass = "com.mau89.talkloop.cli.Day24Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("day25") {
    group = "application"
    description = "День 25: постоянный RAG-чат с памятью задачи и двумя длинными сценариями"
    mainClass = "com.mau89.talkloop.cli.Day25Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.register<JavaExec>("day26") {
    group = "application"
    description = "День 26: три запроса через агента к локальной модели Ollama без ключа"
    mainClass = "com.mau89.talkloop.cli.Day26Kt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
