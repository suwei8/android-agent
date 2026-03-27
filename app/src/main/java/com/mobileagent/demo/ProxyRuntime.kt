package com.mobileagent.demo

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal enum class ProxyBackendType {
    SOCKS5,
    HTTP,
}

internal enum class ProxyAddressFamily {
    AUTO,
    IPV4,
    IPV6,
}

internal data class ProxyNodeConfig(
    val id: Int,
    val name: String,
    val type: ProxyBackendType,
    val host: String,
    val port: Int,
    val exitFamily: ProxyAddressFamily,
    val authEnabled: Boolean,
    val username: String,
    val password: String,
)

internal data class ProxyNodeRuntimeSnapshot(
    val id: Int,
    val name: String,
    val type: ProxyBackendType,
    val host: String,
    val port: Int,
    val exitFamily: ProxyAddressFamily,
    val running: Boolean,
    val currentConnections: Int,
    val startedAt: Long?,
    val lastError: String?,
)

internal object ProxyRuntime {
    private val lock = Any()
    private val configs = LinkedHashMap<Int, ProxyNodeConfig>()
    private val snapshots = LinkedHashMap<Int, ProxyNodeRuntimeSnapshot>()
    private val runtimes = LinkedHashMap<Int, RunningProxyNode>()

    fun snapshotFor(config: ProxyNodeConfig): ProxyNodeRuntimeSnapshot {
        synchronized(lock) {
            configs[config.id] = config
            return currentSnapshotLocked(config.id)
                ?: stoppedSnapshot(config, lastError = snapshots[config.id]?.lastError)
        }
    }

    fun listSnapshots(): List<ProxyNodeRuntimeSnapshot> {
        synchronized(lock) {
            return configs.values.map { config ->
                currentSnapshotLocked(config.id) ?: stoppedSnapshot(config, snapshots[config.id]?.lastError)
            }
        }
    }

    fun start(config: ProxyNodeConfig): ProxyNodeRuntimeSnapshot {
        synchronized(lock) {
            configs[config.id] = config
            runtimes.remove(config.id)?.close()
            return try {
                val runtime = RunningProxyNode(config)
                runtimes[config.id] = runtime
                runtime.start()
                val snapshot = runtime.snapshot()
                snapshots[config.id] = snapshot
                snapshot
            } catch (t: Throwable) {
                val snapshot = stoppedSnapshot(config, t.message ?: "代理启动失败")
                snapshots[config.id] = snapshot
                snapshot
            }
        }
    }

    fun stop(id: Int): ProxyNodeRuntimeSnapshot? {
        synchronized(lock) {
            val runtime = runtimes.remove(id)
            val config = configs[id] ?: runtime?.config ?: return null
            runtime?.close()
            val snapshot = stoppedSnapshot(config, lastError = null)
            snapshots[id] = snapshot
            return snapshot
        }
    }

    private fun currentSnapshotLocked(id: Int): ProxyNodeRuntimeSnapshot? {
        val runtime = runtimes[id] ?: return snapshots[id]
        val snapshot = runtime.snapshot()
        snapshots[id] = snapshot
        return snapshot
    }

    private fun stoppedSnapshot(config: ProxyNodeConfig, lastError: String?): ProxyNodeRuntimeSnapshot {
        return ProxyNodeRuntimeSnapshot(
            id = config.id,
            name = config.name,
            type = config.type,
            host = config.host,
            port = config.port,
            exitFamily = config.exitFamily,
            running = false,
            currentConnections = 0,
            startedAt = null,
            lastError = lastError,
        )
    }
}

private class RunningProxyNode(
    val config: ProxyNodeConfig,
) {
    private val workerPool: ExecutorService = Executors.newCachedThreadPool()
    private val activeConnections = AtomicInteger(0)
    private val startedAt = System.currentTimeMillis()
    private val serverSocket = ServerSocket()

    @Volatile
    private var accepting = true

    @Volatile
    private var lastError: String? = null

    private val acceptThread = thread(
        start = false,
        name = "proxy-node-${config.id}-${config.type.name.lowercase(Locale.US)}",
        isDaemon = true,
    ) {
        acceptLoop()
    }

    fun start() {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(config.port))
        acceptThread.start()
    }

    fun snapshot(): ProxyNodeRuntimeSnapshot {
        return ProxyNodeRuntimeSnapshot(
            id = config.id,
            name = config.name,
            type = config.type,
            host = config.host,
            port = config.port,
            exitFamily = config.exitFamily,
            running = accepting && !serverSocket.isClosed,
            currentConnections = activeConnections.get(),
            startedAt = startedAt,
            lastError = lastError,
        )
    }

    fun close() {
        accepting = false
        runCatching { serverSocket.close() }
        workerPool.shutdownNow()
    }

    private fun acceptLoop() {
        while (accepting) {
            try {
                val client = serverSocket.accept()
                client.tcpNoDelay = true
                workerPool.execute {
                    activeConnections.incrementAndGet()
                    try {
                        when (config.type) {
                            ProxyBackendType.SOCKS5 -> handleSocks5(client)
                            ProxyBackendType.HTTP -> handleHttpProxy(client)
                        }
                    } catch (t: Throwable) {
                        lastError = t.message ?: "代理连接处理失败"
                    } finally {
                        activeConnections.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            } catch (_: SocketException) {
                if (!accepting) {
                    return
                }
                lastError = "监听已中断"
                return
            } catch (t: Throwable) {
                lastError = t.message ?: "代理监听失败"
                return
            }
        }
    }

    private fun handleSocks5(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val version = input.readUnsignedByte()
        if (version != 5) {
            throw EOFException("不是 SOCKS5 握手")
        }
        val methodsCount = input.readUnsignedByte()
        val methods = ByteArray(methodsCount)
        input.readFully(methods)
        val selectedMethod = when {
            config.authEnabled && methods.any { it.toInt() == 0x02 } -> 0x02
            !config.authEnabled && methods.any { it.toInt() == 0x00 } -> 0x00
            else -> 0xFF
        }
        output.write(byteArrayOf(0x05, selectedMethod.toByte()))
        output.flush()
        if (selectedMethod == 0xFF) {
            return
        }
        if (selectedMethod == 0x02) {
            val authVersion = input.readUnsignedByte()
            if (authVersion != 0x01) {
                output.write(byteArrayOf(0x01, 0x01))
                output.flush()
                return
            }
            val username = input.readSizedString()
            val password = input.readSizedString()
            val authOk = username == config.username && password == config.password
            output.write(byteArrayOf(0x01, if (authOk) 0x00 else 0x01))
            output.flush()
            if (!authOk) {
                return
            }
        }

        val requestVersion = input.readUnsignedByte()
        if (requestVersion != 5) {
            throw EOFException("SOCKS5 请求版本错误")
        }
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val destinationHost = readAddress(input)
        val destinationPort = input.readUnsignedShort()
        if (command != 0x01) {
            output.write(buildSocks5Reply(0x07, null, 0))
            output.flush()
            return
        }

        val remote = connectRemote(destinationHost, destinationPort)
        try {
            val localAddress = remote.localAddress
            val localPort = remote.localPort
            output.write(buildSocks5Reply(0x00, localAddress, localPort))
            output.flush()
            relayBidirectional(client, remote)
        } catch (t: Throwable) {
            output.write(buildSocks5Reply(0x05, null, 0))
            output.flush()
            throw t
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun handleHttpProxy(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val requestLine = input.readAsciiLine()?.takeIf { it.isNotBlank() } ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) {
            writeHttpError(output, 400, "Bad Request")
            return
        }
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readAsciiLine() ?: break
            if (line.isBlank()) {
                break
            }
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }

        if (config.authEnabled) {
            val provided = headers["proxy-authorization"].orEmpty()
            val expected = "Basic " + Base64.encodeToString(
                "${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            if (provided != expected) {
                writeHttpProxyAuthRequired(output)
                return
            }
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            handleHttpConnect(target, output, client)
            return
        }

        val uri = runCatching { URI(target) }.getOrNull()
        val hostHeader = headers["host"].orEmpty()
        val targetHost = uri?.host ?: hostHeader.substringBefore(':').ifBlank { null }
        val targetPort = when {
            uri != null && uri.port > 0 -> uri.port
            hostHeader.contains(':') -> hostHeader.substringAfterLast(':').toIntOrNull() ?: 80
            else -> 80
        }
        if (targetHost.isNullOrBlank()) {
            writeHttpError(output, 400, "Bad Request")
            return
        }
        val path = when {
            uri != null -> buildString {
                append(uri.rawPath?.ifBlank { "/" } ?: "/")
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let {
                    append('?')
                    append(it)
                }
            }
            target.startsWith("/") -> target
            else -> "/"
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

        val remote = connectRemote(targetHost, targetPort)
        try {
            val remoteOut = BufferedOutputStream(remote.getOutputStream())
            remoteOut.write("$method $path $version\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            headers.forEach { (name, value) ->
                if (name == "proxy-authorization" || name == "proxy-connection" || name == "connection") {
                    return@forEach
                }
                remoteOut.write("${name.headerCase()}: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
            remoteOut.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            remoteOut.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            if (contentLength > 0) {
                input.copyExactlyTo(remoteOut, contentLength)
            }
            remoteOut.flush()
            remote.getInputStream().copyTo(output)
            output.flush()
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun handleHttpConnect(target: String, output: BufferedOutputStream, client: Socket) {
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "443").toIntOrNull() ?: 443
        val remote = connectRemote(host, port)
        try {
            output.write("HTTP/1.1 200 Connection Established\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
            relayBidirectional(client, remote)
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun connectRemote(host: String, port: Int): Socket {
        var lastFailure: Throwable? = null
        InetAddress.getAllByName(host)
            .asSequence()
            .filter { address ->
                when (config.exitFamily) {
                    ProxyAddressFamily.AUTO -> true
                    ProxyAddressFamily.IPV4 -> address is Inet4Address
                    ProxyAddressFamily.IPV6 -> address is Inet6Address
                }
            }
            .ifEmpty { InetAddress.getAllByName(host).asSequence() }
            .forEach { address ->
            try {
                return Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(address, port), 10_000)
                }
            } catch (t: Throwable) {
                lastFailure = t
            }
        }
        throw lastFailure ?: SocketException("无法连接到 $host:$port")
    }

    private fun relayBidirectional(client: Socket, remote: Socket) {
        val upstream: Future<*> = workerPool.submit {
            copySocketData(client.getInputStream(), remote.getOutputStream())
        }
        val downstream: Future<*> = workerPool.submit {
            copySocketData(remote.getInputStream(), client.getOutputStream())
        }
        try {
            upstream.get()
        } catch (_: Throwable) {
        } finally {
            runCatching { remote.shutdownOutput() }
            runCatching { client.shutdownInput() }
        }
        try {
            downstream.get()
        } catch (_: Throwable) {
        }
    }

    private fun copySocketData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) {
                break
            }
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun buildSocks5Reply(status: Int, bindAddress: InetAddress?, bindPort: Int): ByteArray {
        val address = bindAddress ?: InetAddress.getByName("0.0.0.0")
        val addressBytes = address.address
        val addressType = when (address) {
            is Inet6Address -> 0x04
            is Inet4Address -> 0x01
            else -> 0x01
        }
        val reply = ByteArray(6 + addressBytes.size)
        reply[0] = 0x05
        reply[1] = status.toByte()
        reply[2] = 0x00
        reply[3] = addressType.toByte()
        System.arraycopy(addressBytes, 0, reply, 4, addressBytes.size)
        reply[reply.size - 2] = ((bindPort ushr 8) and 0xFF).toByte()
        reply[reply.size - 1] = (bindPort and 0xFF).toByte()
        return reply
    }

    private fun readAddress(input: InputStream): String {
        return when (val addressType = input.readUnsignedByte()) {
            0x01 -> InetAddress.getByAddress(input.readExact(4)).hostAddress ?: throw EOFException("IPv4 地址解析失败")
            0x03 -> String(input.readExact(input.readUnsignedByte()), StandardCharsets.UTF_8)
            0x04 -> InetAddress.getByAddress(input.readExact(16)).hostAddress ?: throw EOFException("IPv6 地址解析失败")
            else -> throw EOFException("不支持的地址类型: $addressType")
        }
    }

    private fun writeHttpProxyAuthRequired(output: BufferedOutputStream) {
        output.write(
            (
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"mobile-agent\"\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.flush()
    }

    private fun writeHttpError(output: BufferedOutputStream, code: Int, message: String) {
        output.write(
            (
                "HTTP/1.1 $code $message\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.flush()
    }
}

private fun InputStream.readExact(length: Int): ByteArray {
    val buffer = ByteArray(length)
    readFully(buffer)
    return buffer
}

private fun InputStream.readFully(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) {
            throw EOFException("流提前结束")
        }
        offset += count
    }
}

private fun InputStream.readUnsignedByte(): Int {
    val value = read()
    if (value < 0) {
        throw EOFException("流提前结束")
    }
    return value
}

private fun InputStream.readUnsignedShort(): Int {
    val first = readUnsignedByte()
    val second = readUnsignedByte()
    return (first shl 8) or second
}

private fun InputStream.readSizedString(): String {
    val size = readUnsignedByte()
    return String(readExact(size), StandardCharsets.UTF_8)
}

private fun BufferedInputStream.readAsciiLine(): String? {
    val bytes = ArrayList<Byte>()
    while (true) {
        val value = read()
        if (value < 0) {
            return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
        }
        if (value == '\n'.code) {
            break
        }
        if (value != '\r'.code) {
            bytes += value.toByte()
        }
    }
    return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
}

private fun InputStream.copyExactlyTo(output: OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) {
            throw EOFException("请求体提前结束")
        }
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun String.headerCase(): String {
    return split('-').joinToString("-") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
