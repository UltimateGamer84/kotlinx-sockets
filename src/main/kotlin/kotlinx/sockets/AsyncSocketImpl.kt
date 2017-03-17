package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import java.net.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

internal class AsyncSocketImpl<out S : SocketChannel>(override val channel: S, val selector: SelectorManager) : AsyncSelectable, AsyncSocket {
    init {
        require(!channel.isBlocking) { "channel need to be configured as non-blocking" }
    }

    @Volatile
    override var interestedOps: Int = 0
        private set

    private val connectContinuation = AtomicReference<Continuation<Boolean>?>()
    private val readContinuation = AtomicReference<Continuation<Unit>?>()
    private val writeContinuation = AtomicReference<Continuation<Unit>?>()

    override val localAddress: SocketAddress
        get() = channel.localAddress

    override val remoteAddress: SocketAddress
        get() = channel.remoteAddress

    override fun <T> setOption(name: SocketOption<T>, value: T) {
        channel.setOption(name, value)
    }

    override suspend fun onSelected(key: SelectionKey) {
        if (key.isConnectable) {
            connectContinuation.getAndSet(null)?.resume(false)
        } else {
            wantConnect(false)
        }

        if (key.isReadable) {
            readContinuation.getAndSet(null)?.resume(Unit)
        } else {
            wantMoreBytesRead(false)
        }

        if (key.isWritable) {
            writeContinuation.getAndSet(null)?.resume(Unit)
        } else {
            wantMoreSpaceForWrite(false)
        }
    }

    override suspend fun connect(address: SocketAddress) {
        var connected = suspendCoroutineOrReturn<Boolean> { c ->
            if (channel.connect(address)) {
                true
            } else {
                if (!connectContinuation.compareAndSet(null, c)) throw IllegalStateException()
                wantConnect(true)

                COROUTINE_SUSPENDED
            }
        }

        while (!connected) {
            connected = suspendCoroutineOrReturn<Boolean> { c ->
                if (channel.finishConnect()) {
                    true
                } else {
                    if (!connectContinuation.compareAndSet(null, c)) throw IllegalStateException()
                    wantConnect(true)

                    COROUTINE_SUSPENDED
                }
            }
        }
    }

    override suspend fun read(dst: ByteBuffer): Int {
        while (true) {
            val rc = suspendCoroutineOrReturn<Any> {
                val rc = channel.read(dst)
                if (rc > 0) rc
                else {
                    if (!readContinuation.compareAndSet(null, it)) throw IllegalStateException()
                    wantMoreBytesRead()

                    COROUTINE_SUSPENDED
                }
            }

            if (rc is Int) return rc
        }
    }

    override suspend fun write(src: ByteBuffer) {
        while (src.hasRemaining()) {
            suspendCoroutineOrReturn<Unit> { c ->
                val rc = channel.write(src)

                if (rc == 0) {
                    if (!writeContinuation.compareAndSet(null, c)) throw IllegalStateException()
                    wantMoreSpaceForWrite()

                    COROUTINE_SUSPENDED
                } else {
                    Unit
                }
            }
        }
    }

    override fun close() {
        channel.close()
    }

    private fun wantConnect(state: Boolean = true) {
        interestFlag(SelectionKey.OP_CONNECT, state)
    }

    private fun wantMoreBytesRead(state: Boolean = true) {
        interestFlag(SelectionKey.OP_READ, state)
    }

    private fun wantMoreSpaceForWrite(state: Boolean = true) {
        interestFlag(SelectionKey.OP_WRITE, state)
    }

    private fun interestFlag(flag: Int, state: Boolean) {
        val newOps = if (state) interestedOps or flag else interestedOps and flag.inv()
        if (interestedOps != newOps) {
            launch(selector.dispatcher) {
                interestedOps = newOps
                selector.registerSafe(this@AsyncSocketImpl)
            }
        }
    }
}
