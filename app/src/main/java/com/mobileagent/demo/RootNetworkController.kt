package com.mobileagent.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit


internal data class NetworkSnapshot(
    val currentIpv4: String?,
    val currentIpv6: String?,
    val preferredNetworkLabel: String,
    val wifiConnected: Boolean,
    val details: List<String>,
)

internal data class RotateResult(
    val success: Boolean,
    val oldIpv6: String?,
    val newIpv6: String?,
    val ipChanged: Boolean,
    val message: String,
    val debugLog: List<String>,
)

internal enum class RotateMode(val apiValue: String, val label: String) {
    AIRPLANE("airplane", "飞行模式重连"),
    MOBILE_DATA("mobile_data", "移动数据重连");

    companion object {
        fun fromApiValue(value: String?): RotateMode? {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.apiValue.equals(normalized, ignoreCase = true) }
        }
    }
}

internal class RootNetworkController {
    @Volatile
    private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun refreshSnapshot(): NetworkSnapshot = withContext(Dispatchers.IO) {
        refreshSnapshotBlocking()
    }


    suspend fun rotateIp(holdSeconds: Int, mode: RotateMode): RotateResult = withContext(Dispatchers.IO) {
        val debugLog = mutableListOf<String>()
        val before = refreshSnapshotBlocking(debugLog, stage = "切换前")
        debugLog += "切换前网络：${before.preferredNetworkLabel}，IPv6：${before.currentIpv6 ?: "未获取"}，模式：${mode.label}"

        val rootCheck = runRootCommand("id", timeoutMs = 20_000)
        debugLog += summarizeCommand("Root 检测", rootCheck)
        if (rootCheck.exitCode != 0 || !rootCheck.stdout.contains("uid=0")) {
            return@withContext RotateResult(
                success = false,
                oldIpv6 = before.currentIpv6,
                newIpv6 = before.currentIpv6,
                ipChanged = false,
                message = "未获得 root 权限，请在 Magisk 中允许此应用后重试",
                debugLog = debugLog,
            )
        }

        val hold = holdSeconds.coerceIn(3, 60)
        val enableResult = when (mode) {
            RotateMode.AIRPLANE -> setAirplaneMode(enabled = true, debugLog = debugLog)
            RotateMode.MOBILE_DATA -> setMobileDataEnabled(enabled = false, debugLog = debugLog)
        }
        if (!enableResult.first) {
            return@withContext RotateResult(
                success = false,
                oldIpv6 = before.currentIpv6,
                newIpv6 = before.currentIpv6,
                ipChanged = false,
                message = enableResult.second,
                debugLog = debugLog,
            )
        }

        debugLog += "${mode.label}保持 ${hold} 秒"
        Thread.sleep(hold * 1000L)

        val disableResult = when (mode) {
            RotateMode.AIRPLANE -> setAirplaneMode(enabled = false, debugLog = debugLog)
            RotateMode.MOBILE_DATA -> setMobileDataEnabled(enabled = true, debugLog = debugLog)
        }
        if (!disableResult.first) {
            return@withContext RotateResult(
                success = false,
                oldIpv6 = before.currentIpv6,
                newIpv6 = before.currentIpv6,
                ipChanged = false,
                message = disableResult.second,
                debugLog = debugLog,
            )
        }

        var after = refreshSnapshotBlocking(debugLog, stage = "恢复轮询 #0")
        repeat(30) { index ->
            if (!after.currentIpv6.isNullOrBlank()) return@repeat
            Thread.sleep(2_000)
            after = refreshSnapshotBlocking(debugLog, stage = "恢复轮询 #${index + 1}")
        }

        val newIpv6 = after.currentIpv6
        if (newIpv6.isNullOrBlank()) {
            return@withContext RotateResult(
                success = false,
                oldIpv6 = before.currentIpv6,
                newIpv6 = null,
                ipChanged = false,
                message = "网络恢复超时，未获取到新的 IPv6",
                debugLog = debugLog,
            )
        }

        val ipChanged = before.currentIpv6?.let { it != newIpv6 } ?: true
        RotateResult(
            success = true,
            oldIpv6 = before.currentIpv6,
            newIpv6 = newIpv6,
            ipChanged = ipChanged,
            message = if (ipChanged) {
                "${mode.label}完成，新的 IPv6 已生效"
            } else {
                "网络已恢复，但 IPv6 未发生变化"
            },
            debugLog = debugLog,
        )
    }

    private fun refreshSnapshotBlocking(debugLog: MutableList<String>? = null, stage: String? = null): NetworkSnapshot {
        val activeNetwork = resolveActiveNetworkInfo()
        val ipv4AddrResult = runNetworkCommand("ip -4 addr show", timeoutMs = 10_000)
        val ipv4RouteResult = runNetworkCommand("ip -4 route show default", timeoutMs = 10_000)
        val addrResult = runNetworkCommand("ip -6 addr show", timeoutMs = 10_000)
        val routeResult = runNetworkCommand("ip -6 route show default", timeoutMs = 10_000)
        val allIpv4Addresses = parseGlobalIpv4Addresses(ipv4AddrResult.stdout)
        val allAddresses = parseGlobalIpv6Addresses(addrResult.stdout)
        val defaultIpv4Interface = parseDefaultInterface(ipv4RouteResult.stdout)
        val defaultInterface = parseDefaultInterface(routeResult.stdout)
        val preferredIpv4 = selectPreferredAddress(
            addresses = allIpv4Addresses.filterNot { isPrivateIpv4(it.address) },
            activeInterface = activeNetwork.interfaceName,
            defaultInterface = defaultIpv4Interface,
        )
        val preferred = selectPreferredAddress(
            addresses = allAddresses,
            activeInterface = activeNetwork.interfaceName,
            defaultInterface = defaultInterface,
        )
        val details = buildList {
            stage?.let { add("${it}：${summarizeCommand("IPv4 地址采集", ipv4AddrResult)}") }
            stage?.let { add("${it}：${summarizeCommand("IPv4 默认路由", ipv4RouteResult)}") }
            stage?.let { add("${it}：${summarizeCommand("IPv6 地址采集", addrResult)}") }
            stage?.let { add("${it}：${summarizeCommand("默认路由", routeResult)}") }
            add("系统活跃网络：${activeNetwork.transportLabel}")
            add("系统活跃接口：${activeNetwork.interfaceName ?: "未识别"}")
            add(
                if (defaultIpv4Interface.isNullOrBlank()) {
                    "默认 IPv4 路由接口：未识别"
                } else {
                    "默认 IPv4 路由接口：$defaultIpv4Interface"
                }
            )
            add(
                if (defaultInterface.isNullOrBlank()) {
                    "默认 IPv6 路由接口：未识别"
                } else {
                    "默认 IPv6 路由接口：${defaultInterface}"
                }
            )
            add(
                if (allIpv4Addresses.isEmpty()) {
                    "候选 IPv4：未发现可用 IPv4"
                } else {
                    "候选 IPv4：${allIpv4Addresses.joinToString("；") { "${it.interfaceName}=${it.address}" }}"
                }
            )
            add(
                if (preferredIpv4 == null) {
                    "最终选择 IPv4：未获取公网 IPv4"
                } else {
                    "最终选择 IPv4：${preferredIpv4.interfaceName} -> ${preferredIpv4.address}"
                }
            )
            add(
                if (allAddresses.isEmpty()) {
                    "候选地址：未发现全局 IPv6"
                } else {
                    "候选地址：${allAddresses.joinToString("；") { "${it.interfaceName}=${it.address}" }}"
                }
            )
            add(
                if (preferred == null) {
                    "最终选择：未获取到可用 IPv6"
                } else {
                    "最终选择：${preferred.interfaceName} -> ${preferred.address}"
                }
            )
        }
        debugLog?.addAll(details)
        return NetworkSnapshot(
            currentIpv4 = preferredIpv4?.address,
            currentIpv6 = preferred?.address,
            preferredNetworkLabel = when {
                activeNetwork.transportLabel != "未连接" -> activeNetwork.transportLabel
                preferred != null -> interfaceToNetworkLabel(preferred.interfaceName)
                preferredIpv4 != null -> interfaceToNetworkLabel(preferredIpv4.interfaceName)
                else -> "未连接"
            },
            wifiConnected = activeNetwork.wifiConnected || allAddresses.any { it.interfaceName.lowercase().startsWith("wlan") },
            details = details,
        )
    }


    private fun setAirplaneMode(enabled: Boolean, debugLog: MutableList<String>): Pair<Boolean, String> {
        val primaryCommand = if (enabled) {
            "cmd connectivity airplane-mode enable"
        } else {
            "cmd connectivity airplane-mode disable"
        }
        val primary = runRootCommand(primaryCommand, timeoutMs = 15_000)
        debugLog += summarizeCommand(if (enabled) "开启飞行模式" else "关闭飞行模式", primary)
        if (primary.exitCode == 0 && verifyAirplaneMode(enabled, debugLog)) {
            return true to ""
        }

        val fallbackCommand = if (enabled) {
            "settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
        } else {
            "settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        }
        val fallback = runRootCommand(fallbackCommand, timeoutMs = 15_000)
        debugLog += summarizeCommand(if (enabled) "开启飞行模式回退" else "关闭飞行模式回退", fallback)
        if (fallback.exitCode == 0 && verifyAirplaneMode(enabled, debugLog)) {
            return true to ""
        }

        val reason = listOf(primary.stdout, fallback.stdout)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("；")
            .ifBlank { "系统命令执行失败" }
        return false to "切换飞行模式失败：$reason"
    }

    private fun verifyAirplaneMode(enabled: Boolean, debugLog: MutableList<String>): Boolean {
        val result = runRootCommand("settings get global airplane_mode_on", timeoutMs = 10_000)
        debugLog += summarizeCommand("飞行模式状态校验", result)
        return result.exitCode == 0 && result.stdout.trim() == if (enabled) "1" else "0"
    }

    private fun setMobileDataEnabled(enabled: Boolean, debugLog: MutableList<String>): Pair<Boolean, String> {
        val primaryCommand = if (enabled) "svc data enable" else "svc data disable"
        val primary = runRootCommand(primaryCommand, timeoutMs = 15_000)
        debugLog += summarizeCommand(if (enabled) "开启移动数据" else "关闭移动数据", primary)
        if (primary.exitCode == 0 && verifyMobileDataEnabled(enabled, debugLog)) {
            return true to ""
        }

        val fallbackCommand = if (enabled) {
            "cmd phone data enable"
        } else {
            "cmd phone data disable"
        }
        val fallback = runRootCommand(fallbackCommand, timeoutMs = 15_000)
        debugLog += summarizeCommand(if (enabled) "开启移动数据回退" else "关闭移动数据回退", fallback)
        if (fallback.exitCode == 0 && verifyMobileDataEnabled(enabled, debugLog)) {
            return true to ""
        }

        val reason = listOf(primary.stdout, fallback.stdout)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("；")
            .ifBlank { "系统命令执行失败" }
        return false to "切换移动数据失败：$reason"
    }

    private fun verifyMobileDataEnabled(enabled: Boolean, debugLog: MutableList<String>): Boolean {
        val result = runRootCommand("settings get global mobile_data", timeoutMs = 10_000)
        debugLog += summarizeCommand("移动数据状态校验", result)
        return result.exitCode == 0 && result.stdout.trim() == if (enabled) "1" else "0"
    }

    private fun runRootCommand(command: String, timeoutMs: Long): ShellResult {
        return runCommandWithFallback(
            executables = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"),
            arguments = listOf("-c", command),
            timeoutMs = timeoutMs,
        )
    }

    private fun runShellCommand(command: String, timeoutMs: Long): ShellResult {
        return runCommandWithFallback(
            executables = listOf("/system/bin/sh", "sh"),
            arguments = listOf("-c", command),
            timeoutMs = timeoutMs,
        )
    }

    private fun runNetworkCommand(command: String, timeoutMs: Long): ShellResult {
        val shellResult = runShellCommand(command, timeoutMs)
        if (shellResult.exitCode == 0 && shellResult.stdout.isNotBlank()) return shellResult

        val rootResult = runRootCommand(command, timeoutMs)
        return when {
            rootResult.exitCode == 0 && rootResult.stdout.isNotBlank() -> rootResult
            rootResult.exitCode == 0 -> rootResult
            else -> shellResult
        }
    }

    private fun runCommandWithFallback(
        executables: List<String>,
        arguments: List<String>,
        timeoutMs: Long,
    ): ShellResult {
        var lastResult: ShellResult? = null
        executables.distinct().forEach { executable ->
            val result = runCommand(listOf(executable) + arguments, timeoutMs)
            lastResult = result
            val missingBinary = result.exitCode == -1 && result.stdout.contains("No such file", ignoreCase = true)
            if (!missingBinary) {
                return result
            }
        }
        return lastResult ?: ShellResult(executable = executables.firstOrNull().orEmpty(), exitCode = -1, stdout = "未找到可执行文件")
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!finished) {
                process.destroyForcibly()
                ShellResult(executable = command.firstOrNull().orEmpty(), exitCode = -1, stdout = output)
            } else {
                ShellResult(executable = command.firstOrNull().orEmpty(), exitCode = process.exitValue(), stdout = output)
            }
        } catch (t: Throwable) {
            ShellResult(executable = command.firstOrNull().orEmpty(), exitCode = -1, stdout = t.message.orEmpty())
        }
    }

    private fun summarizeCommand(label: String, result: ShellResult): String {
        val output = result.stdout.trim().replace("\n", " | ")
        val preview = output.take(180).ifBlank { "无输出" }
        return "$label：exit=${result.exitCode} via=${result.executable} -> $preview"
    }

    private fun parseGlobalIpv6Addresses(raw: String): List<InterfaceAddress> {
        val interfaceRegex = Regex("^\\d+:\\s+([A-Za-z0-9_@.-]+):")
        val ipv6Regex = Regex("inet6\\s+([0-9a-fA-F:]+)/\\d+\\s+scope\\s+global")
        val results = mutableListOf<InterfaceAddress>()
        var currentInterface: String? = null

        raw.lineSequence().forEach { line ->
            interfaceRegex.find(line.trim())?.let { match ->
                currentInterface = match.groupValues[1].substringBefore('@')
            }
            val address = ipv6Regex.find(line)?.groupValues?.getOrNull(1)
            if (address != null && !address.startsWith("fd", ignoreCase = true) && !address.startsWith("fe80", ignoreCase = true)) {
                currentInterface?.let { results += InterfaceAddress(it, address) }
            }
        }

        return results
    }

    private fun parseGlobalIpv4Addresses(raw: String): List<InterfaceAddress> {
        val interfaceRegex = Regex("^\\d+:\\s+([A-Za-z0-9_@.-]+):")
        val ipv4Regex = Regex("inet\\s+((?:\\d{1,3}\\.){3}\\d{1,3})/\\d+\\s+scope\\s+global")
        val results = mutableListOf<InterfaceAddress>()
        var currentInterface: String? = null

        raw.lineSequence().forEach { line ->
            interfaceRegex.find(line.trim())?.let { match ->
                currentInterface = match.groupValues[1].substringBefore('@')
            }
            val address = ipv4Regex.find(line)?.groupValues?.getOrNull(1)
            if (address != null) {
                currentInterface?.let { results += InterfaceAddress(it, address) }
            }
        }

        return results
    }

    private fun parseDefaultInterface(raw: String): String? {
        val devRegex = Regex("\\bdev\\s+([A-Za-z0-9_@.-]+)")
        return raw.lineSequence()
            .mapNotNull { line -> devRegex.find(line)?.groupValues?.getOrNull(1)?.substringBefore('@') }
            .firstOrNull()
    }

    private fun selectPreferredAddress(
        addresses: List<InterfaceAddress>,
        activeInterface: String?,
        defaultInterface: String?,
    ): InterfaceAddress? {
        activeInterface?.let { preferredInterface ->
            addresses.firstOrNull { it.interfaceName == preferredInterface }?.let { return it }
        }
        defaultInterface?.let { preferredInterface ->
            addresses.firstOrNull { it.interfaceName == preferredInterface }?.let { return it }
        }
        return addresses.maxWithOrNull(
            compareBy<InterfaceAddress> { scoreInterface(it.interfaceName) }
                .thenByDescending { it.address }
        )
    }


    private fun scoreInterface(name: String): Int {
        val lowerName = name.lowercase()
        return when {
            lowerName.startsWith("wlan") -> 100
            lowerName.startsWith("rmnet") ||
                lowerName.startsWith("ccmni") ||
                lowerName.startsWith("pdp") ||
                lowerName.startsWith("radio") -> 80

            else -> 10
        }
    }

    private fun resolveActiveNetworkInfo(): ActiveNetworkInfo {
        val context = appContext ?: return ActiveNetworkInfo()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ActiveNetworkInfo()
        val activeNetwork = connectivityManager.activeNetwork ?: return ActiveNetworkInfo()
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val transportLabel = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "蜂窝网络"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> linkProperties?.interfaceName ?: "已连接"
        }
        return ActiveNetworkInfo(
            interfaceName = linkProperties?.interfaceName,
            transportLabel = transportLabel,
            wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        )
    }


    private fun interfaceToNetworkLabel(name: String): String {
        val lowerName = name.lowercase()
        return when {
            lowerName.startsWith("rmnet") ||
                lowerName.startsWith("ccmni") ||
                lowerName.startsWith("pdp") ||
                lowerName.startsWith("radio") -> "蜂窝网络"

            lowerName.startsWith("wlan") -> "Wi‑Fi"
            else -> name
        }
    }

    private fun isPrivateIpv4(address: String): Boolean {
        return address.startsWith("10.") ||
            address.startsWith("192.168.") ||
            address.startsWith("127.") ||
            address.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(address) ||
            Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(address)
    }
}

private data class ShellResult(
    val executable: String,
    val exitCode: Int,
    val stdout: String,
)

private data class InterfaceAddress(
    val interfaceName: String,
    val address: String,
)

private data class ActiveNetworkInfo(
    val interfaceName: String? = null,
    val transportLabel: String = "未连接",
    val wifiConnected: Boolean = false,
)
