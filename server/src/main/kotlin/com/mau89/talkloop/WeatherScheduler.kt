package com.mau89.talkloop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private const val MIN_INTERVAL_MINUTES = 1
private const val MAX_INTERVAL_MINUTES = 24 * 60
private const val MAX_SAMPLES_PER_JOB = 1_000

@Serializable
data class ScheduledWeatherJob(
    val id: String,
    val city: String,
    val intervalMinutes: Int,
    val active: Boolean,
    val createdAtEpochMs: Long,
    val nextRunAtEpochMs: Long,
    val lastRunAtEpochMs: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class WeatherMeasurement(
    val jobId: String,
    val collectedAtEpochMs: Long,
    val weather: WeatherSnapshot,
)

@Serializable
data class WeatherSchedulerData(
    val jobs: List<ScheduledWeatherJob> = emptyList(),
    val measurements: List<WeatherMeasurement> = emptyList(),
)

data class WeatherSummary(
    val job: ScheduledWeatherJob,
    val samples: Int,
    val firstCollectedAtEpochMs: Long,
    val lastCollectedAtEpochMs: Long,
    val minimumTemperatureC: Double,
    val averageTemperatureC: Double,
    val maximumTemperatureC: Double,
    val averageHumidityPercent: Double,
    val averageWindSpeedKmh: Double,
    val latest: WeatherSnapshot,
)

fun interface WeatherSchedulerStore {
    fun load(): WeatherSchedulerData

    fun save(data: WeatherSchedulerData) = Unit
}

class JsonWeatherSchedulerStore(
    private val file: Path,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
) : WeatherSchedulerStore {
    override fun load(): WeatherSchedulerData {
        if (!Files.exists(file)) return WeatherSchedulerData()
        return runCatching {
            json.decodeFromString<WeatherSchedulerData>(Files.readString(file))
        }.getOrElse { WeatherSchedulerData() }
    }

    override fun save(data: WeatherSchedulerData) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(data))
        runCatching {
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class InMemoryWeatherSchedulerStore(
    initial: WeatherSchedulerData = WeatherSchedulerData(),
) : WeatherSchedulerStore {
    var data: WeatherSchedulerData = initial
        private set

    override fun load(): WeatherSchedulerData = data

    override fun save(data: WeatherSchedulerData) {
        this.data = data
    }
}

/** Часы вынесены в интерфейс, чтобы периодический запуск проверялся без ожидания минут. */
fun interface SchedulerClock {
    fun nowEpochMs(): Long
}

/**
 * Фоновый планировщик живёт вместе с сервером: восстанавливает JSON, запускает
 * просроченные задания и сохраняет каждый полученный замер.
 */
class WeatherSummaryScheduler(
    private val weatherApi: WeatherApi,
    private val store: WeatherSchedulerStore,
    private val clock: SchedulerClock = SchedulerClock(System::currentTimeMillis),
    private val pollIntervalMs: Long = 1_000,
) : AutoCloseable {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state = store.load()
    private var schedulerJob: Job? = null

    fun start() {
        if (schedulerJob != null) return
        schedulerJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                runDueJobs()
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun schedule(city: String, intervalMinutes: Int): ScheduledWeatherJob {
        val normalizedCity = city.trim()
        require(normalizedCity.length >= 2) { "Укажите название города минимум из двух символов" }
        require(intervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
            "Интервал должен быть от 1 до 1440 минут"
        }

        val now = clock.nowEpochMs()
        // Активное задание можно перенастроить без потери накопленных данных.
        // После остановки новый запуск создаёт новый период и новый счётчик.
        val existingActive = mutex.withLock {
            state.jobs.lastOrNull {
                it.active && it.city.equals(normalizedCity, ignoreCase = true)
            }
        }
        val job = ScheduledWeatherJob(
            id = existingActive?.id ?: UUID.randomUUID().toString(),
            city = normalizedCity,
            intervalMinutes = intervalMinutes,
            active = true,
            createdAtEpochMs = existingActive?.createdAtEpochMs ?: now,
            // Сразу резервируем следующий запуск, чтобы фоновый цикл не начал
            // тот же первый замер одновременно с schedule().
            nextRunAtEpochMs = now + intervalMinutes * 60_000L,
            lastRunAtEpochMs = existingActive?.lastRunAtEpochMs,
        )

        mutex.withLock {
            state = state.copy(jobs = state.jobs.filterNot { it.id == job.id } + job)
            persistLocked()
        }
        collect(job.id)
        return requireJob(job.id)
    }

    suspend fun cancel(city: String?): ScheduledWeatherJob {
        val job = resolveJob(city, requireActive = true)
        val cancelled = job.copy(active = false, nextRunAtEpochMs = job.nextRunAtEpochMs)
        mutex.withLock {
            state = state.copy(jobs = state.jobs.map { if (it.id == job.id) cancelled else it })
            persistLocked()
        }
        return cancelled
    }

    suspend fun summary(city: String?): WeatherSummary {
        val job = resolveJob(city, requireActive = false)
        val measurements = mutex.withLock {
            state.measurements.filter { it.jobId == job.id }.sortedBy { it.collectedAtEpochMs }
        }
        require(measurements.isNotEmpty()) {
            "Для города «${job.city}» ещё нет сохранённых замеров"
        }
        val latest = measurements.last()
        return WeatherSummary(
            job = job,
            samples = measurements.size,
            firstCollectedAtEpochMs = measurements.first().collectedAtEpochMs,
            lastCollectedAtEpochMs = latest.collectedAtEpochMs,
            minimumTemperatureC = measurements.minOf { it.weather.temperatureC },
            averageTemperatureC = measurements.map { it.weather.temperatureC }.average(),
            maximumTemperatureC = measurements.maxOf { it.weather.temperatureC },
            averageHumidityPercent = measurements.map { it.weather.humidityPercent }.average(),
            averageWindSpeedKmh = measurements.map { it.weather.windSpeedKmh }.average(),
            latest = latest.weather,
        )
    }

    suspend fun latestActiveJob(): ScheduledWeatherJob? = mutex.withLock {
        state.jobs.filter(ScheduledWeatherJob::active).maxByOrNull { it.createdAtEpochMs }
    }

    suspend fun runDueJobs() {
        val now = clock.nowEpochMs()
        val dueJobIds = mutex.withLock {
            state.jobs.filter { it.active && it.nextRunAtEpochMs <= now }.map { it.id }
        }
        dueJobIds.forEach { collect(it) }
    }

    private suspend fun collect(jobId: String) {
        val job = mutex.withLock {
            state.jobs.firstOrNull { it.id == jobId && it.active }
        } ?: return
        val now = clock.nowEpochMs()

        try {
            val weather = weatherApi.getCurrentWeather(job.city)
                ?: throw IllegalStateException("Город «${job.city}» не найден")
            mutex.withLock {
                val current = state.jobs.firstOrNull { it.id == job.id && it.active }
                    ?: return@withLock
                val completed = current.copy(
                    lastRunAtEpochMs = now,
                    nextRunAtEpochMs = now + current.intervalMinutes * 60_000L,
                    lastError = null,
                )
                val retained = state.measurements
                    .filterNot { it.jobId == job.id }
                    .plus(
                        state.measurements.filter { it.jobId == job.id }
                            .plus(WeatherMeasurement(job.id, now, weather))
                            .takeLast(MAX_SAMPLES_PER_JOB)
                    )
                state = state.copy(
                    jobs = state.jobs.map { if (it.id == job.id) completed else it },
                    measurements = retained,
                )
                persistLocked()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                val current = state.jobs.firstOrNull { it.id == job.id } ?: return@withLock
                val failed = current.copy(
                    nextRunAtEpochMs = now + current.intervalMinutes * 60_000L,
                    lastError = e.message ?: "Не удалось получить погоду",
                )
                state = state.copy(jobs = state.jobs.map { if (it.id == job.id) failed else it })
                persistLocked()
            }
        }
    }

    private suspend fun requireJob(id: String): ScheduledWeatherJob = mutex.withLock {
        state.jobs.first { it.id == id }
    }

    private suspend fun resolveJob(city: String?, requireActive: Boolean): ScheduledWeatherJob =
        mutex.withLock {
            val candidates = state.jobs.filter { !requireActive || it.active }
            val normalizedCity = city?.trim().orEmpty()
            when {
                normalizedCity.isNotBlank() -> candidates.lastOrNull {
                    it.city.equals(normalizedCity, ignoreCase = true)
                } ?: throw IllegalArgumentException("Задание для города «$normalizedCity» не найдено")
                candidates.size == 1 -> candidates.single()
                candidates.isEmpty() -> throw IllegalStateException("Сохранённых заданий пока нет")
                else -> throw IllegalArgumentException("Укажите город: сохранено несколько заданий")
            }
        }

    private fun persistLocked() = store.save(state)

    override fun close() {
        schedulerJob?.cancel()
        scope.cancel()
    }
}

internal fun epochToIso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
