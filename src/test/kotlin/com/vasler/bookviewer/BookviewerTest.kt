package com.vasler.bookviewer

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpRequest.GET
import io.micronaut.test.annotation.MicronautTest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.RxStreamingHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.uri.UriBuilder
import io.micronaut.runtime.server.EmbeddedServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import javax.inject.Inject

//@MicronautTest
//class BookviewerTest(private val application: EmbeddedApplication<*>): StringSpec({
//
//    "test the server is running" {
//        assert(application.isRunning)
//    }
//})

@MicronautTest
class HelloControllerTest
{
    @Inject
    @field:Client("http://localhost:8080")
    lateinit var client: RxStreamingHttpClient

    @Test
    fun testHelloController()
    {
//        UriBuilder.of("/hello")
        val response = client.toBlocking().retrieve("/hello")
        println("response: $response")
        Assertions.assertEquals("Hello World", response)
    }
}
