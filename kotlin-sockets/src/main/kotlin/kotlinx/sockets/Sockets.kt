package kotlinx.sockets

import kotlinx.coroutines.experimental.*
import kotlinx.sockets.channels.*
import java.io.*
import java.net.*

/**
 * Base type for all async sockets and channels
 */
interface ASocket : Closeable, DisposableHandle {
    override fun dispose() {
        close()
    }
}

/**
 * Represents local peer
 */
interface AsyncLocalPeer : ASocket {
    /**
     * Local socket address. Could throw an exception if no address bound yet.
     */
    val localAddress: SocketAddress
}

/**
 * Represents connected peer
 */
interface AsyncConnection : ASocket {
    /**
     * Remote socket address. Could throw an exception if the peer is not yet connected or already disconnected.
     */
    val remoteAddress: SocketAddress
}

/**
 * Represents a socket with options, could be configured
 */
interface ConfigurableSocket : ASocket {
    /**
     * Configures option [option] with given [value]. Could throw an exception if [option] is not supported by
     * the socket or [value] is not relevant or invalid.
     */
    fun <T> setOption(option: SocketOption<T>, value: T)
}

/**
 * Represents a TCP socket. Provides ability to [connect], [read] and [write].
 */
interface AsyncSocket : AsyncLocalPeer, AsyncConnection, ReadChannel, WriteChannel, ConfigurableSocket {
    /**
     * Connect socket to the specified [address], suspends until connection succeeds.
     */
    suspend fun connect(address: SocketAddress)
}

/**
 * Represents a server TCP socket that could be bound via [bind] and used to [accept] connections.
 */
interface AsyncServerSocket : AsyncLocalPeer, ConfigurableSocket, SocketSource<AsyncSocket> {
    /**
     * Bind server socket to the specified [localAddress].
     * Will automatically choose some random local address if [localAddress] is null.
     * Could fail if [localAddress] is already in use or if there are no free local ports available.
     */
    fun bind(localAddress: SocketAddress?)
}
