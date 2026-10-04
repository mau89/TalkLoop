package com.mau89.talkloop

import com.mau89.talkloop.llm.DocumentChunkHit
import com.mau89.talkloop.llm.DocumentIndexCatalog
import com.mau89.talkloop.llm.DocumentSearchRequest
import com.mau89.talkloop.llm.DocumentSearchResponse
import com.mau89.talkloop.llm.DocumentStrategyResults
import com.mau89.talkloop.llm.DocumentStrategyStats
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentIndexApiTest {
    private val catalog = DocumentIndexCatalog(
        documents = 79, words = 129505, textPages = 259.01, models = listOf("A1", "A1 mini"),
        embeddingModel = "intfloat/multilingual-e5-small", dimension = 384,
        strategies = listOf(DocumentStrategyStats("fixed", 732, 306.3, 691, 0.7857)),
    )

    @Test
    fun catalogAndBothStrategiesUseSharedMobileContract() = testApplication {
        var received: DocumentSearchRequest? = null
        val gateway = object : DocumentIndexGateway {
            override suspend fun catalog() = catalog
            override suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse {
                received = request
                return DocumentSearchResponse(request.query, request.model,
                    listOf("fixed", "structural").map { strategy ->
                        DocumentStrategyResults(strategy, listOf(DocumentChunkHit(
                            chunkId = strategy, source = "https://wiki.bambulab.com/en/a1", title = "A1 manual",
                            file = "raw/a1.html", section = "Nozzle / Cold pull", anchors = listOf("cold-pull"),
                            models = listOf("A1", "A1 mini"), tokenCount = 40, text = "Cold pull instructions", score = 0.82,
                        )))
                    }, searchSeconds = 0.1,
                )
            }
        }
        application { routing { documentIndexRoutes(gateway) } }
        val status = client.get("/api/documents/status")
        assertEquals(HttpStatusCode.OK, status.status)
        assertEquals(catalog, Json.decodeFromString<DocumentIndexCatalog>(status.bodyAsText()))
        val request = DocumentSearchRequest("Как очистить сопло A1?", "A1", "both")
        val response = client.post("/api/documents/search") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(request, received)
        val decoded = Json.decodeFromString<DocumentSearchResponse>(response.bodyAsText())
        assertEquals(listOf("fixed", "structural"), decoded.results.map { it.strategy })
        assertEquals(listOf("A1", "A1 mini"), decoded.results.first().hits.single().models)
        assertEquals("Nozzle / Cold pull", decoded.results.first().hits.single().section)
    }

    @Test
    fun russianCookbookCatalogHasExamplesWithoutPrinterFilter() = testApplication {
        val cookbook = catalog.copy(
            documents = 53, words = 32122, textPages = 64.24, models = emptyList(),
            title = "Кулинарная книга — Викиучебник", language = "ru",
            exampleQueries = listOf("Как приготовить плов?"),
            attribution = "Участники Викиучебника · CC BY-SA 4.0",
        )
        val gateway = object : DocumentIndexGateway {
            override suspend fun catalog() = cookbook
            override suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse =
                error("Catalog request must not run search")
        }
        application { routing { documentIndexRoutes(gateway) } }
        val response = client.get("/api/documents/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(cookbook, Json.decodeFromString<DocumentIndexCatalog>(response.bodyAsText()))
    }

    @Test
    fun malformedAndInvalidRequestsNeverReachWorker() = testApplication {
        var calls = 0
        val gateway = object : DocumentIndexGateway {
            override suspend fun catalog() = catalog
            override suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse {
                calls++
                error("Invalid requests must be rejected before inference")
            }
        }
        application { routing { documentIndexRoutes(gateway) } }
        for (body in listOf("{", "{}", "{\"query\":\" \"}",
            "{\"query\":\"test\",\"strategy\":\"other\"}", "{\"query\":\"test\",\"limit\":11}")) {
            val response = client.post("/api/documents/search") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(response.bodyAsText().contains("error"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun missingLocalIndexReturnsActionableServiceUnavailable() = testApplication {
        val gateway = object : DocumentIndexGateway {
            override suspend fun catalog(): DocumentIndexCatalog = throw DocumentIndexUnavailable("Подготовьте индекс.")
            override suspend fun search(request: DocumentSearchRequest): DocumentSearchResponse = throw DocumentIndexUnavailable("Подготовьте индекс.")
        }
        application { routing { documentIndexRoutes(gateway) } }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/documents/status").status)
        val response = client.post("/api/documents/search") {
            contentType(ContentType.Application.Json)
            setBody("{\"query\":\"A1\"}")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("Подготовьте индекс"))
    }
}
