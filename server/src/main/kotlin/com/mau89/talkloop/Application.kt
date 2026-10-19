package com.mau89.talkloop

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.nio.file.Paths

fun main() {
    val bookServer = embeddedServer(
        Netty,
        port = 8081,
        host = "0.0.0.0",
        module = Application::bookModule,
    ).start(wait = false)
    val projectServer = embeddedServer(
        Netty, port = 8082, host = "0.0.0.0", module = Application::projectModule,
    )
    val supportServer = embeddedServer(Netty, port = 8083, host = "127.0.0.1", module = Application::supportModule)
    try {
        supportServer.start(wait = false)
        projectServer.start(wait = false)
        embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
            .start(wait = true)
    } finally {
        supportServer.stop(gracePeriodMillis = 1_000L, timeoutMillis = 2_000L)
        bookServer.stop(gracePeriodMillis = 1_000L, timeoutMillis = 2_000L)
        projectServer.stop(gracePeriodMillis = 1_000L, timeoutMillis = 2_000L)
    }
}

fun Application.module(
    enableDnsRebindingProtection: Boolean = true,
    weatherApi: WeatherApi = WttrWeatherApi(),
    weatherScheduler: WeatherSummaryScheduler = WeatherSummaryScheduler(
        weatherApi = weatherApi,
        store = JsonWeatherSchedulerStore(
            Paths.get(
                System.getProperty(
                    "talkloop.scheduler.file",
                    "server-data/weather-scheduler.json",
                )
            )
        ),
    ),
    weatherReportStore: WeatherReportStore = FileWeatherReportStore(
        Paths.get(
            System.getProperty(
                "talkloop.reports.directory",
                "server-data/reports",
            )
        )
    ),
    documentIndex: DocumentIndexGateway = LocalDocumentIndexGateway(),
    projectDocumentIndex: DocumentIndexGateway = LocalDocumentIndexGateway(
        dataDirectory = Paths.get(System.getProperty("talkloop.project.root", ".")).resolve("day31-data"),
        preparationCommand = ".day21-venv/bin/python tools/day31/index_project.py",
    ),
    prReviews: PrReviewGateway = LocalPrReviewGateway(),
    support: SupportService = LocalSupportService(),
    fileTasks: FileTaskGateway = LocalFileTaskGateway(),
) {
    val weatherMcpServer = createWeatherMcpServer(
        weatherApi,
        weatherScheduler,
        WeatherReportPipeline(weatherApi, weatherReportStore),
    )
    weatherScheduler.start()

    if (weatherApi is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { weatherApi.close() }
    }
    monitor.subscribe(ApplicationStopped) { weatherScheduler.close() }
    if (documentIndex is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { documentIndex.close() }
    }
    if (projectDocumentIndex is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { projectDocumentIndex.close() }
    }
    if (prReviews is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { prReviews.close() }
    }
    if (support is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { support.close() }
    }
    if (fileTasks is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { fileTasks.close() }
    }

    mcpStreamableHttp(
        path = "/mcp",
        enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"),
    ) {
        weatherMcpServer
    }

    routing {
        fileTaskRoutes(fileTasks)
        supportRoutes(support)
        prReviewRoutes(prReviews)
        documentIndexRoutes(documentIndex)
        documentIndexRoutes(projectDocumentIndex, "/api/project/documents")
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

fun Application.supportModule(
    enableDnsRebindingProtection: Boolean = true,
    repository: SupportRepository = JsonSupportRepository(),
) {
    val server = createSupportMcpServer(repository)
    mcpStreamableHttp(path = "/mcp", enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")) { server }
}

fun Application.projectModule(
    enableDnsRebindingProtection: Boolean = true,
    projectRepository: ProjectRepository = LocalProjectRepository(),
) {
    val server = createProjectMcpServer(projectRepository)
    mcpStreamableHttp(
        path = "/mcp",
        enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"),
    ) { server }
}

fun Application.bookModule(
    enableDnsRebindingProtection: Boolean = true,
    bookApi: BookApi = OpenLibraryBookApi(),
) {
    val bookMcpServer = createBookMcpServer(bookApi)
    if (bookApi is AutoCloseable) {
        monitor.subscribe(ApplicationStopped) { bookApi.close() }
    }

    mcpStreamableHttp(
        path = "/mcp",
        enableDnsRebindingProtection = enableDnsRebindingProtection,
        allowedHosts = listOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2"),
    ) {
        bookMcpServer
    }

    routing {
        get("/") {
            call.respondText("TalkLoop Books MCP")
        }
    }
}
