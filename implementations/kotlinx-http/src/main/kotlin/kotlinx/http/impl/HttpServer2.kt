package kotlinx.http.impl

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.sockets.*
import kotlinx.sockets.Socket
import kotlinx.sockets.adapters.*
import kotlinx.sockets.impl.*
import kotlinx.sockets.selector.*
import java.io.*
import java.net.*
import java.nio.*
import java.nio.ByteBuffer
import kotlin.coroutines.experimental.*


private val callDispatcher: CoroutineContext = ioCoroutineDispatcher //IOCoroutineDispatcher(8)

fun main(args: Array<String>) {
    runBlocking {
        httpServer2(CompletableDeferred())
    }
}

suspend fun httpServer2(deferred: CompletableDeferred<kotlinx.sockets.ServerSocket>) {
    ActorSelectorManager().use { selector ->
        aSocket(selector).tcp().bind(InetSocketAddress(9096)).use { server ->
            deferred.complete(server)
            server.openAcceptChannel().consumeEach { client ->
                launch(ioCoroutineDispatcher) {
                    try {
                        client.use {
//                            stupidHandler(client, client.openReadChannel())
//                            handleConnectionSimple(client, client.openReadChannel())
//                            handleConnectionSimple(client, client.openReadChannel())
                            handleConnectionPipeline(client, client.openReadChannel())
                        }
                    } catch (io: IOException) {
                    }
                }
            }
        }
    }
}


val CHAR_BUFFER_POOL_SIZE = 4096
val CHAR_BUFFER_SIZE = 4096

internal val CharBufferPool: ObjectPool<CharBuffer> =
        object : kotlinx.sockets.impl.ObjectPoolImpl<CharBuffer>(CHAR_BUFFER_POOL_SIZE) {
            override fun produceInstance(): CharBuffer =
                    ByteBuffer.allocateDirect(CHAR_BUFFER_SIZE).asCharBuffer()

            override fun clearInstance(instance: CharBuffer): CharBuffer =
                    instance.also { it.clear() }
        }


private val stupidResponse = "HTTP/1.1 200 OK\r\nContent-Length: 13\r\nContent-Type: text/plain\r\n\r\nHello, World\n".toByteArray()
@Suppress("unused")
private suspend fun stupidHandler(socket: Socket, input: ByteReadChannel) {
    val ch = socket.openWriteChannel()
    val buffer = CharBufferPool.borrow()

    try {
        while (true) {
            while (true) {
                buffer.clear()
                if (!input.readUTF8LineTo(buffer, buffer.capacity())) return
                buffer.flip()
                if (buffer.isEmpty()) break
            }

            ch.writeFully(stupidResponse)
            ch.flush()
        }
    } finally {
        CharBufferPool.recycle(buffer)
        ch.close()
    }
}

private suspend fun handleConnectionSimple(socket: Socket, input: ByteReadChannel) {
    val output = socket.openWriteChannel()

    try {
        while (true) {
            val request = parseRequest(input) ?: return

            // TODO request body

            handleRequest2(request, EmptyByteReadChannel, output)
        }
    } finally {
        output.close()
    }
}

private suspend fun handleConnectionPipeline(socket: Socket, input: ByteReadChannel) {
    val output = socket.openWriteChannel()
    val outputs = actor<ByteReadChannel>(ioCoroutineDispatcher, capacity = 5) {
        try {
            consumeEach { child ->
                child.copyTo(output)
                output.flush()
            }
        } catch (t: Throwable) {
            output.close(t)
        } finally {
            output.close()
        }
    }

    try {
        while (true) {
            val request = parseRequest(input) ?: return
            val expectedHttpBody = expectHttpBody(request)
            val requestBody = if (expectedHttpBody) ByteChannel() else EmptyByteReadChannel

            val response = ByteChannel()
            outputs.send(response)

            launch(callDispatcher) {
                handleRequest2(request, requestBody, response)
                response.close()
            }

            if (expectedHttpBody) {
                parseHttpBody(request, input, output)
            }
        }
    } finally {
        outputs.close()
    }
}
