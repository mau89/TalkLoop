This is a Kotlin Multiplatform project targeting Android, iOS, Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- CLI (разговорный прогон с LLM): положите ключ в `secrets.properties` (см. `secrets.properties.example`)
  или `export ANTHROPIC_API_KEY=...`, затем `./gradlew :cli:run -q --console=plain`
- CLI-демо Дня 2 (первый подход, до переезда в UI): `./gradlew :cli:day2 -q --console=plain`
- Android app: `./gradlew :app:androidApp:assembleDebug` — ключ берётся из того же `secrets.properties`
  и вкомпилируется в APK, поэтому собранное приложение никому не раздавать; для этого понадобится
  прокси через `:server`
- Server: `./gradlew :server:run`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.
  Ключ подставляется в сгенерированный `Secrets.kt` из того же `secrets.properties`,
  что и на Android, и так же вкомпилируется в сборку — раздавать её нельзя.

### Вкладка «Формат» — детерминированность ответа

В приложении две вкладки: «Разговор» (репетитор английского) и «Формат» —
лаборатория, на которой отрабатывается управление форматом ответа.

Роль модели там одна во всех режимах: **автомобильный эксперт**, который отвечает
только про автомобили, а на «какая сегодня погода» коротко отказывает. Меняется
только то, чем задан формат. Один и тот же вопрос уходит 1, 3 или 5 раз подряд —
так видно, стабилен формат или совпал случайно.

| Режим | Чем задан формат | Что получается |
|---|---|---|
| Без ограничений | ничем | обычный текст, парсить нечем |
| Строгие блоки, текстом | системный промпт | обычно соблюдается, гарантии нет |
| JSON просьбой | системный промпт | ломается на markdown-обёртке и преамбуле |
| JSON схемой | `output_config.format` | схему проверяет API |

Схема разбивает машину на шесть фиксированных узлов — двигатель, трансмиссия,
подвеска и рулевое, тормоза, кузов и салон, электроника. Набор зашит в схему
обязательными полями, поэтому любая машина описывается одинаково и две модели
можно сравнивать построчно.

Отказ встроен в ту же схему полем `answerable`: на вопрос не про автомобили
приходит `answerable: false` с причиной, а блок узлов отсутствует вовсе. Так отказ
остаётся машиночитаемым и не ломает формат — под жёсткой схемой модель физически
не может ответить свободным текстом.

Длина настраивается двумя независимыми регуляторами, потому что рычаги разные:
`max_tokens` (100…2000) режет вслепую — ответ обрывается на полуслове и дальше
непригоден, что бы ни показала проверка формата; лимит словами (20…200) уходит
просьбой в системный промпт, и модель округляет его в свою пользу.

Стоп-слова вписываются вручную, через запятую. Это страховка от лишних токенов:
генерация обрывается ровно на слове, как только модель его напишет, само слово в
текст не попадает, а срабатывание видно в `stop_reason` (`stop_sequence` вместо
`end_turn`) и в поле `stop_sequence` — какое именно слово сработало.

Типичный случай — вежливый хвост, за который платишь: поставь `Если у тебя` или
`В заключение`, и предложение помощи в конце ответа просто не будет сгенерировано.

Отдельный тумблер «Просить дописать стоп-слово» превращает его из страховки в
маркер конца: слово добавляется в системный промпт. Под JSON-схемой такой просьбы
не даём — схема запрещает текст вокруг JSON, поэтому слово там не сработает
никогда (сами стоп-слова всё равно уходят в запрос, чтобы это было видно).

Прогон можно оборвать: пока идёт батч, кнопка становится «Стоп», а поля и
переключатели остаются живыми — можно готовить следующий запрос, не дожидаясь
конца текущего. Остановка отменяет корутину, ktor рвёт HTTP-соединение, уже
собранные прогоны остаются, и вердикт пишет «остановлено вручную: N из M».
Оставшиеся запросы не уходят вовсе — на них деньги не тратятся.

Схема, промпты и проверки формата живут в
[ResponseFormat.kt](core/src/commonMain/kotlin/com/mau89/talkloop/llm/ResponseFormat.kt)
и покрыты тестами без обращения к сети.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Core tests (формат ответа): `./gradlew :core:jvmTest` (те же тесты идут и на iOS:
  `./gradlew :core:iosSimulatorArm64Test`)
- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Server tests: `./gradlew :server:test`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…