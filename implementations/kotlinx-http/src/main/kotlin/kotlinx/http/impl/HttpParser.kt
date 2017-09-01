package kotlinx.http.impl

import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.impl.*

abstract class HttpMessage internal constructor(val headers: HttpHeaders, private val builder: CharBufferBuilder) {
    fun release() {
        builder.release()
        headers.release()
    }
}

class Request internal constructor(val method: HttpMethod, val uri: CharSequence, val version: CharSequence, headers: HttpHeaders, builder: CharBufferBuilder) : HttpMessage(headers, builder)

class Response internal constructor(val version: CharSequence, val status: Int, val statusText: CharSequence, headers: HttpHeaders, builder: CharBufferBuilder) : HttpMessage(headers, builder)

private const val EXPECTED_HEADERS_QTY = 32
/*
 * index array structure
 * [0] = name hash
 * [1] = value hash
 * [2] name start index
 * [3] name end (excl) index
 * [4] value start index
 * [5] value end (excl) index
 * [6] next entry index (multiplied) with the same name hash
 * [7] reserved
 */
private const val HEADER_SIZE = 8

private const val HEADER_ARRAY_POOL_SIZE = 1000

class HttpHeaders internal constructor(private val builder: CharBufferBuilder) {
    var size = 0
        private set

    private var indexes = IntArrayPool.borrow()

    fun put(nameHash: Int, valueHash: Int, nameStartIndex: Int, nameEndIndex: Int, valueStartIndex: Int, valueEndIndex: Int) {
        val base = size * HEADER_SIZE
        val array = indexes

        if (base >= indexes.size) TODO("Implement headers overflow")

        array[base + 0] = nameHash
        array[base + 1] = valueHash
        array[base + 2] = nameStartIndex
        array[base + 3] = nameEndIndex
        array[base + 4] = valueStartIndex
        array[base + 5] = valueEndIndex
        array[base + 6] = -1  // TODO
        array[base + 7] = -1

        size ++
    }

    operator fun get(name: String): CharSequence? {
        val nameHash = name.hashCodeLowerCase()
        for (i in 0 until size) {
            val offset = i * HEADER_SIZE
            if (indexes[offset] == nameHash) {
                return builder.subSequence(indexes[offset + 4], indexes[offset + 5])
            }
        }

        return null
    }

    fun release() {
        size = 0
        IntArrayPool.recycle(indexes)
    }
}

suspend fun parseRequest(input: ByteReadChannel): Request? {
    val builder = CharBufferBuilder()
    val range = MutableRange(0, 0)

    try {
        if (!input.readUTF8LineTo(builder, 8192)) return null
        range.end = builder.length

        val method = parseHttpMethod(builder, range)
        val uri = parseUri(builder, range)
        val version = parseVersion(builder, range)
        skipSpaces(builder, range)

        if (range.start != range.end) throw ParserException("Extra characters in request line: ${builder.substring(range.start, range.end)}")

        val headers = parseHeaders(input, builder, range) ?: return null

        return Request(method, uri, version, headers, builder)
    } catch (t: Throwable) {
        builder.release()
        throw t
    }
}

suspend fun parseResponse(input: ByteReadChannel): Response? {
    val builder = CharBufferBuilder()
    val range = MutableRange(0, 0)

    try {
        if (!input.readUTF8LineTo(builder, 8192)) return null
        range.end = builder.length

        val version = parseVersion(builder, range)
        val statusCode = parseStatusCode(builder, range)
        skipSpaces(builder, range)
        val statusText = builder.subSequence(range.start, range.end)

        val headers = parseHeaders(input, builder, range) ?: return null

        return Response(version, statusCode, statusText, headers, builder)
    } catch (t: Throwable) {
        builder.release()
        throw t
    }
}

fun expectHttpBody(request: Request): Boolean {
    val method = request.method
    if (method == HttpMethod.GET ||
            method == HttpMethod.HEAD ||
            method == HttpMethod.OPTIONS) {
        return false
    }

    val hh = request.headers
    val length = hh["Content-Length"]

    if (length != null) {
        if (length.length == 1 && length[0] == '0') return false
        return true
    }

    val transferEncoding = hh["Transfer-Encoding"]
    if (transferEncoding != null) {
        return true
    }

    val connection = hh["Connection"]
    if (connection != null && connection.startsWith("close")) {
        return true
    }

    if (hh["Content-Type"] != null) return true

    return false
}

suspend fun parseHttpBody(headers: HttpHeaders, input: ByteReadChannel, out: ByteWriteChannel) {
    val lengthString = headers["Content-Length"]
    if (lengthString != null) {
        val length = lengthString.toString().toLong()

        input.copyTo(out, length)
        return
    }

    val transferEncoding = headers["Transfer-Encoding"]
    if (transferEncoding != null) {
        if (transferEncoding.equalsLowerCase(other = "chunked")) {
            return decodeChunked(input, out)
        } else {
            // TODO unknown transfer encoding?
        }
    }

    val contentType = headers["Content-Type"]
    if (contentType != null && contentType.startsWith("multipart/")) {
        // TODO: implement multipart
        return copyMultipartDummy(headers, input, out)
    }

    out.close()
}

private suspend fun copyMultipartDummy(headers: HttpHeaders, input: ByteReadChannel, out: ByteWriteChannel) {
    val length = headers["Content-Length"]?.toString()?.toLong() ?: Long.MAX_VALUE
    input.copyTo(out, length)
}

private suspend fun parseHeaders(input: ByteReadChannel, builder: CharBufferBuilder, range: MutableRange): HttpHeaders? {
    val headers = HttpHeaders(builder)

    try {
        while (true) {
            if (!input.readUTF8LineTo(builder, 4096)) {
                headers.release()
                return null
            }

            range.end = builder.length

            skipSpaces(builder, range)

            range.end = builder.length
            if (range.start == range.end) break

            val nameStart = range.start
            val nameEnd = findColonOrSpace(builder, range)
            val nameHash = builder.hashCodeLowerCase(nameStart, nameEnd)
            range.start = nameEnd

            skipSpacesAndColon(builder, range)
            if (range.start == range.end) throw ParserException("No HTTP header value provided for name ${builder.substring(nameStart, nameEnd)}: \n$builder")

            // TODO check for trailing spaces in HTTP spec

            val valueStart = range.start
            val valueEnd = range.end
            val valueHash = builder.hashCodeLowerCase(valueStart, valueEnd)
            range.start = valueEnd

            headers.put(nameHash, valueHash, nameStart, nameEnd, valueStart, valueEnd)
        }

        return headers
    } catch (t: Throwable) {
        headers.release()
        throw t
    }
}

private fun parseHttpMethod(text: CharSequence, range: MutableRange): HttpMethod {
    skipSpaces(text, range)
    val exact = HttpMethod.defaults.search(text, range.start, range.end) { ch, _ -> ch == ' ' }.singleOrNull()
    if (exact != null) {
        range.start += exact.name.length
        return exact
    }

    return parseHttpMethodFull(text, range)
}

private fun parseHttpMethodFull(text: CharSequence, range: MutableRange): HttpMethod {
    return HttpMethod(nextToken(text, range))
}

private fun parseUri(text: CharSequence, range: MutableRange): CharSequence {
    skipSpaces(text, range)
    val start = range.start
    val spaceOrEnd = findSpaceOrEnd(text, range)
    val length = spaceOrEnd - start

    if (length <= 0) return ""
    if (length == 1 && text[start] == '/') {
        range.start = spaceOrEnd
        return "/"
    }

    val s = text.subSequence(start, spaceOrEnd)
    range.start = spaceOrEnd
    return s
}

private val versions = AsciiCharTree.build(listOf("HTTP/1.0", "HTTP/1.1"))
private fun parseVersion(text: CharSequence, range: MutableRange): CharSequence {
    skipSpaces(text, range)
    val exact = versions.search(text, range.start, range.end) { ch, _ -> ch == ' ' }.singleOrNull()
    if (exact != null) {
        range.start += exact.length
        return exact
    }

    return nextToken(text, range)
}

private fun parseStatusCode(text: CharSequence, range: MutableRange): Int {
    skipSpaces(text, range)
    var status = 0
    var newStart = range.end

    for (idx in range.start until range.end) {
        val ch = text[idx]
        if (ch == ' ') {
            newStart = idx
            break
        } else if (ch in '0'..'9') {
            status = status * 10 + (ch - '0')
        } else {
            throw NumberFormatException("Illegal digit $ch in status code ${text.substring(range.start, findSpaceOrEnd(text, range))}")
        }
    }

    range.start = newStart
    return status
}

private fun nextToken(text: CharSequence, range: MutableRange): CharSequence {
    val spaceOrEnd = findSpaceOrEnd(text, range)
    val s = text.subSequence(range.start, spaceOrEnd)
    range.start = spaceOrEnd
    return s
}

private fun skipSpaces(text: CharSequence, range: MutableRange) {
    var idx = range.start
    val end = range.end

    if (idx >= end || text[idx] != ' ') return
    idx++

    while (idx < end) {
        if (text[idx] != ' ') break
        idx++
    }

    range.start = idx
}

private fun skipSpacesAndColon(text: CharSequence, range: MutableRange) {
    var idx = range.start
    val end = range.end
    var colons = 0

    while (idx < end) {
        val ch = text[idx]
        if (ch == ':') {
            if (++colons > 1) {
                throw ParserException("Multiple colons in header")
            }
        } else if (ch != ' ') {
            break
        }

        idx++
    }

    range.start = idx
}

private fun findSpaceOrEnd(text: CharSequence, range: MutableRange): Int {
    var idx = range.start
    val end = range.end

    if (idx >= end || text[idx] == ' ') return idx
    idx++

    while (idx < end) {
        if (text[idx] == ' ') return idx
        idx++
    }

    return idx
}

private fun findColonOrSpace(text: CharSequence, range: MutableRange): Int {
    var idx = range.start
    val end = range.end

    while (idx < end) {
        val ch = text[idx]
        if (ch == ' ' || ch == ':') return idx
        idx++
    }

    return idx
}

private val HexTable = (0..0xff).map { v ->
    when {
        v in 0x30..0x39 -> v - 0x30L
        v >= 'a'.toLong() && v <= 'f'.toLong() -> v - 'a'.toLong() + 10
        v >= 'A'.toLong() && v <= 'F'.toLong() -> v - 'A'.toLong() + 10
        else -> -1L
    }
}.toTypedArray()

internal fun CharSequence.parseHexLong(): Long {
    var result = 0L
    for (i in 0 until length) {
        val v = this[i].toInt() and 0xffff
        val digit = if (v < 0xff) HexTable[v] else -1L
        if (digit == -1L) throw NumberFormatException("Invalid HEX number: $this, wrong digit: ${this[i]}")

        result = (result shl 4) or digit
    }

    return result
}

private class MutableRange(var start: Int, var end: Int) {
    override fun toString(): String {
        return "MutableRange(start=$start, end=$end)"
    }
}
private class ParserException(message: String) : Exception(message)

private val IntArrayPool = object : ObjectPoolImpl<IntArray>(HEADER_ARRAY_POOL_SIZE) {
    override fun produceInstance(): IntArray = IntArray(EXPECTED_HEADERS_QTY * HEADER_SIZE)
}

internal fun CharSequence.hashCodeLowerCase(start: Int = 0, end: Int = length): Int {
    var hashCode = 0
    for (pos in start until end) {
        val v = get(pos).toInt().toLowerCase()
        hashCode = 31 * hashCode + v
    }

    return hashCode
}

internal fun CharSequence.equalsLowerCase(start: Int = 0, end: Int = length, other: CharSequence): Boolean {
    if  (end - start != other.length) return false

    for (pos in start until end) {
        if (get(pos).toInt().toLowerCase() != other.get(pos - start).toInt().toLowerCase()) return false
    }

    return true
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Int.toLowerCase() = if (this in 'A'.toInt() .. 'Z'.toInt()) 'a'.toInt() + (this - 'A'.toInt()) else this