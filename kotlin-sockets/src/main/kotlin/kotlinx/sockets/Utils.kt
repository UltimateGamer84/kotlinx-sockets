package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.channels.*
import kotlinx.sockets.selector.*
import java.io.*
import java.nio.*
import java.nio.channels.*

suspend fun ReadChannel.readFully(dst: ByteBuffer) {
    do {
        if (read(dst) == -1) {
            if (dst.hasRemaining()) throw IOException("Unexpected eof")
            break
        }
    } while (dst.hasRemaining())
}

suspend fun WriteChannel.writeFully(src: ByteBuffer) {
    do {
        write(src)
    } while (src.hasRemaining())
}

internal fun CancellableContinuation<*>.disposeOnCancel(disposableHandle: DisposableHandle) {
    invokeOnCompletion { if (isCancelled) disposableHandle.dispose() }
}

internal var SelectionKey.subject: AsyncSelectable?
    get() = attachment() as? AsyncSelectable
    set(newValue) {
        attach(newValue)
    }
