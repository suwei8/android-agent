package com.mobileagent.demo

import android.content.Context
import fi.iki.elonen.NanoHTTPD

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

internal data class ApiServerStatus(
    val running: Boolean,
    val host: String,
    val port: Int,
    val localApi: String,
    val lastError: String? = null,
)

internal data class ApiTaskRecord(
    val id: String,
    val title: String,
    val status: String,
    val detail: String,
    val createdAt: Long,
    val updatedAt: Long,
    val oldIpv6: String? = null,
    val newIpv6: String? = null,
    val debugLines: List<String> = emptyList(),
)

internal object MobileAgentRuntime {
    private const val host = "127.0.0.1"
    private const val port = 18080

    private val controller = RootNetworkController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverLock = Any()
    private val taskLock = Any()
    private val tasks = LinkedHashMap<String, ApiTaskRecord>()

    @Volatile
    private var server: MobileAgentApiServer? = null

    @Volatile
    private var serverStatus = ApiServerStatus(
        running = false,
        host = host,
        port = port,
        localApi = "$host:$port",
    )

    @Volatile
    private var latestSnapshot = NetworkSnapshot(
        currentIpv4 = null,
        currentIpv6 = null,
        preferredNetworkLabel = "未连接",
        wifiConnected = false,
        details = emptyList(),
    )

    @Volatile
    private var latestDiagnostics: List<String> = emptyList()

    @Volatile
    private var latestError: String? = null

    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
        controller.bindContext(context.applicationContext)
    }

    fun ensureApiServerStarted(): ApiServerStatus {

        synchronized(serverLock) {
            server?.let { existing ->
                if (existing.isAlive) {
                    return serverStatus.copy(running = true, lastError = latestError)
                }
            }
            return try {
                MobileAgentApiServer(host, port).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                    server = it
                }
                serverStatus = ApiServerStatus(
                    running = true,
                    host = host,
                    port = port,
                    localApi = "$host:$port",
                    lastError = null,
                )
                serverStatus
            } catch (e: IOException) {
                serverStatus = serverStatus.copy(running = false, lastError = e.message ?: "本地 API 启动失败")
                serverStatus
            }
        }
    }

    fun getServerStatus(): ApiServerStatus = serverStatus.copy(lastError = latestError ?: serverStatus.lastError)

    suspend fun refreshNetworkSnapshot(lastErrorOverride: String? = latestError): NetworkSnapshot {
        val snapshot = controller.refreshSnapshot()
        latestSnapshot = snapshot
        latestDiagnostics = snapshot.details
        latestError = lastErrorOverride
        return snapshot
    }

    fun getCachedSnapshot(): NetworkSnapshot = latestSnapshot

    fun getLatestDiagnostics(): List<String> = latestDiagnostics

    fun getLatestError(): String? = latestError

    fun requireContext(): Context {
        return appContext ?: error("应用上下文尚未绑定。")
    }

    fun listTasks(limit: Int = 20): List<ApiTaskRecord> = synchronized(taskLock) {
        tasks.values.toList().sortedByDescending { it.createdAt }.take(limit)
    }

    fun getTask(taskId: String): ApiTaskRecord? = synchronized(taskLock) {
        tasks[taskId]
    }

    fun submitRotateTask(holdSeconds: Int, title: String): ApiTaskRecord {
        val taskId = "rot-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val pendingTask = ApiTaskRecord(
            id = taskId,
            title = title,
            status = "queued",
            detail = "任务已创建，等待执行",
            createdAt = now,
            updatedAt = now,
        )
        upsertTask(pendingTask)

        scope.launch {
            updateTask(taskId) { task ->
                task.copy(status = "running", detail = "正在申请 root 权限并切换飞行模式", updatedAt = System.currentTimeMillis())
            }

            val result = controller.rotateIp(holdSeconds)
            val snapshot = controller.refreshSnapshot()
            latestSnapshot = snapshot
            val debugLines = (result.debugLog + snapshot.details).distinct().takeLast(16)
            latestDiagnostics = debugLines
            latestError = if (result.success) null else result.message
            val detail = when {
                result.success && result.oldIpv6.isNullOrBlank() -> "已获取新的 IPv6：${result.newIpv6}"
                result.success && result.ipChanged -> "IPv6 已从 ${result.oldIpv6} 切换到 ${result.newIpv6}"
                else -> result.message
            }
            updateTask(taskId) { task ->
                task.copy(
                    status = if (result.success) "succeeded" else "failed",
                    detail = detail,
                    updatedAt = System.currentTimeMillis(),
                    oldIpv6 = result.oldIpv6,
                    newIpv6 = snapshot.currentIpv6 ?: result.newIpv6,
                    debugLines = debugLines,
                )
            }
        }

        return pendingTask
    }

    private fun upsertTask(task: ApiTaskRecord) {
        synchronized(taskLock) {
            tasks[task.id] = task
            while (tasks.size > 40) {
                val oldestKey = tasks.entries.minByOrNull { it.value.createdAt }?.key ?: break
                tasks.remove(oldestKey)
            }
        }
    }

    private fun updateTask(taskId: String, transform: (ApiTaskRecord) -> ApiTaskRecord) {
        synchronized(taskLock) {
            val existing = tasks[taskId] ?: return
            tasks[taskId] = transform(existing)
        }
    }

    private class MobileAgentApiServer(hostname: String, port: Int) : NanoHTTPD(hostname, port) {
        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.method == Method.GET && session.uri == "/api/health" -> jsonResponse(
                        JSONObject()
                            .put("ok", true)
                            .put("server", statusJson())
                    )

                    session.method == Method.GET && session.uri == "/api/network/status" -> {
                        val snapshot = runBlocking { refreshNetworkSnapshot() }
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("network", snapshotJson(snapshot))
                                .put("server", statusJson())
                        )
                    }

                    session.method == Method.POST && session.uri == "/api/network/rotate" -> {
                        val body = parseJsonBody(session)
                        val holdSeconds = body?.optInt("holdSeconds")?.takeIf { it > 0 } ?: 10
                        val title = body?.optString("title")?.takeIf { it.isNotBlank() } ?: "API 更换出口 IP"
                        val task = submitRotateTask(holdSeconds = holdSeconds, title = title)
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("taskId", task.id)
                                .put("task", taskJson(getTask(task.id) ?: task)),
                            status = Response.Status.ACCEPTED,
                        )
                    }

                    session.method == Method.GET && session.uri == "/api/tasks" -> jsonResponse(
                        JSONObject()
                            .put("ok", true)
                            .put("tasks", JSONArray().apply {
                                listTasks().forEach { put(taskJson(it)) }
                            })
                    )

                    session.method == Method.GET && session.uri == "/api/tunnel/status" -> {
                        val status = runBlocking { TunnelRuntime.refreshStatus(requireContext(), lastErrorOverride = null) }
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("tunnel", tunnelJson(status))
                        )
                    }

                    session.method == Method.GET && session.uri == "/api/proxy/nodes" -> {
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("nodes", JSONArray().apply {
                                    ProxyRuntime.listSnapshots().forEach { put(proxyNodeJson(it)) }
                                })
                        )
                    }

                    session.method == Method.POST && session.uri == "/api/proxy/start" -> {
                        val body = parseJsonBody(session) ?: JSONObject()
                        val config = parseProxyNodeConfig(body)
                        val status = ProxyRuntime.start(config)
                        jsonResponse(
                            JSONObject()
                                .put("ok", status.running)
                                .put("node", proxyNodeJson(status))
                                .put("error", status.lastError ?: JSONObject.NULL),
                            status = if (status.running) Response.Status.OK else Response.Status.INTERNAL_ERROR,
                        )
                    }

                    session.method == Method.POST && session.uri == "/api/proxy/stop" -> {
                        val body = parseJsonBody(session) ?: JSONObject()
                        val nodeId = body.optInt("id", -1)
                        val status = ProxyRuntime.stop(nodeId)
                        if (status == null) {
                            jsonResponse(
                                JSONObject().put("ok", false).put("error", "节点不存在"),
                                status = Response.Status.NOT_FOUND,
                            )
                        } else {
                            jsonResponse(
                                JSONObject()
                                    .put("ok", true)
                                    .put("node", proxyNodeJson(status))
                            )
                        }
                    }

                    (session.method == Method.GET || session.method == Method.POST) && session.uri == "/api/tunnel/start" -> {
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("queued", true)
                                .put("message", "隧道启动任务已提交")
                        ).also {
                            val context = requireContext()
                            scope.launch {
                                TunnelRuntime.start(context)
                            }
                        }
                    }

                    (session.method == Method.GET || session.method == Method.POST) && session.uri == "/api/tunnel/stop" -> {
                        jsonResponse(
                            JSONObject()
                                .put("ok", true)
                                .put("queued", true)
                                .put("message", "隧道停止任务已提交")
                        ).also {
                            val context = requireContext()
                            scope.launch {
                                TunnelRuntime.stop(context)
                            }
                        }
                    }

                    session.method == Method.GET && session.uri.startsWith("/api/tasks/") -> {
                        val taskId = session.uri.removePrefix("/api/tasks/")
                        val task = getTask(taskId)
                        if (task == null) {
                            jsonResponse(
                                JSONObject().put("ok", false).put("error", "任务不存在"),
                                status = Response.Status.NOT_FOUND,
                            )
                        } else {
                            jsonResponse(JSONObject().put("ok", true).put("task", taskJson(task)))
                        }
                    }

                    else -> jsonResponse(
                        JSONObject()
                            .put("ok", false)
                            .put("error", "未找到接口")
                            .put("available", JSONArray(listOf(
                                "GET /api/health",
                                "GET /api/network/status",
                                "POST /api/network/rotate",
                                "GET /api/tasks",
                                "GET /api/tasks/{taskId}",
                                "GET /api/proxy/nodes",
                                "POST /api/proxy/start",
                                "POST /api/proxy/stop",
                                "GET /api/tunnel/status",
                                "GET /api/tunnel/start",
                                "GET /api/tunnel/stop",
                                "POST /api/tunnel/start",
                                "POST /api/tunnel/stop",
                            ))),
                        status = Response.Status.NOT_FOUND,
                    )
                }
            } catch (t: Throwable) {
                latestError = t.message ?: "本地 API 异常"
                jsonResponse(
                    JSONObject().put("ok", false).put("error", latestError),
                    status = Response.Status.INTERNAL_ERROR,
                )
            }
        }
    }

    private fun parseJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject? {
        if (session.method != NanoHTTPD.Method.POST && session.method != NanoHTTPD.Method.PUT) return null
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"]?.trim().orEmpty()
        return if (body.isBlank()) null else JSONObject(body)
    }

    private fun jsonResponse(body: JSONObject, status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.OK): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString(2))
    }

    private fun statusJson(): JSONObject {
        val status = getServerStatus()
        return JSONObject()
            .put("running", status.running)
            .put("host", status.host)
            .put("port", status.port)
            .put("localApi", status.localApi)
            .put("lastError", status.lastError ?: JSONObject.NULL)
    }

    private fun snapshotJson(snapshot: NetworkSnapshot): JSONObject {
        return JSONObject()
            .put("activeNetwork", snapshot.preferredNetworkLabel)
            .put("ipv4", snapshot.currentIpv4 ?: JSONObject.NULL)
            .put("ipv6", snapshot.currentIpv6 ?: JSONObject.NULL)
            .put("wifiConnected", snapshot.wifiConnected)
            .put("lastError", latestError ?: JSONObject.NULL)
            .put("diagnostics", JSONArray(snapshot.details))
    }

    private fun taskJson(task: ApiTaskRecord): JSONObject {
        return JSONObject()
            .put("id", task.id)
            .put("title", task.title)
            .put("status", task.status)
            .put("detail", task.detail)
            .put("createdAt", formatTime(task.createdAt))
            .put("updatedAt", formatTime(task.updatedAt))
            .put("oldIpv6", task.oldIpv6 ?: JSONObject.NULL)
            .put("newIpv6", task.newIpv6 ?: JSONObject.NULL)
            .put("debugLines", JSONArray(task.debugLines))
    }

    private fun tunnelJson(status: TunnelStatusSnapshot): JSONObject {
        return JSONObject()
            .put("tokenBound", status.tokenBound)
            .put("running", status.running)
            .put("statusLabel", status.statusLabel)
            .put("domain", status.domain)
            .put("binaryPath", status.binaryPath)
            .put("binaryReady", status.binaryReady)
            .put("binaryVersion", status.binaryVersion ?: JSONObject.NULL)
            .put("downloadStatus", status.downloadStatus)
            .put("deviceAbi", status.deviceAbi)
            .put("pid", status.pid ?: JSONObject.NULL)
            .put("lastError", status.lastError ?: JSONObject.NULL)
            .put("logLines", JSONArray(status.logLines))
    }

    private fun proxyNodeJson(status: ProxyNodeRuntimeSnapshot): JSONObject {
        return JSONObject()
            .put("id", status.id)
            .put("name", status.name)
            .put("type", status.type.name.lowercase())
            .put("host", status.host)
            .put("port", status.port)
            .put("exitFamily", status.exitFamily.name.lowercase())
            .put("bindHost", status.bindHost)
            .put("running", status.running)
            .put("currentConnections", status.currentConnections)
            .put("startedAt", status.startedAt ?: JSONObject.NULL)
            .put("localIpv4Reachable", status.localIpv4Reachable ?: JSONObject.NULL)
            .put("localIpv6Reachable", status.localIpv6Reachable ?: JSONObject.NULL)
            .put("lastError", status.lastError ?: JSONObject.NULL)
    }

    private fun parseProxyNodeConfig(body: JSONObject): ProxyNodeConfig {
        val nodeType = when (body.optString("type").trim().lowercase()) {
            "http" -> ProxyBackendType.HTTP
            else -> ProxyBackendType.SOCKS5
        }
        val exitFamily = when (body.optString("exitFamily").trim().lowercase()) {
            "ipv4" -> ProxyAddressFamily.IPV4
            "ipv6" -> ProxyAddressFamily.IPV6
            else -> ProxyAddressFamily.AUTO
        }
        val nodeId = body.optInt("id").takeIf { it > 0 } ?: ((System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        return ProxyNodeConfig(
            id = nodeId,
            name = body.optString("name").ifBlank { "代理节点-$nodeId" },
            type = nodeType,
            host = body.optString("host").ifBlank { "::" },
            port = body.optInt("port").takeIf { it > 0 } ?: if (nodeType == ProxyBackendType.HTTP) 8080 else 1080,
            exitFamily = exitFamily,
            authEnabled = body.optBoolean("authEnabled", false),
            username = body.optString("username"),
            password = body.optString("password"),
        )
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))
    }
}
