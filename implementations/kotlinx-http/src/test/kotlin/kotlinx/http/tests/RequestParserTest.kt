package kotlinx.http.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.http.*
import org.junit.*
import kotlin.test.*

class RequestParserTest {
    @Test
    fun testParseGetRoot() = runBlocking {
        val requestText = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        val ch = ByteReadChannel(requestText.toByteArray())

        val request = parseRequest(ch)
        assertNotNull(request)
        assertEquals(HttpMethod.GET, request!!.method)
        assertEquals("/", request.uri.toString())
        assertEquals("HTTP/1.1", request.version.toString())

        assertEquals(2, request.headers.size)
        assertEquals("localhost", request.headers["Host"]?.toString())
        assertEquals("close", request.headers["Connection"]?.toString())
    }

    @Test
    fun testParseGetRootAlternativeSpaces() = runBlocking {
        val requestText = "GET  /  HTTP/1.1\nHost:  localhost\nConnection:close\n\n"
        val ch = ByteReadChannel(requestText.toByteArray())

        val request = parseRequest(ch)
        assertNotNull(request)
        assertEquals(HttpMethod.GET, request!!.method)
        assertEquals("/", request.uri.toString())
        assertEquals("HTTP/1.1", request.version.toString())

        assertEquals(2, request.headers.size)
        assertEquals("localhost", request.headers["Host"]?.toString())
        assertEquals("close", request.headers["Connection"]?.toString())
    }
}