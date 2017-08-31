package kotlinx.sockets.examples

import kotlinx.sockets.examples.http.*
import java.nio.*

@Suppress("LoopToCallChain", "ReplaceRangeToWithUntil")
class CharBufferBuilder : CharSequence, Appendable {
    private var buffers: MutableList<CharBuffer>? = null
    private var current: CharBuffer? = null
    private var stringified: String? = null

    override var length: Int = 0
        private set

    override fun get(index: Int): Char {
        require(index >= 0) { "index is negative: $index"}
        require(index < length) { "index $index is not in range [0, $length)" }

        return getImpl(index)
    }

    private fun getImpl(index: Int) = bufferForIndex(index).get(index % CHAR_BUFFER_SIZE)

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        require(startIndex <= endIndex) { "startIndex ($startIndex) should be less or equal to endIndex ($endIndex)" }
        require(startIndex >= 0) { "startIndex is negative: $startIndex" }
        require(endIndex <= length) { "endIndex ($endIndex) is greater than length ($length)" }

        return SubSequenceImpl(startIndex, endIndex)
    }

    override fun toString() = stringified ?: copy(0, length).toString().also { stringified = it }

    override fun append(c: Char): Appendable {
        nonFullBuffer().put(c)
        stringified = null
        length++
        return this
    }

    override fun append(csq: CharSequence, start: Int, end: Int): Appendable {
        append(csq, start, end, nonFullBuffer())
        stringified = null
        length += end - start
        return this
    }

    override fun append(csq: CharSequence): java.lang.Appendable {
        append(csq, 0, csq.length, nonFullBuffer())
        stringified = null
        length += csq.length
        return this
    }

    fun release() {
        length = 0
        val list = buffers
        buffers = null

        if (list != null) {
            current = null
            for (i in 0 until list.size) {
                CharBufferPool.recycle(list[i])
            }
        } else {
            current?.let { CharBufferPool.recycle(it) }
            current = null
        }

        stringified = null
    }

    private fun copy(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex == endIndex) return ""

        val sb = StringBuilder(endIndex - startIndex)

        var buffer: CharBuffer
        var base = startIndex - (startIndex % CHAR_BUFFER_SIZE)

        while (base < endIndex) {
            buffer = bufferForIndex(base)
            val innerStartIndex = maxOf(0, startIndex - base)
            val innerEndIndex = minOf(endIndex - base, CHAR_BUFFER_POOL_SIZE)

            for (innerIndex in innerStartIndex until innerEndIndex) {
                sb.append(buffer.get(innerIndex))
            }

            base += CHAR_BUFFER_SIZE
        }

        return sb
    }

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private inner class SubSequenceImpl(val start: Int, val end: Int) : CharSequence {
        private var stringified: String? = null

        override val length: Int
            get() = end - start

        override fun get(index: Int): Char {
            val withOffset = index + start
            require(index >= 0) { "index is negative: $index" }
            require(withOffset < end) { "index ($index) should be less than length ($length)" }

            return this@CharBufferBuilder.getImpl(withOffset)
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex >= 0) { "start is negative: $startIndex" }
            require(startIndex <= endIndex) { "start ($startIndex) should be less or equal to end ($endIndex)" }
            require(endIndex <= end - start) { "end should be less than length ($length)" }
            if (startIndex == endIndex) return ""

            return SubSequenceImpl(start + startIndex, start + endIndex)
        }

        override fun toString() = stringified ?: copy(start, end).toString().also { stringified = it }
    }

    private tailrec fun append(csq: CharSequence, start: Int, end: Int, buffer: CharBuffer) {
        val limitedEnd = minOf(end, start + buffer.remaining())

        for (i in start..limitedEnd) {
            buffer.put(csq[i])
        }

        if (limitedEnd < end) {
            return append(csq, limitedEnd, end, appendNewBuffer())
        }
    }

    private fun bufferForIndex(index: Int): CharBuffer {
        val list = buffers

        if (list == null) {
            if (index >= CHAR_BUFFER_SIZE) throw IndexOutOfBoundsException("$index is not in range [0; ${CHAR_BUFFER_SIZE})")
            return current ?: throw IndexOutOfBoundsException("$index is not in range [0; 0)")
        }

        return list[index / CHAR_BUFFER_SIZE]
    }

    private fun nonFullBuffer(): CharBuffer {
        return current?.takeIf { it.hasRemaining() } ?: appendNewBuffer()
    }

    private fun appendNewBuffer(): CharBuffer {
        val newBuffer = CharBufferPool.borrow()
        val existing = current
        if (existing == null) {
            current = newBuffer
        } else {
            val list = buffers ?: ArrayList<CharBuffer>().also { buffers = it }
            list.add(existing)
            list.add(newBuffer)
        }

        return newBuffer
    }
}