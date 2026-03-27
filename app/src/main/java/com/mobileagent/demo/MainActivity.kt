@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mobileagent.demo

import android.os.Bundle

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MobileAgentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MobileAgentDemoApp()
                }
            }
        }
    }
}

private enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("概览", Icons.Default.Dashboard),
    Nodes("节点", Icons.Default.Dns),
    Network("网络", Icons.Default.SwapHoriz),
    Remote("远程", Icons.Default.Cloud),
    Settings("设置", Icons.Default.Settings),
}

private enum class NodeType(val label: String, val defaultPort: Int, val subtitle: String) {
    Socks5("SOCKS5", 1080, "兼容脚本、客户端和通用代理场景"),
    Http("HTTP", 8080, "适合浏览器和支持 HTTP 代理的软件"),
}

private enum class NodeStatus(val label: String, val color: Color) {
    Running("运行中", Color(0xFF1E8E3E)),
    Stopped("已停止", Color(0xFF6B7280)),
    Starting("启动中", Color(0xFFF59E0B)),
    Error("异常", Color(0xFFDC2626)),
}

private data class ProxyNode(
    val id: Int,
    val name: String,
    val type: NodeType,
    val host: String,
    val port: Int,
    val authEnabled: Boolean,
    val username: String,
    val password: String,
    val status: NodeStatus,
    val currentConnections: Int,
    val lastStarted: String,
)

private data class RotateTask(
    val id: String,
    val title: String,
    val status: String,
    val detail: String,
    val debugLines: List<String> = emptyList(),
)

private data class NetworkState(
    val activeNetwork: String,
    val ipv6: String,
    val wifiConnected: Boolean,
    val lastRotateAt: String,
    val rotateMode: String,
    val holdSeconds: Int,
    val lastError: String?,
    val diagnostics: List<String>,
)


private data class RemoteState(
    val tokenBound: Boolean,
    val running: Boolean,
    val tunnelStatus: String,

    val domain: String,
    val localApi: String,
    val lastError: String?,
    val binaryPath: String,
    val binaryReady: Boolean,
    val binaryVersion: String?,
    val downloadStatus: String,
    val deviceAbi: String,
    val pid: String?,
    val logLines: List<String>,
)



private data class SettingsState(
    val keepServiceAlive: Boolean,
    val preferCellular: Boolean,
    val auditLogs: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAgentDemoApp() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val nodes = remember { mutableStateListOf(*sampleNodes().toTypedArray()) }
    val tasks = remember { mutableStateListOf<RotateTask>() }

    var networkState by remember {
        mutableStateOf(
            NetworkState(
                activeNetwork = "检测中",
                ipv6 = "获取中...",
                wifiConnected = false,
                lastRotateAt = "未执行",
                rotateMode = "飞行模式重连",
                holdSeconds = 10,
                lastError = null,
                diagnostics = emptyList(),
            )


        )
    }
    var remoteState by remember {
        mutableStateOf(
            RemoteState(
                tokenBound = false,
                running = false,
                tunnelStatus = "未绑定",

                domain = "未配置",
                localApi = "127.0.0.1:18080",
                lastError = null,
                binaryPath = "检测中",
                binaryReady = false,
                binaryVersion = null,
                downloadStatus = "APK 内置二进制待检测",
                deviceAbi = "检测中",
                pid = null,
                logLines = emptyList(),
            )


        )
    }
    var settings by remember {
        mutableStateOf(
            SettingsState(
                keepServiceAlive = true,
                preferCellular = true,
                auditLogs = true,
            )
        )
    }
    var showCreateNodeDialog by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<ProxyNode?>(null) }
    var remoteTokenInput by remember { mutableStateOf(TunnelRuntime.getSavedToken(context)) }
    var remoteDomainInput by remember { mutableStateOf(TunnelRuntime.getSavedDomain(context)) }


    fun updateNode(updated: ProxyNode) {

        val index = nodes.indexOfFirst { it.id == updated.id }
        if (index >= 0) nodes[index] = updated
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun copyLines(label: String, lines: List<String>) {
        if (lines.isEmpty()) {
            toast("$label 暂无内容可复制")
            return
        }
        clipboard.setText(AnnotatedString(lines.joinToString(separator = "\n")))
        toast("$label 已复制")
    }

    fun applyTunnelStatus(status: TunnelStatusSnapshot) {

        remoteState = remoteState.copy(
            tokenBound = status.tokenBound,
            running = status.running,
            tunnelStatus = status.statusLabel,

            domain = status.domain,
            lastError = status.lastError,
            binaryPath = status.binaryPath,
            binaryReady = status.binaryReady,
            binaryVersion = status.binaryVersion,
            downloadStatus = status.downloadStatus,
            deviceAbi = status.deviceAbi,
            pid = status.pid,
            logLines = status.logLines,
        )
    }

    fun ApiTaskRecord.toUiTask(): RotateTask {

        return RotateTask(
            id = id,
            title = title,
            status = when (status) {
                "queued" -> "排队中"
                "running" -> "执行中"
                "succeeded" -> "已成功"
                "failed" -> "失败"
                else -> status
            },
            detail = detail,
            debugLines = debugLines,
        )
    }

    suspend fun syncRuntimeState(refreshSnapshot: Boolean, announce: Boolean = false) {
        val serverStatus = MobileAgentRuntime.ensureApiServerStarted()
        val snapshot = if (refreshSnapshot) {
            MobileAgentRuntime.refreshNetworkSnapshot()
        } else {
            MobileAgentRuntime.getCachedSnapshot()
        }
        val tunnelStatus = TunnelRuntime.refreshStatus(context)
        val runtimeTasks = MobileAgentRuntime.listTasks().map { it.toUiTask() }
        tasks.clear()
        tasks.addAll(runtimeTasks)
        val latestTask = runtimeTasks.firstOrNull()
        networkState = networkState.copy(
            activeNetwork = snapshot.preferredNetworkLabel,
            ipv6 = snapshot.currentIpv6 ?: "未获取",
            wifiConnected = snapshot.wifiConnected,
            lastRotateAt = when (latestTask?.status) {
                "执行中", "排队中" -> "进行中"
                "已成功" -> "刚刚"
                "失败" -> "失败"
                else -> networkState.lastRotateAt
            },
            lastError = MobileAgentRuntime.getLatestError(),
            diagnostics = MobileAgentRuntime.getLatestDiagnostics().ifEmpty { snapshot.details },
        )
        remoteState = remoteState.copy(
            tokenBound = tunnelStatus.tokenBound,
            running = tunnelStatus.running,
            tunnelStatus = tunnelStatus.statusLabel,
            domain = tunnelStatus.domain,

            localApi = serverStatus.localApi,
            lastError = tunnelStatus.lastError ?: serverStatus.lastError,
            binaryPath = tunnelStatus.binaryPath,
            binaryReady = tunnelStatus.binaryReady,
            binaryVersion = tunnelStatus.binaryVersion,
            downloadStatus = tunnelStatus.downloadStatus,
            deviceAbi = tunnelStatus.deviceAbi,
            pid = tunnelStatus.pid,
            logLines = tunnelStatus.logLines,
        )

        if (announce) {
            snackbarHostState.showSnackbar("状态已刷新")
        }
    }


    suspend fun runRealRotateTask(taskTitle: String) {
        val task = MobileAgentRuntime.submitRotateTask(
            holdSeconds = networkState.holdSeconds,
            title = taskTitle,
        )
        networkState = networkState.copy(lastRotateAt = "进行中", lastError = null)
        syncRuntimeState(refreshSnapshot = false)
        snackbarHostState.showSnackbar("任务已创建：${task.id}")
    }

    LaunchedEffect(Unit) {
        MobileAgentRuntime.bindContext(context)
        MobileAgentRuntime.ensureApiServerStarted()
        syncRuntimeState(refreshSnapshot = true)
    }


    LaunchedEffect(Unit) {
        while (true) {
            syncRuntimeState(refreshSnapshot = false)
            delay(1000)
        }
    }



    Scaffold(

        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (AppTab.entries[selectedTab]) {
                            AppTab.Overview -> "移动代理"
                            AppTab.Nodes -> "代理节点"
                            AppTab.Network -> "网络控制"
                            AppTab.Remote -> "远程管理"
                            AppTab.Settings -> "设置"
                        }
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (AppTab.entries[selectedTab] == AppTab.Nodes) {
                FloatingActionButton(onClick = { showCreateNodeDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建节点")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { innerPadding ->
        when (AppTab.entries[selectedTab]) {
            AppTab.Overview -> OverviewScreen(
                modifier = Modifier.padding(innerPadding),
                nodes = nodes,
                networkState = networkState,
                remoteState = remoteState,
                tasks = tasks,
                onQuickRotate = {
                    scope.launch {
                        runRealRotateTask("一键换 IP")
                    }
                },

                onNewNode = { showCreateNodeDialog = true },
                onCopyIp = {
                    clipboard.setText(AnnotatedString(networkState.ipv6))
                    toast("当前 IPv6 已复制")
                }
            )

            AppTab.Nodes -> NodesScreen(
                modifier = Modifier.padding(innerPadding),
                nodes = nodes,
                onNodeClick = { selectedNode = it },
                onStartNode = { node ->
                    updateNode(node.copy(status = NodeStatus.Running, lastStarted = "刚刚"))
                    toast("${node.name} 已启动")
                },
                onStopNode = { node ->
                    updateNode(node.copy(status = NodeStatus.Stopped, currentConnections = 0))
                    toast("${node.name} 已停止")
                },
                onCopyAddress = { node ->
                    clipboard.setText(AnnotatedString("[${node.host}]:${node.port}"))
                    toast("访问地址已复制")
                }
            )

            AppTab.Network -> NetworkScreen(
                modifier = Modifier.padding(innerPadding),
                networkState = networkState,
                tasks = tasks,
                onRotate = {
                    scope.launch {
                        runRealRotateTask("更换出口 IP")
                    }
                },
                onRefresh = {
                    scope.launch {
                        syncRuntimeState(refreshSnapshot = true, announce = true)
                    }
                },
                onToggleMode = {
                    networkState = networkState.copy(
                        rotateMode = if (networkState.rotateMode == "飞行模式重连") "移动数据重连" else "飞行模式重连"
                    )
                },
                onCopyDiagnostics = { copyLines("诊断日志", it) },
                onCopyTaskLogs = { title, lines -> copyLines("$title 调试日志", lines) },
            )



            AppTab.Remote -> RemoteScreen(
                modifier = Modifier.padding(innerPadding),
                remoteState = remoteState,
                remoteTokenInput = remoteTokenInput,
                remoteDomainInput = remoteDomainInput,
                onTokenChange = { remoteTokenInput = it },
                onDomainChange = { remoteDomainInput = it },
                onBind = {
                    scope.launch {
                        val status = TunnelRuntime.bind(context, remoteTokenInput, remoteDomainInput)
                        applyTunnelStatus(status)
                        snackbarHostState.showSnackbar("隧道配置已保存")
                    }
                },
                onDownload = {
                    scope.launch {
                        val status = TunnelRuntime.prepareBundledBinary(context)
                        applyTunnelStatus(status)
                        snackbarHostState.showSnackbar(if (status.binaryReady) "内置 cloudflared 已就绪" else (status.lastError ?: "内置 cloudflared 检测失败"))

                    }
                },
                onToggleTunnel = {
                    scope.launch {
                        val status = if (remoteState.running) {
                            TunnelRuntime.stop(context)
                        } else {
                            TunnelRuntime.start(context)
                        }

                        applyTunnelStatus(status)
                        snackbarHostState.showSnackbar(if (status.running) "隧道已启动" else (status.lastError ?: "隧道已停止"))
                    }
                },
                onRefresh = {
                    scope.launch {
                        val status = TunnelRuntime.refreshStatus(context, lastErrorOverride = null)
                        applyTunnelStatus(status)
                        snackbarHostState.showSnackbar("隧道状态已刷新")
                    }
                },
                onTest = {
                    scope.launch {
                        val domainLabel = remoteState.domain.takeIf { it.isNotBlank() && it != "未配置" } ?: "当前未填写域名"
                        snackbarHostState.showSnackbar("$domainLabel -> http://${remoteState.localApi}")
                    }
                },
                onCopyLogs = { copyLines("隧道日志", it) },
            )




            AppTab.Settings -> SettingsScreen(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                onSettingsChange = { settings = it }
            )
        }
    }

    if (showCreateNodeDialog) {
        CreateNodeDialog(
            onDismiss = { showCreateNodeDialog = false },
            onCreate = { node ->
                nodes.add(0, node)
                showCreateNodeDialog = false
                toast("节点已创建并开始监听")
            },
            nextId = (nodes.maxOfOrNull { it.id } ?: 0) + 1,
        )
    }

    selectedNode?.let { node ->
        NodeDetailDialog(
            node = node,
            onDismiss = { selectedNode = null },
            onStart = {
                updateNode(node.copy(status = NodeStatus.Running, lastStarted = "刚刚"))
                selectedNode = node.copy(status = NodeStatus.Running, lastStarted = "刚刚")
            },
            onStop = {
                updateNode(node.copy(status = NodeStatus.Stopped, currentConnections = 0))
                selectedNode = node.copy(status = NodeStatus.Stopped, currentConnections = 0)
            },
            onCopy = {
                clipboard.setText(AnnotatedString("[${node.host}]:${node.port}"))
                toast("节点地址已复制")
            }
        )
    }
}

@Composable
private fun OverviewScreen(
    modifier: Modifier = Modifier,
    nodes: List<ProxyNode>,
    networkState: NetworkState,
    remoteState: RemoteState,
    tasks: List<RotateTask>,
    onQuickRotate: () -> Unit,
    onNewNode: () -> Unit,
    onCopyIp: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101828)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("手机版 3x-ui 控制台", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("管理代理节点、远程隧道和换 IP 任务，当前界面已接入真实控制链路。", color = Color(0xFFD0D5DD))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = onNewNode) { Text("新建节点") }
                        Button(onClick = onQuickRotate) { Text("一键换 IP") }
                        OutlinedButton(onClick = onCopyIp) { Text("复制当前 IPv6") }
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewStatCard(title = "代理核心", value = if (nodes.any { it.status == NodeStatus.Running }) "运行中" else "已停止", icon = Icons.Default.Router)
                OverviewStatCard(title = "远程隧道", value = remoteState.tunnelStatus, icon = Icons.Default.Cloud)
                OverviewStatCard(title = "当前网络", value = networkState.activeNetwork, icon = if (networkState.activeNetwork == "蜂窝网络") Icons.Default.CellTower else Icons.Default.Language)
                OverviewStatCard(title = "已启用节点", value = nodes.count { it.status == NodeStatus.Running }.toString(), icon = Icons.Default.Dns)
            }
        }
        item {
            SectionTitle(title = "当前出口 IP", subtitle = "远程设备访问前，先确认手机当前 IPv6")
            KeyValueCard(
                pairs = listOf(
                    "IPv6 地址" to networkState.ipv6,
                    "换 IP 方式" to networkState.rotateMode,
                    "最近切换" to networkState.lastRotateAt,
                )
            )
        }
        item {
            SectionTitle(title = "最近任务", subtitle = "展示换 IP 与远程控制的执行结果")
        }
        items(tasks.take(3)) { task ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        StatusBadge(label = task.status, color = when (task.status) {
                            "已成功" -> NodeStatus.Running.color
                            "等待网络恢复" -> NodeStatus.Starting.color
                            else -> NodeStatus.Error.color
                        })
                    }
                    Text(task.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NodesScreen(
    modifier: Modifier = Modifier,
    nodes: List<ProxyNode>,
    onNodeClick: (ProxyNode) -> Unit,
    onStartNode: (ProxyNode) -> Unit,
    onStopNode: (ProxyNode) -> Unit,
    onCopyAddress: (ProxyNode) -> Unit,
) {
    if (nodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有代理节点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("创建一个 SOCKS5 或 HTTP 代理节点，让其他设备通过这台手机访问网络")
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle(title = "节点列表", subtitle = "卡片式管理，更适合手机操作")
        }
        items(nodes, key = { it.id }) { node ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNodeClick(node) },
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${node.type.label} · [${node.host}]:${node.port}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        StatusBadge(label = node.status.label, color = node.status.color)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TinyInfoChip(icon = Icons.Default.Lock, label = if (node.authEnabled) "已开启认证" else "匿名访问")
                        TinyInfoChip(icon = Icons.Default.Sync, label = "连接 ${node.currentConnections}")
                        TinyInfoChip(icon = Icons.Default.PowerSettingsNew, label = "最近启动 ${node.lastStarted}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (node.status == NodeStatus.Running) onStopNode(node) else onStartNode(node) }) {
                            Icon(if (node.status == NodeStatus.Running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (node.status == NodeStatus.Running) "停止" else "启动")
                        }
                        OutlinedButton(onClick = { onCopyAddress(node) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("复制地址")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkScreen(
    modifier: Modifier = Modifier,
    networkState: NetworkState,
    tasks: List<RotateTask>,
    onRotate: () -> Unit,
    onRefresh: () -> Unit,
    onToggleMode: () -> Unit,
    onCopyDiagnostics: (List<String>) -> Unit,
    onCopyTaskLogs: (String, List<String>) -> Unit,
) {

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(title = "当前网络状态", subtitle = "控制手机出口网络与换 IP 任务")
        }
        item {
            KeyValueCard(
                pairs = listOf(
                    "当前网络" to networkState.activeNetwork,
                    "当前 IPv6" to networkState.ipv6,
                    "无线网络状态" to if (networkState.wifiConnected) "已连接" else "未连接",
                    "上次换 IP" to networkState.lastRotateAt,
                    "最近错误" to (networkState.lastError ?: "无"),
                )
            )
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("更换出口 IP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("执行期间网络会短暂中断；界面会展示 root 命令、默认路由和 IPv6 采集诊断")

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TinyInfoChip(icon = Icons.Default.SwapHoriz, label = networkState.rotateMode)
                        TinyInfoChip(icon = Icons.Default.Sync, label = "保持 ${networkState.holdSeconds} 秒")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRotate) { Text("立即更换") }
                        OutlinedButton(onClick = onRefresh) { Text("刷新状态") }
                        OutlinedButton(onClick = onToggleMode) { Text("切换方式") }
                    }
                }
            }
        }
        item {
            SectionTitle(title = "最近诊断", subtitle = "用于定位 root 权限、飞行模式和 IPv6 恢复问题")
        }
        item {
            DiagnosticsCard(
                lines = networkState.diagnostics,
                onCopyAll = onCopyDiagnostics,
            )
        }

        item {
            SectionTitle(title = "最近任务", subtitle = "建议将换 IP 设计为异步任务")
        }
        items(tasks.take(5)) { task ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold)
                    Text(task.status, color = MaterialTheme.colorScheme.primary)
                    Text(task.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.debugLines.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("调试日志", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { onCopyTaskLogs(task.title, task.debugLines) }) { Text("复制全部") }
                        }
                        task.debugLines.takeLast(6).forEach { line ->
                            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                }
            }
        }
    }
}


@Composable
private fun RemoteScreen(
    modifier: Modifier = Modifier,
    remoteState: RemoteState,
    remoteTokenInput: String,
    remoteDomainInput: String,
    onTokenChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onBind: () -> Unit,
    onDownload: () -> Unit,
    onToggleTunnel: () -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onCopyLogs: (List<String>) -> Unit,
) {


    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(title = "Cloudflare Tunnel", subtitle = "远程入口走 Tunnel，实际流量仍由本地代理节点承载")
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = remoteTokenInput,
                        onValueChange = onTokenChange,
                        label = { Text("隧道令牌") },
                        supportingText = { Text("当前版本使用 APK 内置 arm64 二进制，并通过 root 前台服务启动 Cloudflare Tunnel") },

                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )

                    OutlinedTextField(
                        value = remoteDomainInput,
                        onValueChange = onDomainChange,
                        label = { Text("预期域名") },
                        supportingText = { Text("可选，用于显示你在 Cloudflare 后台绑定的域名") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onBind) { Text("保存配置") }
                        OutlinedButton(onClick = onDownload) { Text("检测内置") }
                        OutlinedButton(onClick = onRefresh) { Text("刷新状态") }
                    }

                }
            }
        }
        item {
            KeyValueCard(
                pairs = listOf(
                    "隧道状态" to remoteState.tunnelStatus,
                    "进程状态" to if (remoteState.running) "运行中" else "未运行",
                    "访问域名" to remoteState.domain,

                    "本地控制接口" to remoteState.localApi,
                    "设备架构" to remoteState.deviceAbi,
                    "二进制路径" to remoteState.binaryPath,
                    "二进制状态" to if (remoteState.binaryReady) "已就绪" else "未就绪",
                    "运行说明" to remoteState.downloadStatus,
                    "当前版本" to (remoteState.binaryVersion ?: "未知"),
                    "进程号" to (remoteState.pid ?: "未运行"),
                    "最近错误" to (remoteState.lastError ?: "无"),
                )
            )

        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地控制接口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("cloudflared 会把域名请求转发到手机本地控制接口；当前版本直接使用 APK 内置二进制，不依赖 Termux。", color = MaterialTheme.colorScheme.onSurfaceVariant)


                    Text("健康检查：GET /api/health", style = MaterialTheme.typography.bodySmall)
                    Text("网络状态：GET /api/network/status", style = MaterialTheme.typography.bodySmall)
                    Text("切换出口：POST /api/network/rotate", style = MaterialTheme.typography.bodySmall)
                    Text("任务详情：GET /api/tasks/{taskId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggleTunnel) {
                    Text(if (remoteState.running) "停止隧道" else "启动隧道")
                }

                OutlinedButton(onClick = onTest) { Text("查看连通性") }
            }
        }
        item {
            SectionTitle(title = "最近日志", subtitle = "读取 cloudflared 最近输出，便于定位绑定失败")
        }
        item {
            DiagnosticsCard(
                lines = remoteState.logLines,
                onCopyAll = onCopyLogs,
            )
        }

    }
}


@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(title = "运行设置", subtitle = "第一版保留最关键的常驻与安全开关")
        }
        item {
            ToggleCard(
                title = "前台服务常驻",
                subtitle = "避免后台被杀后导致 Tunnel 与代理节点中断",
                checked = settings.keepServiceAlive,
                onCheckedChange = { onSettingsChange(settings.copy(keepServiceAlive = it)) }
            )
        }
        item {
            ToggleCard(
                title = "优先使用蜂窝网络",
                subtitle = "测试阶段建议开启，避免代理流量走 Wi‑Fi",
                checked = settings.preferCellular,
                onCheckedChange = { onSettingsChange(settings.copy(preferCellular = it)) }
            )
        }
        item {
            ToggleCard(
                title = "保留审计日志",
                subtitle = "记录节点启动、远程请求和换 IP 执行结果",
                checked = settings.auditLogs,
                onCheckedChange = { onSettingsChange(settings.copy(auditLogs = it)) }
            )
        }
    }
}

@Composable
private fun CreateNodeDialog(
    nextId: Int,
    onDismiss: () -> Unit,
    onCreate: (ProxyNode) -> Unit,
) {
    var name by remember { mutableStateOf("手机代理-$nextId") }
    var type by remember { mutableStateOf(NodeType.Socks5) }
    var port by remember { mutableStateOf(type.defaultPort.toString()) }
    var authEnabled by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("agent") }
    var password by remember { mutableStateOf("12345678") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(type) {
        port = type.defaultPort.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建节点") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("节点名称") }, modifier = Modifier.fillMaxWidth())
                Text("节点类型", fontWeight = FontWeight.SemiBold)
                NodeType.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { type = item }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(selected = type == item, onClick = { type = item })
                        Column {
                            Text(item.label, fontWeight = FontWeight.SemiBold)
                            Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(value = "::", onValueChange = {}, enabled = false, label = { Text("监听地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("监听端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ToggleMiniRow(title = "开启用户名密码", checked = authEnabled, onCheckedChange = { authEnabled = it })
                if (authEnabled) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(Icons.Default.Visibility, contentDescription = "显示密码")
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        ProxyNode(
                            id = nextId,
                            name = name.ifBlank { "手机代理-$nextId" },
                            type = type,
                            host = "::",
                            port = port.toIntOrNull() ?: type.defaultPort,
                            authEnabled = authEnabled,
                            username = username,
                            password = password,
                            status = NodeStatus.Running,
                            currentConnections = 0,
                            lastStarted = "刚刚",
                        )
                    )
                }
            ) {
                Text("保存并启动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun NodeDetailDialog(
    node: ProxyNode,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${node.type.label} · 监听于 [${node.host}]:${node.port}", modifier = Modifier.weight(1f))
                    StatusBadge(label = node.status.label, color = node.status.color)
                }
                HorizontalDivider()

                Text("访问认证：${if (node.authEnabled) "已开启" else "未开启"}")
                if (node.authEnabled) {
                    Text("用户名：${node.username}")
                    Text("密码：${node.password}")
                }
                Text("当前连接：${node.currentConnections}")
                Text("最近启动：${node.lastStarted}")
                Text("请确认当前网络环境允许通过手机 IPv6 访问此端口", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { if (node.status == NodeStatus.Running) onStop() else onStart() }) {
                Text(if (node.status == NodeStatus.Running) "停止节点" else "启动节点")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("复制地址") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun OverviewStatCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun KeyValueCard(pairs: List<Pair<String, String>>) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            pairs.forEachIndexed { index, pair ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pair.first, modifier = Modifier.weight(0.38f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pair.second, modifier = Modifier.weight(0.62f), fontWeight = FontWeight.Medium)
                }
                if (index != pairs.lastIndex) HorizontalDivider()

            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    lines: List<String>,
    onCopyAll: ((List<String>) -> Unit)? = null,
) {
    val visibleLines = lines.takeLast(12)
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visibleLines.isEmpty()) {
                Text("暂无诊断信息，点击“刷新状态”或执行一次“立即更换”后会显示详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (onCopyAll != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onCopyAll(lines) }) { Text("复制全部") }
                    }
                }
                visibleLines.forEachIndexed { index, line ->

                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (index != visibleLines.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}


@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {

    Card(shape = RoundedCornerShape(22.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ToggleMiniRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TinyInfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text = label,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MobileAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3563E9),
            secondary = Color(0xFF1F9D72),
            tertiary = Color(0xFFF59E0B),
            background = Color(0xFFF5F7FB),
            surface = Color.White,
            surfaceContainerHighest = Color(0xFFEFF3FF),
        ),
        content = content
    )
}

private fun sampleNodes(): List<ProxyNode> = listOf(
    ProxyNode(
        id = 1,
        name = "示例 SOCKS5 节点",
        type = NodeType.Socks5,
        host = "::",
        port = 1080,
        authEnabled = true,
        username = "agent",
        password = "12345678",
        status = NodeStatus.Stopped,
        currentConnections = 0,
        lastStarted = "未启动",
    ),
    ProxyNode(
        id = 2,
        name = "示例 HTTP 节点",
        type = NodeType.Http,
        host = "::",
        port = 8080,
        authEnabled = true,
        username = "browser",
        password = "87654321",
        status = NodeStatus.Stopped,
        currentConnections = 0,
        lastStarted = "未启动",
    ),
)


private fun sampleTasks(): List<RotateTask> = listOf(
    RotateTask(
        id = "rot-001",
        title = "更换出口 IP",
        status = "已成功",
        detail = "新的 IPv6 已更新，Tunnel 自动重连完成",
    ),
    RotateTask(
        id = "tun-001",
        title = "绑定远程隧道",
        status = "已成功",
        detail = "Cloudflare Tunnel 已连接到本地控制接口 127.0.0.1:18080",
    ),
    RotateTask(
        id = "xray-001",
        title = "重启代理核心",
        status = "等待网络恢复",
        detail = "等待蜂窝网络恢复后拉起 Xray 核心",
    )
)

private fun randomIpv6(): String {
    fun part(): String = Random.nextInt(0, 65535).toString(16)
    return "240e:${part()}:${part()}:${part()}:${part()}:${part()}:${part()}:${part()}"
}
