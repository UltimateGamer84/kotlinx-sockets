package kotlinx.http

import kotlinx.http.internals.*

abstract class HttpMessage internal constructor(val headers: HttpHeaders, private val builder: CharBufferBuilder) {
    fun release() {
        builder.release()
        headers.release()
    }
}

class Request internal constructor(val method: HttpMethod, val uri: CharSequence, val version: CharSequence, headers: HttpHeaders, builder: CharBufferBuilder) : HttpMessage(headers, builder)

class Response internal constructor(val version: CharSequence, val status: Int, val statusText: CharSequence, headers: HttpHeaders, builder: CharBufferBuilder) : HttpMessage(headers, builder)
