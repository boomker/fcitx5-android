/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.clipboardsync.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.net.ssl.*

/** Uses public socket APIs; no dependency on OkHttp's private reader or Android TLS internals. */
internal object BoundedWebSocketTransport {
    fun client(base: OkHttpClient, secure: Boolean, maxMessage: Long): OkHttpClient {
        val checkedRoutes = ConcurrentHashMap<URI, List<Proxy>>()
        val builder = base.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            // A connection with an unguarded HTTP reader must never enter this pool.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS) // OkHttp exits this timeout once the socket upgrades.
            // OkHttp deliberately skips network interceptors for WebSocket upgrades.
            .addInterceptor { chain ->
                if (!secure) {
                    val uri = chain.request().url.toUri()
                    val routes = base.proxy?.let(::listOf) ?: base.proxySelector.select(uri).toList()
                    // Reject outside RetryAndFollowUpInterceptor: throwing from select() inside
                    // RouteSelector construction can make OkHttp retry before advancing a route.
                    if (routes.any { it.type() == Proxy.Type.SOCKS })
                        throw IOException("Plain WebSocket over SOCKS cannot enforce the receive limit; use WSS")
                    checkedRoutes[uri] = routes
                }
                // Wire limits cannot bound inflated messages; do not negotiate compression.
                val response = chain.proceed(chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build())
                if (response.headers.values("Sec-WebSocket-Extensions").any { it.isNotBlank() }) {
                    response.close()
                    throw IOException("Compressed WebSocket extensions are not supported")
                }
                response
            }
        if (secure) builder.sslSocketFactory(GuardedTlsFactory(base.sslSocketFactory, maxMessage), base.x509TrustManager!!)
        else {
            builder.socketFactory(GuardedSocketFactory(base.socketFactory, maxMessage))
            builder.proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    // Use exactly the checked snapshot, without a second mutable proxy lookup.
                    return checkedRoutes[uri] ?: throw IOException("Proxy selection was not validated")
                }
                override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) =
                    base.proxySelector.connectFailed(uri, address, failure)
            })
        }
        return builder.build()
    }
}

private class GuardedSocketFactory(private val base: SocketFactory, private val limit: Long) : SocketFactory() {
    private fun wrap(socket: Socket) = GuardedSocket(socket, limit)
    override fun createSocket(): Socket = wrap(base.createSocket())
    override fun createSocket(host: String, port: Int): Socket = wrap(base.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        wrap(base.createSocket(host, port, local, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = wrap(base.createSocket(host, port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        wrap(base.createSocket(host, port, local, localPort))
}

private class GuardedTlsFactory(private val base: SSLSocketFactory, private val limit: Long) : SSLSocketFactory() {
    private fun wrap(socket: Socket): Socket = GuardedTlsSocket(socket as SSLSocket, limit)
    override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    override fun createSocket(): Socket = wrap(base.createSocket())
    // Pass the real raw socket and peer host through to the provider: preserve SNI and TLS trust.
    override fun createSocket(socket: Socket, host: String, port: Int, close: Boolean): Socket =
        wrap(base.createSocket(socket, host, port, close))
    override fun createSocket(host: String, port: Int): Socket = wrap(base.createSocket(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        wrap(base.createSocket(host, port, local, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = wrap(base.createSocket(host, port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        wrap(base.createSocket(host, port, local, localPort))
}

private class GuardedSocket(private val base: Socket, limit: Long) : Socket() {
    private val guarded by lazy { WebSocketFrameGuard(base.getInputStream(), limit) }
    override fun getInputStream() = guarded
    override fun getOutputStream() = base.getOutputStream()
    override fun bind(address: SocketAddress?) = base.bind(address)
    override fun connect(address: SocketAddress?) = base.connect(address)
    override fun connect(address: SocketAddress?, timeout: Int) = base.connect(address, timeout)
    override fun close() = base.close()
    override fun shutdownInput() = base.shutdownInput()
    override fun shutdownOutput() = base.shutdownOutput()
    override fun isConnected() = base.isConnected
    override fun isBound() = base.isBound
    override fun isClosed() = base.isClosed
    override fun isInputShutdown() = base.isInputShutdown
    override fun isOutputShutdown() = base.isOutputShutdown
    override fun getInetAddress() = base.inetAddress
    override fun getLocalAddress() = base.localAddress
    override fun getPort() = base.port
    override fun getLocalPort() = base.localPort
    override fun getRemoteSocketAddress() = base.remoteSocketAddress
    override fun getLocalSocketAddress() = base.localSocketAddress
    override fun getChannel() = base.channel
    override fun setSoTimeout(value: Int) { base.soTimeout = value }
    override fun getSoTimeout() = base.soTimeout
    override fun setTcpNoDelay(value: Boolean) { base.tcpNoDelay = value }
    override fun getTcpNoDelay() = base.tcpNoDelay
    override fun setKeepAlive(value: Boolean) { base.keepAlive = value }
    override fun getKeepAlive() = base.keepAlive
    override fun setReuseAddress(value: Boolean) { base.reuseAddress = value }
    override fun getReuseAddress() = base.reuseAddress
    override fun setSendBufferSize(value: Int) { base.sendBufferSize = value }
    override fun getSendBufferSize() = base.sendBufferSize
    override fun setReceiveBufferSize(value: Int) { base.receiveBufferSize = value }
    override fun getReceiveBufferSize() = base.receiveBufferSize
    override fun setSoLinger(on: Boolean, value: Int) = base.setSoLinger(on, value)
    override fun getSoLinger() = base.soLinger
    override fun setTrafficClass(value: Int) { base.trafficClass = value }
    override fun getTrafficClass() = base.trafficClass
    override fun setOOBInline(value: Boolean) { base.oobInline = value }
    override fun getOOBInline() = base.oobInline
    override fun sendUrgentData(value: Int) = base.sendUrgentData(value)
    override fun setPerformancePreferences(a: Int, b: Int, c: Int) = base.setPerformancePreferences(a, b, c)
    override fun toString() = "Bounded($base)"
}

private class GuardedTlsSocket(private val base: SSLSocket, limit: Long) : SSLSocket() {
    private val guarded by lazy { WebSocketFrameGuard(base.getInputStream(), limit) }
    override fun getInputStream() = guarded
    override fun getOutputStream() = base.getOutputStream()
    override fun bind(address: SocketAddress?) = base.bind(address)
    override fun connect(address: SocketAddress?) = base.connect(address)
    override fun connect(address: SocketAddress?, timeout: Int) = base.connect(address, timeout)
    override fun close() = base.close()
    override fun shutdownInput() = base.shutdownInput()
    override fun shutdownOutput() = base.shutdownOutput()
    override fun isConnected() = base.isConnected
    override fun isBound() = base.isBound
    override fun isClosed() = base.isClosed
    override fun isInputShutdown() = base.isInputShutdown
    override fun isOutputShutdown() = base.isOutputShutdown
    override fun getInetAddress() = base.inetAddress
    override fun getLocalAddress() = base.localAddress
    override fun getPort() = base.port
    override fun getLocalPort() = base.localPort
    override fun getRemoteSocketAddress() = base.remoteSocketAddress
    override fun getLocalSocketAddress() = base.localSocketAddress
    override fun getChannel() = base.channel
    override fun setSoTimeout(value: Int) { base.soTimeout = value }
    override fun getSoTimeout() = base.soTimeout
    override fun setTcpNoDelay(value: Boolean) { base.tcpNoDelay = value }
    override fun getTcpNoDelay() = base.tcpNoDelay
    override fun setKeepAlive(value: Boolean) { base.keepAlive = value }
    override fun getKeepAlive() = base.keepAlive
    override fun setReuseAddress(value: Boolean) { base.reuseAddress = value }
    override fun getReuseAddress() = base.reuseAddress
    override fun setSendBufferSize(value: Int) { base.sendBufferSize = value }
    override fun getSendBufferSize() = base.sendBufferSize
    override fun setReceiveBufferSize(value: Int) { base.receiveBufferSize = value }
    override fun getReceiveBufferSize() = base.receiveBufferSize
    override fun setSoLinger(on: Boolean, value: Int) = base.setSoLinger(on, value)
    override fun getSoLinger() = base.soLinger
    override fun setTrafficClass(value: Int) { base.trafficClass = value }
    override fun getTrafficClass() = base.trafficClass
    override fun setOOBInline(value: Boolean) { base.oobInline = value }
    override fun getOOBInline() = base.oobInline
    override fun sendUrgentData(value: Int) = base.sendUrgentData(value)
    override fun setPerformancePreferences(a: Int, b: Int, c: Int) = base.setPerformancePreferences(a, b, c)
    override fun getSupportedCipherSuites() = base.supportedCipherSuites
    override fun getEnabledCipherSuites() = base.enabledCipherSuites
    override fun setEnabledCipherSuites(value: Array<String>) { base.enabledCipherSuites = value }
    override fun getSupportedProtocols() = base.supportedProtocols
    override fun getEnabledProtocols() = base.enabledProtocols
    override fun setEnabledProtocols(value: Array<String>) { base.enabledProtocols = value }
    override fun getSession() = base.session
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = base.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = base.removeHandshakeCompletedListener(listener)
    override fun startHandshake() = base.startHandshake()
    override fun setUseClientMode(value: Boolean) { base.useClientMode = value }
    override fun getUseClientMode() = base.useClientMode
    override fun setNeedClientAuth(value: Boolean) { base.needClientAuth = value }
    override fun getNeedClientAuth() = base.needClientAuth
    override fun setWantClientAuth(value: Boolean) { base.wantClientAuth = value }
    override fun getWantClientAuth() = base.wantClientAuth
    override fun setEnableSessionCreation(value: Boolean) { base.enableSessionCreation = value }
    override fun getEnableSessionCreation() = base.enableSessionCreation
    override fun getSSLParameters() = base.sslParameters
    override fun setSSLParameters(value: SSLParameters) { base.sslParameters = value }
    override fun toString() = "Bounded($base)"
}
