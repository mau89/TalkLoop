package com.mau89.talkloop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class SavedWeatherReport(
    val filePath: String,
    val bytesWritten: Int,
)

fun interface WeatherReportStore {
    fun save(city: String, markdown: String): SavedWeatherReport
}

class FileWeatherReportStore(
    private val directory: Path,
) : WeatherReportStore {
    override fun save(city: String, markdown: String): SavedWeatherReport {
        Files.createDirectories(directory)
        val safeCity = city.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(50)
            .ifBlank { "city" }
        val fileName = "weather-$safeCity-${System.currentTimeMillis()}.md"
        val target = directory.resolve(fileName)
        val bytes = markdown.toByteArray(StandardCharsets.UTF_8)
        Files.write(target, bytes)
        return SavedWeatherReport(
            filePath = target.toString(),
            bytesWritten = bytes.size,
        )
    }
}

data class WeatherReportPipelineResult(
    val weather: WeatherSnapshot,
    val markdown: String,
    val saved: SavedWeatherReport,
)

/**
 * Три стадии композиции MCP: получить исходные данные, преобразовать их и сохранить.
 * Отдельные MCP-инструменты и общий оркестратор используют одни и те же операции,
 * поэтому формат данных между самостоятельным и автоматическим сценариями совпадает.
 */
class WeatherReportPipeline(
    private val weatherApi: WeatherApi,
    private val reportStore: WeatherReportStore,
) {
    suspend fun search(city: String): WeatherSnapshot {
        val normalizedCity = city.trim()
        require(normalizedCity.length >= 2) {
            "Укажите название города минимум из двух символов"
        }
        return weatherApi.getCurrentWeather(normalizedCity)
            ?: error("Город «$normalizedCity» не найден")
    }

    fun summarize(weather: WeatherSnapshot): String = buildString {
        appendLine("# Отчёт о погоде: ${weather.city}")
        appendLine()
        appendLine("- Страна: ${weather.country.ifBlank { "не указана" }}")
        appendLine("- Время наблюдения: ${weather.observedAt}")
        appendLine("- Условия: ${weather.condition}")
        appendLine("- Температура: ${weather.temperatureC} °C")
        appendLine("- Ощущается как: ${weather.feelsLikeC} °C")
        appendLine("- Влажность: ${weather.humidityPercent}%")
        appendLine("- Осадки: ${weather.precipitationMm} мм")
        appendLine("- Ветер: ${weather.windSpeedKmh} км/ч")
    }.trimEnd()

    fun save(city: String, markdown: String): SavedWeatherReport {
        require(city.isNotBlank()) { "Не указан город для имени отчёта" }
        require(markdown.isNotBlank()) { "Нельзя сохранить пустой отчёт" }
        return reportStore.save(city, markdown)
    }

    suspend fun run(city: String): WeatherReportPipelineResult {
        val weather = search(city)
        val markdown = summarize(weather)
        val saved = save(weather.city, markdown)
        return WeatherReportPipelineResult(weather, markdown, saved)
    }
}
