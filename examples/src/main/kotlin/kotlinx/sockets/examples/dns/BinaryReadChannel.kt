package kotlinx.sockets.examples.dns

import kotlinx.coroutines.experimental.channels.*
import kotlinx.sockets.*
import java.nio.*
import java.nio.charset.*

class BinaryReadChannel(val source: ReceiveChannel<ByteBuffer>, val pool: Channel<ByteBuffer>, val order: ByteOrder) : ReadChannel {
    private var remaining: ByteBuffer? = null
    private var buffer = Empty

    suspend override fun read(dst: ByteBuffer): Int {
        if (!dst.hasRemaining()) {
            return if (source.isClosedForReceive && !buffer.hasRemaining()) -1 else 0
        }

        if (!buffer.hasRemaining()) {
            if (source.isClosedForReceive) return -1

            fill(1)
        }

        val before = dst.position()
        while (dst.hasRemaining() && buffer.hasRemaining()) {
            dst.put(buffer.get())
        }

        return dst.position() - before
    }

    suspend fun skipExact(qty: Int) {
        require(qty >= 0)

        var remaining = qty
        while (remaining > 0) {
            fill(1)

            val step = minOf(remaining, buffer.remaining())
            remaining -= step
            buffer.position(buffer.position() + step)
        }
    }

    fun getByte(): Byte = buffer.get()
    fun getUByte(): Int = buffer.get().toInt() and 0xff
    fun getShort(): Short = buffer.getShort()
    fun getUShort(): Int = buffer.getShort().toInt() and 0xffff
    fun getUInt(): Long = buffer.getInt().toLong() and 0xffffffffL

    suspend fun getStringByRawLength(length: Int, decoder: CharsetDecoder): String {
        val sb = StringBuilder((length * decoder.maxCharsPerByte()).toInt())
        decoder.reset()
        val bb = pool.receive()
        val cb = bb.asCharBuffer()
        cb.clear()

        var remaining = length

        while (remaining > 0) {
            if (!buffer.hasRemaining()) {
                fill(1)
            }

            val before = buffer.position()
            when {
                buffer.remaining() <= remaining -> {
                    decoder.decode(buffer, cb, buffer.remaining() >= remaining)
                    remaining -= (buffer.position() - before)
                }
                else -> {
                    val sub = buffer.slice()
                    sub.limit(remaining)
                    val dr = decoder.decode(sub, cb, true)
                    buffer.position(buffer.position() + sub.position())
                    remaining -= sub.position()
                }
            }

            cb.flip()
            sb.append(cb)
        }

        pool.offer(bb)

        return sb.toString()
    }

    override fun close() {
        // TODO not clear for now
    }

    suspend fun fill(required: Int) {
        while (buffer.remaining() < required) {
            val next = remaining ?: source.receiveOrNull() ?: break
            remaining = null

            if (buffer.hasRemaining()) {
                val newBuffer = pool.receive()
                newBuffer.clear()
                newBuffer.order(order)
                newBuffer.put(buffer)
                while (next.hasRemaining() && newBuffer.hasRemaining()) {
                    newBuffer.put(next.get())
                }

                buffer = newBuffer
                if (next.hasRemaining()) remaining = next
            } else {
                buffer = next
            }
        }

        if (buffer.remaining() < required) throw BufferUnderflowException()
    }

    companion object {
        private val Empty = ByteBuffer.allocate(0)
    }
}
