package com.mau89.talkloop

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(
    enableDnsRebindingProtection: Boolean = true,
    weatherApi: WeatherApi = WttrWeatherApi(),
) {
    val weatherMcpServer = createWeatherMcpServer(weatherApi)

    if (weatherApi is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { weatherApi.close() }
    }

    mcpStreamableHttp(
        path = "/mcp",
        enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"),
    ) {
        weatherMcpServer
    }

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }

        get("/api/weather/{city}") {
            val city = call.parameters["city"].orEmpty()
            val weather = weatherApi.getCurrentWeather(city)
            if (weather == null) {
                call.respondText(
                    "City $city not found",
                    status = io.ktor.http.HttpStatusCode.NotFound,
                )
            } else {
                call.respondText(
                    weather.toJson().toString(),
                    io.ktor.http.ContentType.Application.Json,
                )
            }
        }
    }
}
