package com.mobileagent.demo

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private const val keyBinarySource = "cloudflared_binary_source"

    private const val binarySourceApp = "app"
    private const val binarySourceDownloaded = "downloaded"
    private const val binarySourceTermux = "termux"

    private const val appBinaryDefaultPath = "/data/user/0/com.mobileagent.demo/files/cloudflared"
    private const val appLogDefaultPath = "/data/user/0/com.mobileagent.demo/files/mobile-agent-cloudflared.log"
    private const val appPidDefaultPath = "/data/user/0/com.mobileagent.demo/files/mobile-agent-cloudflared.pid"
    private const val downloadedBinaryPath = "/data/local/tmp/cloudflared"
    private const val downloadedLogPath = "/data/local/tmp/mobile-agent-cloudflared.log"
    private const val downloadedPidPath = "/data/local/tmp/mobile-agent-cloudflared.pid"

    private const val termuxPackageName = "com.termux"
    private const val termuxPrefix = "/data/data/com.termux/files/usr"
    private const val termuxHome = "/data/data/com.termux/files/home"
    private const val termuxBinaryPath = "$termuxPrefix/bin/cloudflared"
    private const val termuxLogPath = "$termuxHome/.mobile-agent-cloudflared.log"
    private const val termuxPidPath = "$termuxHome/.mobile-agent-cloudflared.pid"

    private val termuxRepoBaseUrls = listOf(
        "https://packages.termux.dev/apt/termux-main",
        "https://packages-cf.termux.dev/apt/termux-main",
    )
    private const val packageName = "cloudflared"
    private const val logLineLimit = 80
    private const val startupPollCount = 10
    private const val startupPollDelayMs = 2_000L
    private const val tunnelDnsResolverArgs = "--dns-resolver-addrs 1.1.1.1:53 --dns-resolver-addrs 8.8.8.8:53"

    @Volatile
    private var latestStatus = TunnelStatusSnapshot(
        tokenBound = false,
        running = false,
        statusLabel = "未绑定",
        domain = "未配置",
        binaryPath = appBinaryDefaultPath,
        binaryReady = false,
        binaryVersion = null,
        downloadStatus = "未下载",
        deviceAbi = resolveDownloadSpec().abiLabel,
        pid = null,
        logLines = emptyList(),
        lastError = null,
    )

    fun getSavedToken(context: Context): String = prefs(context).getString(keyToken, "").orEmpty()

    fun getSavedDomain(context: Context): String = prefs(context).getString(keyDomain, "").orEmpty()

    private fun appBinaryFile(context: Context): File = File(context.applicationContext.filesDir, "cloudflared")

    private fun appLogFile(context: Context): File = File(context.applicationContext.filesDir, "mobile-agent-cloudflared.log")

    private fun appPidFile(context: Context): File = File(context.applicationContext.filesDir, "mobile-agent-cloudflared.pid")

    suspend fun bind(context: Context, token: String, domain: String): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        prefs(context)
            .edit()
            .putString(keyToken, token.trim())
            .putString(keyDomain, domain.trim())
            .apply()
        refreshStatus(context, lastErrorOverride = null)
    }

    suspend fun downloadBinary(context: Context, force: Boolean = false): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val appSelection = resolveSpecificBinarySelection(context, binarySourceApp)
        if (appSelection.ready && !force) {
            return@withContext refreshStatus(context, lastErrorOverride = null)
        }

        val spec = resolveDownloadSpec()
        if (!spec.downloadSupported) {
            return@withContext refreshStatus(
                context,
                lastErrorOverride = "当前 ABI ${spec.abiLabel} 不支持内置 Android 包下载。",
            )
        }

        val tempPackage = File(context.cacheDir, "$packageName-${spec.termuxArch}.deb")
        val extractedBinary = File(context.cacheDir, "$packageName-android-${spec.termuxArch}")
        val downloadError = try {
            tempPackage.parentFile?.mkdirs()
            val packageUrl = resolvePackageDownloadUrl(spec)
            downloadFile(packageUrl, tempPackage)
            extractCloudflaredBinary(tempPackage, extractedBinary)
            installBinaryForApp(context, extractedBinary)
            val appUsable = probeAppBinary(context).exitCode == 0
            val rootProbe = probeRootAccess()
            val rootInstallError = if (isRootAccessible(rootProbe)) {
                try {
                    installBinaryForRoot(extractedBinary)
                    null
                } catch (t: Throwable) {
                    t.message?.trim().orEmpty().ifBlank { "安装 Root cloudflared 失败。" }
                }
            } else {
                null
            }

            when {
                appUsable -> {
                    saveBinarySource(context, binarySourceApp)
                    null
                }
                rootInstallError == null && resolveSpecificBinarySelection(context, binarySourceDownloaded).ready -> {
                    saveBinarySource(context, binarySourceDownloaded)
                    null
                }
                isRootPermissionDenied(rootProbe) -> {
                    "应用私有目录中的 cloudflared 在当前真机环境不可执行，请先在 Magisk 中为本应用授予 Root 权限。"
                }
                rootInstallError != null -> rootInstallError
                else -> "应用私有目录中的 cloudflared 在当前真机环境不可执行。"
            }
        } catch (t: Throwable) {
            t.message?.trim().orEmpty().ifBlank { "下载内置 cloudflared 失败。" }
        } finally {
            tempPackage.delete()
            extractedBinary.delete()
        }

        refreshStatus(context, lastErrorOverride = downloadError)
    }

    suspend fun refreshStatus(
        context: Context,
        lastErrorOverride: String? = latestStatus.lastError,
    ): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val selection = resolveBinarySelection(context, getSavedBinarySource(context))
        buildStatus(context, selection, lastErrorOverride)
    }

    suspend fun start(context: Context): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        val token = getSavedToken(context)
        if (token.isBlank()) {
            return@withContext refreshStatus(context, lastErrorOverride = "请先绑定 Tunnel Token。")
        }

        var selection = resolveBinarySelection(context, getSavedBinarySource(context))
        val appSelection = resolveSpecificBinarySelection(context, binarySourceApp)
        val downloadedSelection = resolveSpecificBinarySelection(context, binarySourceDownloaded)
        if (!selection.ready && !appSelection.ready && !downloadedSelection.ready) {
            downloadBinary(context, force = false)
            selection = resolveBinarySelection(context, getSavedBinarySource(context))
        }
        if (!selection.ready) {
            return@withContext buildStatus(context, selection, "当前没有可运行的 cloudflared 二进制文件。")
        }

        stopInternal()
        var startResult = startSelection(selection, token)
        var status = waitForStableStatus(context, selection, startResult.stdout.trim().ifBlank { null })

        if (shouldFallbackToTermux(status) && selection.id != binarySourceTermux) {
            val termuxSelection = resolveSpecificBinarySelection(context, binarySourceTermux)
            if (termuxSelection.ready) {
                stopInternal()
                startResult = startSelection(termuxSelection, token)
                status = waitForStableStatus(context, termuxSelection, startResult.stdout.trim().ifBlank { null })
                if (status.running) {
                    saveBinarySource(context, binarySourceTermux)
                }
                return@withContext status
            }
        }

        if (status.running) {
            saveBinarySource(context, selection.id)
        }
        status
    }

    suspend fun stop(context: Context): TunnelStatusSnapshot = withContext(Dispatchers.IO) {
        stopInternal()
        Thread.sleep(1_000)
        refreshStatus(context, lastErrorOverride = null)
    }

    fun getLatestStatus(): TunnelStatusSnapshot = latestStatus

    private fun buildStatus(
        context: Context,
        selection: TunnelBinarySelection,
        lastErrorOverride: String?,
    ): TunnelStatusSnapshot {
        val token = getSavedToken(context)
        val savedDomain = getSavedDomain(context).ifBlank { "未配置" }
        val spec = resolveDownloadSpec()
        val escapedBinaryPath = escapeForSingleQuotes(selection.path)
        val versionCheck = if (selection.ready) {
            runSelectionCommand(selection, "'$escapedBinaryPath' --version", 15_000)
        } else {
            null
        }
        val binaryVersion = versionCheck
            ?.stdout
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
        val pidResult = runSelectionCommand(
            selection,
            "if [ -f '${selection.pidPath}' ]; then pid=\$(cat '${selection.pidPath}'); kill -0 \"\$pid\" 2>/dev/null && echo \"\$pid\"; fi",
            10_000,
        )
        val running = pidResult.exitCode == 0 && pidResult.stdout.trim().isNotBlank()
        val tailResult = runSelectionCommand(
            selection,
            "if [ -f '${selection.logPath}' ]; then tail -n $logLineLimit '${selection.logPath}'; fi",
            10_000,
        )
        val tunnelLogs = tailResult.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(logLineLimit)
        val logHealth = analyzeTunnelLogs(tunnelLogs)
        val appReady = resolveSpecificBinarySelection(context, binarySourceApp).ready
        val bundledReady = resolveSpecificBinarySelection(context, binarySourceDownloaded).ready
        val termuxReady = resolveSpecificBinarySelection(context, binarySourceTermux).ready

        val computedError = when {
            lastErrorOverride != null -> lastErrorOverride
            token.isBlank() -> null
            !selection.ready -> null
            running && logHealth.connected -> null
            !running && logHealth.gracefulStop -> null
            logHealth.blockingError != null -> logHealth.blockingError
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
                !running && logHealth.gracefulStop -> "已停止"
                computedError != null -> "已停止（有错误）"
                else -> "已停止"
            },
            domain = savedDomain,
            binaryPath = selection.path,
            binaryReady = selection.ready,
            binaryVersion = binaryVersion,
            downloadStatus = buildDownloadStatus(selection, appReady, bundledReady, termuxReady, spec),
            deviceAbi = spec.abiLabel,
            pid = pidResult.stdout.trim().ifBlank { null },
            logLines = buildList {
                add("ABI: ${spec.abiLabel}")
                add("Source: ${selection.sourceLabel}")
                add("Binary: ${selection.path}")
                add("Log: ${selection.logPath}")
                binaryVersion?.let { add("Version: $it") }
                addAll(tunnelLogs)
            }.takeLast(logLineLimit),
            lastError = computedError,
        )
        return latestStatus
    }

    private fun buildDownloadStatus(
        selection: TunnelBinarySelection,
        appReady: Boolean,
        bundledReady: Boolean,
        termuxReady: Boolean,
        spec: CloudflaredDownloadSpec,
    ): String {
        val appStatus = if (appReady) "应用私有目录已就绪" else "应用私有目录未就绪"
        val bundledStatus = when {
            bundledReady -> "内置包已就绪"
            spec.downloadSupported -> "内置包可下载"
            else -> "内置包不支持"
        }
        val termuxStatus = if (termuxReady) "Termux 可用" else "Termux 不可用"
        return when (selection.id) {
            binarySourceApp -> "当前使用应用私有二进制（$appStatus，$bundledStatus，$termuxStatus）"
            binarySourceTermux -> "当前使用 Termux 回退方案（$bundledStatus，$termuxStatus）"
            else -> "当前使用内置二进制（$appStatus，$bundledStatus，$termuxStatus）"
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
            if (status.running && status.logLines.any { line -> line.contains("Registered tunnel connection", ignoreCase = true) }) {
                return status
            }
            if (!status.running && status.lastError != null) {
                return status
            }
        }
        return buildStatus(context, selection, lastErrorOverride = null)
    }

    private fun shouldFallbackToTermux(status: TunnelStatusSnapshot): Boolean {
        val combined = buildString {
            status.lastError?.let { appendLine(it) }
            status.logLines.forEach { appendLine(it) }
        }.lowercase()
        return combined.contains("[::1]:53") || combined.contains("could not lookup srv records")
    }

    private fun startSelection(selection: TunnelBinarySelection, token: String): TunnelShellResult {
        val escapedBinaryPath = escapeForSingleQuotes(selection.path)
        val escapedToken = escapeForSingleQuotes(token)
        val startCommand = when (selection.launchMode) {
            TunnelLaunchMode.APP -> "chmod 700 '$escapedBinaryPath'; rm -f '${selection.logPath}' '${selection.pidPath}'; nohup '$escapedBinaryPath' tunnel --no-autoupdate run $tunnelDnsResolverArgs --token '$escapedToken' > '${selection.logPath}' 2>&1 & echo \$! > '${selection.pidPath}'"
            TunnelLaunchMode.ROOT -> "chmod 755 '$escapedBinaryPath'; rm -f '${selection.logPath}' '${selection.pidPath}'; nohup '$escapedBinaryPath' tunnel --no-autoupdate run $tunnelDnsResolverArgs --token '$escapedToken' > '${selection.logPath}' 2>&1 & echo \$! > '${selection.pidPath}'"
            TunnelLaunchMode.TERMUX -> "rm -f '${selection.logPath}' '${selection.pidPath}'; nohup '$escapedBinaryPath' tunnel run $tunnelDnsResolverArgs --token '$escapedToken' > '${selection.logPath}' 2>&1 & echo \$! > '${selection.pidPath}'"
        }
        return runSelectionCommand(selection, startCommand, 15_000)
    }

    private fun stopInternal() {
        runAppCommand(
            "if [ -f '$appPidDefaultPath' ]; then kill \$(cat '$appPidDefaultPath') 2>/dev/null; rm -f '$appPidDefaultPath'; fi",
            10_000,
        )
        runRootCommand(
            "if [ -f '$downloadedPidPath' ]; then kill \$(cat '$downloadedPidPath') 2>/dev/null; rm -f '$downloadedPidPath'; fi; " +
                "if [ -f '$termuxPidPath' ]; then kill \$(cat '$termuxPidPath') 2>/dev/null; rm -f '$termuxPidPath'; fi; " +
                "for pid in \$(pidof cloudflared 2>/dev/null); do kill \$pid 2>/dev/null; done",
            15_000,
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun getSavedBinarySource(context: Context): String {
        return prefs(context).getString(keyBinarySource, binarySourceApp).orEmpty().ifBlank { binarySourceApp }
    }

    private fun saveBinarySource(context: Context, sourceId: String) {
        prefs(context).edit().putString(keyBinarySource, sourceId).apply()
    }

    private fun resolveBinarySelection(context: Context, preferredSource: String): TunnelBinarySelection {
        val selections = buildBinarySelections(context)
        return selections.firstOrNull { it.id == preferredSource && it.ready }
            ?: selections.firstOrNull { it.id == binarySourceApp && it.ready }
            ?: selections.firstOrNull { it.id == binarySourceDownloaded && it.ready }
            ?: selections.firstOrNull { it.id == binarySourceTermux && it.ready }
            ?: selections.firstOrNull { it.id == preferredSource }
            ?: selections.first()
    }

    private fun resolveSpecificBinarySelection(context: Context, sourceId: String): TunnelBinarySelection {
        return buildBinarySelections(context).firstOrNull { it.id == sourceId }
            ?: TunnelBinarySelection(
                id = sourceId,
                path = appBinaryFile(context).absolutePath,
                ready = false,
                sourceLabel = "未知来源",
                logPath = appLogFile(context).absolutePath,
                pidPath = appPidFile(context).absolutePath,
                launchMode = TunnelLaunchMode.APP,
            )
    }

    private fun buildBinarySelections(context: Context): List<TunnelBinarySelection> {
        val appBinary = appBinaryFile(context)
        val appLog = appLogFile(context)
        val appPid = appPidFile(context)
        val appReady = appBinary.exists() && appBinary.canExecute() && probeAppBinary(context).exitCode == 0
        val rootProbe = probeRootAccess()
        val rootAccessible = isRootAccessible(rootProbe)
        val downloadedExists = runAppCommand("if [ -x '$downloadedBinaryPath' ]; then echo ready; else echo missing; fi", 10_000)
            .stdout
            .contains("ready")
        val downloadedReady = rootAccessible && downloadedExists
        val termuxReady = runTermuxCommand("test -x '$termuxBinaryPath' && echo ready", 10_000)
            .stdout
            .contains("ready")
        return listOf(
            TunnelBinarySelection(
                id = binarySourceApp,
                path = appBinary.absolutePath,
                ready = appReady,
                sourceLabel = "应用私有二进制",
                logPath = appLog.absolutePath,
                pidPath = appPid.absolutePath,
                launchMode = TunnelLaunchMode.APP,
            ),
            TunnelBinarySelection(
                id = binarySourceDownloaded,
                path = downloadedBinaryPath,
                ready = downloadedReady,
                sourceLabel = "内置 Android 二进制（root）",
                logPath = downloadedLogPath,
                pidPath = downloadedPidPath,
                launchMode = TunnelLaunchMode.ROOT,
            ),
            TunnelBinarySelection(
                id = binarySourceTermux,
                path = termuxBinaryPath,
                ready = termuxReady,
                sourceLabel = "Termux 回退二进制",
                logPath = termuxLogPath,
                pidPath = termuxPidPath,
                launchMode = TunnelLaunchMode.TERMUX,
            ),
        )
    }

    private fun resolveDownloadSpec(): CloudflaredDownloadSpec {
        val abis = Build.SUPPORTED_ABIS.orEmpty().toList()
        return when {
            abis.any { it.contains("arm64", ignoreCase = true) } -> CloudflaredDownloadSpec(
                abiLabel = "arm64-v8a",
                termuxArch = "aarch64",
                packageLabel = "cloudflared Android package (aarch64)",
                packagesIndexUrl = "/dists/stable/main/binary-aarch64/Packages",
            )
            abis.any { it.contains("armeabi", ignoreCase = true) || it.contains("armv7", ignoreCase = true) } -> CloudflaredDownloadSpec(
                abiLabel = "armeabi-v7a",
                termuxArch = "arm",
                packageLabel = "cloudflared Android package (arm)",
                packagesIndexUrl = "/dists/stable/main/binary-arm/Packages",
            )
            else -> CloudflaredDownloadSpec(
                abiLabel = abis.firstOrNull().orEmpty().ifBlank { "unknown" },
                termuxArch = null,
                packageLabel = "不支持的 ABI",
                packagesIndexUrl = null,
            )
        }
    }

    private fun resolvePackageDownloadUrl(spec: CloudflaredDownloadSpec): String {
        val packagesIndexPath = spec.packagesIndexUrl
            ?: throw IllegalStateException("当前 ABI ${spec.abiLabel} 没有可下载的软件源索引。")
        var lastError: Throwable? = null
        termuxRepoBaseUrls.forEach { repoBaseUrl ->
            try {
                val packageIndex = downloadText("$repoBaseUrl$packagesIndexPath")
                val packageStanza = packageIndex
                    .split("\n\n")
                    .firstOrNull { stanza ->
                        stanza.lineSequence().any { it.trim() == "Package: $packageName" }
                    }
                    ?: throw IllegalStateException("在 Termux 软件包索引中找不到 $packageName。")
                val filename = parseDebianField(packageStanza, "Filename")
                    ?: throw IllegalStateException("Termux 软件包索引中缺少 Filename 字段。")
                return "$repoBaseUrl/${filename.removePrefix("/")}"
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(lastError?.message ?: "无法解析 $packageName 对应的 Termux 包下载地址。")
    }

    private fun parseDebianField(stanza: String, key: String): String? {
        val prefix = "$key:"
        return stanza.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractCloudflaredBinary(packageFile: File, destination: File) {
        val dataArchiveFile = File(packageFile.parentFile, "${packageFile.nameWithoutExtension}-data.tmp")
        val dataArchiveName = try {
            extractDataArchive(packageFile, dataArchiveFile)
        } catch (t: Throwable) {
            dataArchiveFile.delete()
            throw t
        }
        try {
            openTarArchiveInputStream(dataArchiveFile, dataArchiveName).use { tarInput ->
                destination.parentFile?.mkdirs()
                var entry = tarInput.nextTarEntry
                while (entry != null) {
                    val normalizedName = entry.name.removePrefix("./").trim()
                    if (!entry.isDirectory && (normalizedName == "bin/cloudflared" || normalizedName.endsWith("/bin/cloudflared"))) {
                        destination.outputStream().use { output ->
                            tarInput.copyTo(output)
                        }
                        return
                    }
                    entry = tarInput.nextTarEntry
                }
            }
            throw IllegalStateException("软件包中不包含 bin/cloudflared。")
        } finally {
            dataArchiveFile.delete()
        }
    }

    private fun extractDataArchive(packageFile: File, destination: File): String {
        ArArchiveInputStream(BufferedInputStream(packageFile.inputStream())).use { debInput ->
            var entry = debInput.nextArEntry
            while (entry != null) {
                val entryName = entry.name.trim()
                if (entryName.startsWith("data.tar")) {
                    destination.outputStream().use { output ->
                        debInput.copyTo(output)
                    }
                    return entryName
                }
                entry = debInput.nextArEntry
            }
        }
        throw IllegalStateException("deb 软件包中不包含 data.tar 数据段。")
    }

    private fun openTarArchiveInputStream(archiveFile: File, archiveName: String): TarArchiveInputStream {
        val bufferedInput = BufferedInputStream(archiveFile.inputStream())
        val archiveInput = when {
            archiveName.endsWith(".xz") -> XZCompressorInputStream(bufferedInput)
            archiveName.endsWith(".gz") -> GzipCompressorInputStream(bufferedInput)
            archiveName.endsWith(".tar") -> bufferedInput
            else -> throw IllegalStateException("不支持的归档格式：$archiveName")
        }
        return TarArchiveInputStream(archiveInput)
    }

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
                (normalized.contains("lookup ") && normalized.contains(" on [::1]:53"))
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

    private fun downloadFile(url: String, destination: File) {
        val connection = openHttpConnection(url)
        try {
            destination.outputStream().use { output ->
                connection.inputStream.use { input ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadText(url: String): String {
        val connection = openHttpConnection(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "MobileAgent/1.0")
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Download failed with HTTP $responseCode")
        }
        return connection
    }

    private fun escapeForSingleQuotes(value: String): String = value.replace("'", "'\"'\"'")

    private fun installBinaryForApp(context: Context, extractedBinary: File) {
        val target = appBinaryFile(context)
        target.parentFile?.mkdirs()
        extractedBinary.copyTo(target, overwrite = true)
        if (!target.setExecutable(true, false)) {
            throw IllegalStateException("无法为应用私有 cloudflared 设置可执行权限。")
        }
    }

    private fun installBinaryForRoot(extractedBinary: File) {
        val escapedSource = escapeForSingleQuotes(extractedBinary.absolutePath)
        val result = runRootCommand(
            "mkdir -p '/data/local/tmp' && cp '$escapedSource' '$downloadedBinaryPath' && chmod 755 '$downloadedBinaryPath'",
            30_000,
        )
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stdout.trim().ifBlank { "安装 Root cloudflared 失败。" })
        }
    }

    private fun probeAppBinary(context: Context): TunnelShellResult {
        val binaryPath = appBinaryFile(context).absolutePath
        val escapedBinaryPath = escapeForSingleQuotes(binaryPath)
        return runAppCommand("'$escapedBinaryPath' --version", 10_000)
    }

    private fun probeRootAccess(): TunnelShellResult = runRootCommand("id -u", 10_000)

    private fun isRootAccessible(result: TunnelShellResult): Boolean {
        return result.exitCode == 0 && result.stdout.lineSequence().any { it.trim() == "0" }
    }

    private fun isRootPermissionDenied(result: TunnelShellResult): Boolean {
        return !isRootAccessible(result) && result.stdout.contains("Permission denied", ignoreCase = true)
    }

    private fun runSelectionCommand(
        selection: TunnelBinarySelection,
        command: String,
        timeoutMs: Long,
    ): TunnelShellResult {
        return when (selection.launchMode) {
            TunnelLaunchMode.APP -> runAppCommand(command, timeoutMs)
            TunnelLaunchMode.TERMUX -> runTermuxCommand(command, timeoutMs)
            TunnelLaunchMode.ROOT -> runRootCommand(command, timeoutMs)
        }
    }

    private fun runAppCommand(command: String, timeoutMs: Long): TunnelShellResult {
        return runCommand(listOf("sh", "-c", command), timeoutMs)
    }

    private fun runTermuxCommand(command: String, timeoutMs: Long): TunnelShellResult {
        val fullCommand =
            "export PREFIX='$termuxPrefix'; " +
                "export HOME='$termuxHome'; " +
                "export PATH='$termuxPrefix/bin:$termuxPrefix/bin/applets':\$PATH; " +
                command
        val wrapped = "run-as $termuxPackageName sh -c '${escapeForSingleQuotes(fullCommand)}'"
        return runRootCommand(wrapped, timeoutMs)
    }

    private fun runRootCommand(command: String, timeoutMs: Long): TunnelShellResult {
        return runCommandWithFallback(
            executables = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"),
            arguments = listOf("-c", command),
            timeoutMs = timeoutMs,
        )
    }

    private fun runCommandWithFallback(
        executables: List<String>,
        arguments: List<String>,
        timeoutMs: Long,
    ): TunnelShellResult {
        var lastResult: TunnelShellResult? = null
        executables.distinct().forEach { executable ->
            val result = runCommand(listOf(executable) + arguments, timeoutMs)
            lastResult = result
            val missingBinary = result.exitCode == -1 && result.stdout.contains("No such file", ignoreCase = true)
            val permissionDenied = result.exitCode == -1 && result.stdout.contains("Permission denied", ignoreCase = true)
            if (!missingBinary && !permissionDenied) {
                return result
            }
        }
        return lastResult ?: TunnelShellResult(
            executable = executables.firstOrNull().orEmpty(),
            exitCode = -1,
            stdout = "No executable was found.",
        )
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): TunnelShellResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!finished) {
                process.destroyForcibly()
                TunnelShellResult(executable = command.firstOrNull().orEmpty(), exitCode = -1, stdout = output)
            } else {
                TunnelShellResult(executable = command.firstOrNull().orEmpty(), exitCode = process.exitValue(), stdout = output)
            }
        } catch (t: Throwable) {
            TunnelShellResult(executable = command.firstOrNull().orEmpty(), exitCode = -1, stdout = t.message.orEmpty())
        }
    }
}

private enum class TunnelLaunchMode {
    APP,
    ROOT,
    TERMUX,
}

private data class CloudflaredDownloadSpec(
    val abiLabel: String,
    val termuxArch: String?,
    val packageLabel: String,
    val packagesIndexUrl: String?,
) {
    val downloadSupported: Boolean
        get() = termuxArch != null && packagesIndexUrl != null
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
    val launchMode: TunnelLaunchMode,
)

private data class TunnelLogHealth(
    val connected: Boolean,
    val blockingError: String?,
    val gracefulStop: Boolean,
)
