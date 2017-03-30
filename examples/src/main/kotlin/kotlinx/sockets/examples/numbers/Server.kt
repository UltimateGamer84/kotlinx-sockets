package kotlinx.sockets.examples.numbers

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.*
import kotlinx.sockets.ServerSocket
import kotlinx.sockets.Socket
import kotlinx.sockets.channels.*
import kotlinx.sockets.channels.impl.*
import java.net.*
import java.nio.channels.*
import java.util.logging.*

fun main(args: Array<String>) {
    val (_, job) = startNumbersServer(9096)

    runBlocking {
        job.join()
    }
}

fun startNumbersServer(port: Int?, onBound: () -> Unit = {}): Pair<ServerSocket, Job> {
    val server = aSocket().tcp().bind(port?.let(::InetSocketAddress))

    val serverJob = launch(CommonPool) {
        onBound()
        while (true) {
            var client: Socket? = null
            try {
                client = server.accept()
                launch(CommonPool) {
                    client!!.use {
                        runClient(it)
                    }
                }
            } catch (expected: ClosedChannelException) {
                client?.close()
                break
            } catch (t: Throwable) {
                Logger.getLogger("acceptor").log(Level.SEVERE, "Failed to handle client", t)
                client?.close()
            }
        }
    }.apply {
        invokeOnCompletion {
            server.close()
        }
    }

    return Pair(server, serverJob)
}

private suspend fun runClient(client: Socket) {
    val input = client.asCharChannel().buffered()
    val output = client.asCharWriteChannel()
    val logger = Logger.getLogger("client")

    hello@ while (true) {
        when (input.readLine()) {
            null -> return
            "" -> {
            }
            "HELLO" -> {
                output.write("EHLLO\n")
                break@hello
            }
            else -> {
                logger.warning("Wrong hello from client ${client.remoteAddress}")
                output.write("ERROR Wrong HELLO\n")
                return
            }
        }
    }

    command@ while (true) {
        val command = input.readLine() ?: return
        when (command) {
            "" -> continue@command
            "SUM" -> sum(input, output)
            "AVG" -> avg(input, output)
            "BYE" -> {
                output.write("BYE\n")
                return
            }
            else -> {
                logger.warning("Unknown command $command from client ${client.remoteAddress}")
                output.write("ERROR Unknown command\n")
                return
            }
        }
    }
}

private suspend fun sum(input: BufferedCharReadChannel, output: CharWriteChannel) {
    val numbers = numbers(input) ?: return
    output.write("${numbers.sum()}\n")
}

private suspend fun avg(input: BufferedCharReadChannel, output: CharWriteChannel) {
    val numbers = numbers(input) ?: return
    output.write("${numbers.average()}\n")
}

private suspend fun numbers(input: BufferedCharReadChannel): List<Int>? {
    return input.readLine()?.trim()?.split(",")?.filter(String::isNotBlank)?.map(String::toInt)
}
