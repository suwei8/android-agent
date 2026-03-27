package com.mobileagent.demo

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal data class TunnelStatusSnapshot(
    val tokenBound: Boolean,
    val running: Boolean,
    val statusLabel: String,
    val domain: String,
    val binaryPath: String,
    val binaryReady: Boolean,
    val binaryVersion: String?,
    val downloadStatus: String,
    val deviceAbi: String,
    val pid: String?,
    val logLines: List<String>,
    val lastError: String?,
)

internal object TunnelRuntime {
    private const val prefsName = "mobile-agent-remote"
    private const val keyToken = "cloudflared_token"
    private const val keyDomain = "cloudflared_domain"
    private const val binarySourceApp = "app"
    private const val appLogFileName = "mobile-agent-cloudflared.log"
    private const val appPidFileName = "mobile-agent-cloudflared.pid"
    private const val appStatusDumpFileName = "mobile-agent-tunnel-status.json"
    private val dnsResolverAddrs = listOf("1.1.1.1:53", "8.8.8.8:53")
    private const val logLineLimit = 80
    private const val startupPollCount = 10
    private const val startupPollDelayMs = 2_000L

    @Volatile
    private var runtimeExitMessage: String? = null

    @Volatile
    private var latestStatus = TunnelStatusSnapshot(
        tokenBound = false,
        running = false,
        statusLabel = "未绑定",
        domain = "未配置",
        binaryPath = "检测中",
        binaryReady = false,
        binaryVersion = null,
        downloadStatus = "APK 内置二进制待检测",
        deviceAbi = resolveDeviceAbiLabel(),
        pid = null,
        logLines = emptyList(),
        lastError = null,
    )

    fun getSavedToken(context: Context): String = prefs(context).getString(keyToken, "").orEmpty()

    fun getSavedDomain(context: Context): String = prefs(context).getString(keyDomain, "").orEmpty()

    suspend fun bind(context: Context, token: String, domain: String): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        prefs(context)
            .edit()
            .putString(keyToken, token.trim())
            .putString(keyDomain, domain.trim())
            .apply()
        refreshStatus(context, lastErrorOverride = null)
    }

    suspend fun prepareBundledBinary(context: Context): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val stageError = stagePackagedBinaryIfNeeded(context)
        val refreshedSelection = resolveBinarySelection(context)
        val message = when {
            stageError != null -> stageError
            refreshedSelection.ready -> "当前版本已内置 cloudflared，可直接由 root 启动。"
            else -> packagedBinaryIssueMessage()
        }
        buildStatus(context, refreshedSelection, message)
    }

    suspend fun refreshStatus(
        context: Context,
        lastErrorOverride: String? = latestStatus.lastError,
    ): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        buildStatus(context, resolveBinarySelection(context), lastErrorOverride)
    }

    suspend fun start(context: Context): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val token = getSavedToken(context)
        if (token.isBlank()) {
            return@withContext refreshStatus(context, lastErrorOverride = "请先绑定 Tunnel Token。")
        }

        val selection = resolveBinarySelection(context)
        if (!selection.ready) {
            return@withContext buildStatus(context, selection, packagedBinaryIssueMessage())
        }

        TunnelForegroundService.requestStart(context.applicationContext)
        waitForStableStatus(context, selection, startError = null)
    }

    suspend fun stop(context: Context): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        TunnelForegroundService.requestStop(context.applicationContext)
        repeat(6) { attempt ->
            if (attempt > 0) {
                Thread.sleep(500)
            }
            val status = buildStatus(context, resolveBinarySelection(context), lastErrorOverride = null)
            if (!status.running) {
                return@withContext status
            }
        }
        refreshStatus(context, lastErrorOverride = null)
    }

    fun getLatestStatus(): TunnelStatusSnapshot = latestStatus

    internal suspend fun startManagedProcess(
        context: Context,
        onStatusChanged: ((TunnelStatusSnapshot) -> Unit)? = null,
    ): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val token = getSavedToken(context)
        if (token.isBlank()) {
            return@withContext buildStatus(context, resolveBinarySelection(context), "请先绑定 Tunnel Token。")
        }

        val stageError = stagePackagedBinaryIfNeeded(context)
        if (stageError != null) {
            return@withContext buildStatus(context, resolveBinarySelection(context), stageError)
        }
        val selection = resolveBinarySelection(context)
        if (!selection.ready) {
            return@withContext buildStatus(context, selection, packagedBinaryIssueMessage())
        }
        stopLegacyTunnelProcesses()
        stopManagedProcessInternal(selection)
        runtimeExitMessage = null
        resetRuntimeFiles(selection)
        var lastStatus: TunnelStatusSnapshot? = null
        var lastError: String? = null

        buildRootStartStrategies(
            buildBackgroundStartCommand(selection.path, selection.logPath, selection.pidPath, token),
        ).forEach { strategy ->
            appendLogLine(selection, "[app] 尝试启动策略: ${strategy.label}")
            val startResult = runCommand(strategy.command, 15_000)
            if (startResult.exitCode != 0) {
                val message = startResult.stdout.trim().ifBlank { "cloudflared 进程启动失败。" }
                appendLogLine(selection, "[app] 启动命令失败: $message")
                lastError = message
                return@forEach
            }

            val status = waitForStableStatus(context, selection, startError = null)
            if (status.running && status.logLines.any { it.contains("Registered tunnel connection", ignoreCase = true) }) {
                onStatusChanged?.invoke(status)
                return@withContext status
            }

            val message = status.lastError ?: status.statusLabel
            appendLogLine(selection, "[app] 策略未连通，准备切换: $message")
            stopManagedProcessInternal(selection)
            lastStatus = status
            lastError = message
        }

        val status = lastStatus ?: buildStatus(context, selection, lastError ?: "cloudflared 启动失败。")
        onStatusChanged?.invoke(status)
        status
    }

    internal suspend fun stopManagedProcess(
        context: Context,
        lastErrorOverride: String? = null,
    ): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val selection = resolveBinarySelection(context)
        stopManagedProcessInternal(selection)
        Thread.sleep(300)
        val status = buildStatus(context, selection, lastErrorOverride)
        latestStatus = status
        status
    }

    private fun buildStatus(
        context: Context,
        selection: TunnelBinarySelection,
        lastErrorOverride: String?,
    ): TunnelStatusSnapshot {
        val token = getSavedToken(context)
        val domain = getSavedDomain(context).ifBlank { "未配置" }
        val cachedBinaryVersion = latestStatus
            .takeIf { it.binaryPath == selection.path && it.binaryReady }
            ?.binaryVersion
        val versionCheck = if (selection.ready && cachedBinaryVersion.isNullOrBlank()) {
            runBasicRootCommand("${shellQuote(selection.path)} --version", 15_000)
        } else {
            null
        }
        val binaryVersion = cachedBinaryVersion ?: versionCheck
            ?.stdout
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
        val pid = findRunningTunnelPid(selection)
        val running = pid != null
        val tunnelLogs = tailLines(File(selection.logPath), logLineLimit)
        val logHealth = analyzeTunnelLogs(tunnelLogs)

        val computedError = when {
            lastErrorOverride != null -> lastErrorOverride
            token.isBlank() -> null
            !selection.ready -> packagedBinaryIssueMessage()
            running && logHealth.connected -> null
            logHealth.blockingError != null -> logHealth.blockingError
            !running && !runtimeExitMessage.isNullOrBlank() -> runtimeExitMessage
            !running && logHealth.gracefulStop -> null
            !running && tunnelLogs.isNotEmpty() -> tunnelLogs.last()
            versionCheck != null && versionCheck.exitCode != 0 -> versionCheck.stdout.trim().ifBlank { "cloudflared 无法执行。" }
            else -> null
        }

        latestStatus = TunnelStatusSnapshot(
            tokenBound = token.isNotBlank(),
            running = running,
            statusLabel = when {
                token.isBlank() -> "未绑定"
                !selection.ready -> "缺少可执行文件"
                running && logHealth.connected -> "已连接"
                running -> "启动中"
                computedError != null -> "已停止（有错误）"
                !running && logHealth.gracefulStop -> "已停止"
                else -> "已停止"
            },
            domain = domain,
            binaryPath = selection.path,
            binaryReady = selection.ready,
            binaryVersion = binaryVersion,
            downloadStatus = buildBinaryStatusLabel(selection),
            deviceAbi = resolveDeviceAbiLabel(),
            pid = pid,
            logLines = buildList {
                add("ABI: ${resolveDeviceAbiLabel()}")
                add("Source: ${selection.sourceLabel}")
                add("Binary: ${selection.path}")
                add("Log: ${selection.logPath}")
                binaryVersion?.let { add("Version: $it") }
                addAll(tunnelLogs)
            }.takeLast(logLineLimit),
            lastError = computedError,
        )
        persistDebugSnapshot(context, latestStatus, selection, versionCheck)
        return latestStatus
    }

    private fun buildBinaryStatusLabel(selection: TunnelBinarySelection): String {
        return if (selection.ready) {
            "当前使用 APK 内置 native 二进制，并由 root 前台服务启动"
        } else {
            "APK 内未找到可执行 cloudflared，请确认 arm64-v8a/libcloudflared.so 已正确打包"
        }
    }

    private fun waitForStableStatus(
        context: Context,
        selection: TunnelBinarySelection,
        startError: String?,
    ): TunnelStatusSnapshot {
        var lastError = startError
        repeat(startupPollCount) { attempt ->
            if (attempt > 0) {
                Thread.sleep(startupPollDelayMs)
            }
            val status = buildStatus(context, selection, lastError)
            lastError = null
            if (status.running && status.logLines.any { it.contains("Registered tunnel connection", ignoreCase = true) }) {
                return status
            }
            if (!status.running && status.lastError != null) {
                return status
            }
        }
        return buildStatus(context, selection, lastErrorOverride = null)
    }

    private fun persistDebugSnapshot(
        context: Context,
        status: TunnelStatusSnapshot,
        selection: TunnelBinarySelection,
        versionCheck: TunnelShellResult?,
    ) {
        try {
            val dump = JSONObject()
                .put("generatedAt", System.currentTimeMillis())
                .put("abi", resolveDeviceAbiLabel())
                .put("tokenBound", status.tokenBound)
                .put("statusLabel", status.statusLabel)
                .put("running", status.running)
                .put("binaryPath", status.binaryPath)
                .put("binaryReady", status.binaryReady)
                .put("downloadStatus", status.downloadStatus)
                .put("lastError", status.lastError ?: JSONObject.NULL)
                .put("selectedSource", selection.id)
                .put("selectedSourceLabel", selection.sourceLabel)
                .put("nativeLibraryDir", context.applicationInfo.nativeLibraryDir ?: JSONObject.NULL)
                .put("runtimeExitMessage", runtimeExitMessage ?: JSONObject.NULL)
                .put("probes", JSONObject()
                    .put("binaryVersion", versionCheck?.let(::shellResultJson) ?: JSONObject.NULL)
                    .put("binaryExists", File(selection.path).exists())
                    .put("binaryCanExecute", File(selection.path).canExecute())
                )
                .put("logLines", JSONArray(status.logLines))
            appStatusDumpFile(context).writeText(dump.toString(2))
        } catch (_: Throwable) {
        }
    }

    private fun shellResultJson(result: TunnelShellResult): JSONObject {
        return JSONObject()
            .put("executable", result.executable)
            .put("exitCode", result.exitCode)
            .put("stdout", trimForDebug(result.stdout))
    }

    private fun trimForDebug(value: String, limit: Int = 800): String {
        val normalized = value.trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "...(truncated)"
    }

    private fun stopManagedProcessInternal(selection: TunnelBinarySelection) {
        val pid = runCatching { File(selection.pidPath).readText().trim() }.getOrNull().orEmpty()
        if (pid.isNotBlank()) {
            runBasicRootCommand("kill $pid 2>/dev/null || true", 10_000)
        }
        runBasicRootCommand("pkill -x cloudflared 2>/dev/null || true", 10_000)
        stopLegacyTunnelProcesses()
        clearPidFile(selection)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun resolveBinarySelection(context: Context): TunnelBinarySelection {
        val packagedFile = packagedAppBinaryFile(context)
        val ready = packagedFile.exists()
        return TunnelBinarySelection(
            id = binarySourceApp,
            path = packagedFile.absolutePath,
            ready = ready,
            sourceLabel = "内置 Android 二进制（APK nativeLibraryDir）",
            logPath = appLogFile(context).absolutePath,
            pidPath = appPidFile(context).absolutePath,
        )
    }

    private fun resolveDeviceAbiLabel(): String {
        val abis = Build.SUPPORTED_ABIS.orEmpty()
        return abis.firstOrNull().orEmpty().ifBlank { "unknown" }
    }

    private fun packagedBinaryIssueMessage(): String {
        return "内置 cloudflared 尚未就绪。请确认已打包 arm64-v8a/libcloudflared.so。"
    }

    private fun packagedAppBinaryFile(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir.orEmpty(), "libcloudflared.so")
    }

    private fun appLogFile(context: Context): File = File(context.applicationContext.filesDir, appLogFileName)

    private fun appPidFile(context: Context): File = File(context.applicationContext.filesDir, appPidFileName)

    private fun appStatusDumpFile(context: Context): File = File(context.applicationContext.filesDir, appStatusDumpFileName)

    private fun buildTunnelStartCommand(binaryPath: String, token: String): String {
        return buildString {
            append(shellQuote(binaryPath))
            append(" tunnel --no-autoupdate run")
            dnsResolverAddrs.forEach { resolver ->
                append(" --dns-resolver-addrs ")
                append(shellQuote(resolver))
            }
            append(" --token ")
            append(shellQuote(token))
        }
    }

    private fun buildBackgroundStartCommand(
        binaryPath: String,
        logPath: String,
        pidPath: String,
        token: String,
    ): String {
        return buildString {
            append("nohup ")
            append(buildTunnelStartCommand(binaryPath, token))
            append(" > ")
            append(shellQuote(logPath))
            append(" 2>&1 < /dev/null & echo \$! > ")
            append(shellQuote(pidPath))
        }
    }

    private fun stagePackagedBinaryIfNeeded(context: Context): String? {
        val packagedFile = packagedAppBinaryFile(context)
        if (!packagedFile.exists()) {
            return "APK 内未找到 cloudflared。请确认已打包 arm64-v8a/libcloudflared.so。"
        }
        return null
    }

    private fun stopLegacyTunnelProcesses() {
        runBasicRootCommand(
            """for pid in ${'$'}(ps -A -o PID,ARGS | grep -E '/data/local/tmp/cloudflared|libcloudflared\.so' | grep -F ' tunnel ' | grep -v grep | awk '{print ${'$'}1}'); do kill ${'$'}pid 2>/dev/null; done""",
            10_000,
        )
    }

    private fun findRunningTunnelPid(selection: TunnelBinarySelection): String? {
        val pidFromFile = runCatching { File(selection.pidPath).readText().trim() }.getOrNull().orEmpty()
        if (pidFromFile.isNotBlank()) {
            val result = runBasicRootCommand(
                """
                if ps -A -o PID,ARGS | awk -v target=${shellQuote(pidFromFile)} -v path=${shellQuote(selection.path)} '
                    ${'$'}1 == target && index(${ '$' }0, path) > 0 && index(${ '$' }0, " tunnel ") > 0 { print ${'$'}1; found=1; exit }
                    END { exit(found ? 0 : 1) }
                '; then
                    :
                else
                    exit 1
                fi
                """.trimIndent(),
                10_000,
            )
            val matchedPid = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it == pidFromFile }
            if (result.exitCode == 0 && matchedPid != null) {
                return matchedPid
            }
        }

        val fallback = runBasicRootCommand(
            """
            ps -A -o PID,ARGS | awk -v path=${shellQuote(selection.path)} '
                index(${ '$' }0, path) > 0 && index(${ '$' }0, " tunnel ") > 0 { print ${'$'}1; exit }
            '
            """.trimIndent(),
            10_000,
        )
        val fallbackPid = fallback.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!fallbackPid.isNullOrBlank()) {
            runCatching { File(selection.pidPath).writeText(fallbackPid) }
        }
        return fallbackPid
    }

    private fun buildRootStartStrategies(command: String): List<RootCommandStrategy> {
        val adbdPid = findAdbdPid()
        val argsVariants = buildList {
            add(RootCommandStrategy("su -c", listOf("-c", command)))
            add(RootCommandStrategy("su -M -c", listOf("-M", "-c", command)))
            if (!adbdPid.isNullOrBlank()) {
                add(RootCommandStrategy("su -t $adbdPid -c", listOf("-t", adbdPid, "-c", command)))
                add(RootCommandStrategy("su -M -t $adbdPid -c", listOf("-M", "-t", adbdPid, "-c", command)))
            }
        }
        return buildSuExecutableCandidates().flatMap { executable ->
            argsVariants.map { variant ->
                RootCommandStrategy(
                    label = "${executable} ${variant.label.removePrefix("su ")}",
                    command = listOf(executable) + variant.command,
                )
            }
        }
    }

    private fun runBasicRootCommand(command: String, timeoutMs: Long): TunnelShellResult {
        var lastFailure: TunnelShellResult? = null
        buildSuExecutableCandidates().forEach { executable ->
            listOf(
                listOf(executable, "-c", command),
                listOf(executable, "-l", "-c", command),
            ).forEach { candidate ->
                val result = runCommand(candidate, timeoutMs)
                if (result.exitCode == 0) {
                    return result
                }
                lastFailure = result
            }
        }
        return lastFailure ?: TunnelShellResult("su", -1, "未找到可用的 root 命令。")
    }

    private fun findAdbdPid(): String? {
        val result = runBasicRootCommand(
            """ps -A -o PID,USER,ARGS | awk '$2 == "shell" && index($0, "adbd") > 0 { print $1; exit }'""",
            5_000,
        )
        return result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.all(Char::isDigit) }
    }

    private fun buildSuExecutableCandidates(): List<String> {
        return listOf(
            "su",
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/su/bin/su",
        )
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): TunnelShellResult {
        return try {
            val process = ProcessBuilder(command)
                .directory(File("/"))
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = "/"
                    environment()["PATH"] = "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
                    environment()["TMPDIR"] = "/data/local/tmp"
                    environment()["USER"] = "shell"
                    environment()["LOGNAME"] = "shell"
                    environment()["SHELL"] = "/system/bin/sh"
                    environment()["TERM"] = "dumb"
                    environment()["ANDROID_DATA"] = "/data"
                    environment()["ANDROID_ROOT"] = "/system"
                    environment()["ANDROID_STORAGE"] = "/storage"
                    environment()["EXTERNAL_STORAGE"] = "/sdcard"
                    environment()["DOWNLOAD_CACHE"] = "/data/cache"
                    environment()["ASEC_MOUNTPOINT"] = "/mnt/asec"
                    environment()["ANDROID_ART_ROOT"] = "/apex/com.android.art"
                    environment()["ANDROID_I18N_ROOT"] = "/apex/com.android.i18n"
                    environment()["ANDROID_TZDATA_ROOT"] = "/apex/com.android.tzdata"
                }
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!finished) {
                process.destroyForcibly()
                TunnelShellResult(command.firstOrNull().orEmpty(), -1, output)
            } else {
                TunnelShellResult(command.firstOrNull().orEmpty(), process.exitValue(), output)
            }
        } catch (t: Throwable) {
            TunnelShellResult(command.firstOrNull().orEmpty(), -1, t.message.orEmpty())
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun analyzeTunnelLogs(lines: List<String>): TunnelLogHealth {
        var connected = false
        var blockingError: String? = null
        var gracefulStop = false
        lines.forEach { line ->
            val normalized = line.lowercase()
            if (line.contains("Registered tunnel connection", ignoreCase = true)) {
                connected = true
            }
            if (
                normalized.contains("initiating graceful shutdown") ||
                normalized.contains("tunnel server stopped") ||
                normalized.contains("metrics server stopped")
            ) {
                gracefulStop = true
                return@forEach
            }
            if (normalized.contains("failed to fetch features")) {
                return@forEach
            }
            if (
                normalized.contains("could not lookup srv records") ||
                normalized.contains("unable to establish connection with cloudflare edge") ||
                normalized.contains("failed to serve tunnel connection") ||
                normalized.contains("serve tunnel error") ||
                normalized.contains("lookup ") && normalized.contains(" on [::1]:53")
            ) {
                blockingError = line
            }
        }
        return TunnelLogHealth(
            connected = connected,
            blockingError = blockingError,
            gracefulStop = gracefulStop,
        )
    }

    private fun tailLines(file: File, limit: Int): List<String> {
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            file.readLines().takeLast(limit).map { it.trim() }.filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resetRuntimeFiles(selection: TunnelBinarySelection) {
        File(selection.logPath).parentFile?.mkdirs()
        File(selection.logPath).writeText("")
        clearPidFile(selection)
    }

    private fun appendLogLine(selection: TunnelBinarySelection, line: String) {
        runCatching {
            val logFile = File(selection.logPath)
            logFile.parentFile?.mkdirs()
            logFile.appendText(line.trimEnd() + "\n")
        }
    }

    private fun clearPidFile(selection: TunnelBinarySelection) {
        runCatching {
            File(selection.pidPath).parentFile?.mkdirs()
            File(selection.pidPath).writeText("")
        }
    }
}

private data class TunnelShellResult(
    val executable: String,
    val exitCode: Int,
    val stdout: String,
)

private data class TunnelBinarySelection(
    val id: String,
    val path: String,
    val ready: Boolean,
    val sourceLabel: String,
    val logPath: String,
    val pidPath: String,
)

private data class TunnelLogHealth(
    val connected: Boolean,
    val blockingError: String?,
    val gracefulStop: Boolean,
)

private data class RootCommandStrategy(
    val label: String,
    val command: List<String>,
)
