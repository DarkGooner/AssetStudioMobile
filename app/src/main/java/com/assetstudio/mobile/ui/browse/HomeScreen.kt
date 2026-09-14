package com.assetstudio.mobile.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.assetstudio.mobile.AssetItem
import com.assetstudio.mobile.LoadState
import com.assetstudio.mobile.MainViewModel
import com.assetstudio.mobile.core.crypto.EncryptedBundleDecoder
import com.assetstudio.mobile.ui.browser.FilePickerDialog
import com.assetstudio.mobile.ui.browser.rememberStoragePermission
import com.assetstudio.mobile.ui.components.InfoRow

/*
 * 主页：Select files → Load → ViewAsset列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenList: () -> Unit
) {
    val context = LocalContext.current
    val context = LocalContext.current
    val loadState by viewModel.loadState.collectAsState()
    val fileName by viewModel.loadedFileName.collectAsState()
    val assets by viewModel.assets.collectAsState()
    val errors by viewModel.loadErrors.collectAsState()
    val decryptNote by viewModel.decryptNote.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val mcpStarting by viewModel.mcpStarting.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val folderScan by viewModel.folderScan.collectAsState()
    val folderSummary by viewModel.folderSummary.collectAsState()
    val pendingRemaining by viewModel.pendingRemainingFiles.collectAsState()
    // v1.10.0：批次已彻底隐形（纯内部实现）——主页不再收集/展示任何批次状态
    var versionInput by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    // 手动解密对话框
    var showDecryptDialog by remember { mutableStateOf(false) }
    // 顶部Menu弹出的对话框
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbar.showSnackbar(toast!!)
            viewModel.consumeToast()
        }
    }

    // ---------- 应用内File选择（替代 SAF OpenMultipleDocuments） ----------
    val (storageGranted, requestStorage) = rememberStoragePermission()
    var showFilePicker by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    // File夹扫描选items（切换后重新扫描）
    var folderRecursive by remember { mutableStateOf(true) }

    LaunchedEffect(loadState) {
        val msg = when (val s = loadState) {
            is LoadState.Failed -> "Load failed：${s.message}"
            else -> null
        }
        if (msg != null) snackbar.showSnackbar(msg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AssetStudio") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showSettings = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showAbout = true
                            }
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 打开File卡片 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Open Unity asset file",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Supports AssetBundle（.bundle/.unity3d）、Serialized files（.assets）、\n" +
                            "以及 gzip/brotli/zip 容器。可多选：.assets 与对应 .resS 一起选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    showFilePicker = true
                                } else {
                                    showPermDialog = true
                                }
                            },
                            enabled = loadState !is LoadState.Loading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Select files")
                        }
                        OutlinedButton(
                            onClick = {
                                if (storageGranted) {
                                    showFolderPicker = true
                                } else {
                                    showPermDialog = true
                                }
                            },
                            enabled = loadState !is LoadState.Loading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Load folder")
                        }
                    }
                }
            }

            // ---------- Unity 版本（可选） ----------
            OutlinedTextField(
                value = versionInput,
                onValueChange = { versionInput = it },
                label = { Text("Unity 版本（可选，如 2019.4.1f1）") },
                placeholder = { Text("版本号被剥离的File需要填写") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ---------- Load状态 ----------
            when (val s = loadState) {
                is LoadState.Loading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(
                                    s.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                            }
                            // 批量Load：OK性进度 entries（当前/总数）
                            if (s.total > 0) {
                                LinearProgressIndicator(
                                    progress = { s.progress ?: 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // 批量Load允许中途Cancel (keep loaded)
                            if (s.cancellable) {
                                TextButton(onClick = { viewModel.requestCancelLoad() }) {
                                    Text("Cancel (keep loaded)")
                                }
                            }
                        }
                    }
                }
                is LoadState.Loaded -> {
                    // v1.10.0：主页只有一概念——「一Asset库」。File数/Asset数/磁盘暂存数，
                    // 批次、驻留、释放等内存概念All退到幕后，用户无感
                    LoadedSummaryCard(
                        fileName = fileName,
                        fileCount = s.fileCount,
                        assets = assets,
                        errors = errors,
                        lazyCount = assets.count { it.isLazy },
                        onOpenList = { viewModel.openUnifiedList { onOpenList() } },
                        onClearAll = {
                            viewModel.consumeFolderSummary()
                            viewModel.clearAllSessions()
                        }
                    )
                    // File夹批量Load汇总（Success/跳过/Failed明细）
                    if (folderSummary != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                folderSummary!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // 剩余File提示卡：极少数超大File内存一次装不下时，一键Automatically 继续
                    pendingRemaining?.let { pr ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "${pr.files.size} files remaining",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "These files are too large to fit in memory at once (crash protection paused loading)." +
                                        "The ${assets.size} listed assets are unaffected. Tap below to automatically continue loading the remaining files.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Button(
                                    onClick = { viewModel.continueBatchLoad(context) },
                                    enabled = loadState !is LoadState.Loading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Automatically load the remaining ${pr.files.size} files")
                                }
                            }
                        }
                    }
                    if (decryptNote != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                "Automatically decrypted: $decryptNote",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                is LoadState.Failed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Load failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "若File为已知密钥加密的 bundle，可Try manual decryption。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            OutlinedButton(onClick = { showDecryptDialog = true }) {
                                Text("Try manual decryption")
                            }
                        }
                    }
                    // v1.10.2：Load failed不再顶掉已Load内容——之前Failed分支只显示Error卡，
                    // 用户已Load的Asset库“凭空消失”，误以为内容丢了/新File没显示。
                    // 已有内容时Error卡下方照常显示汇总卡（可继续View/导出）
                    if (assets.isNotEmpty()) {
                        LoadedSummaryCard(
                            fileName = fileName,
                            fileCount = viewModel.sessions.collectAsState().value.sumOf { it.fileCount },
                            assets = assets,
                            errors = errors,
                            lazyCount = assets.count { it.isLazy },
                            onOpenList = { viewModel.openUnifiedList { onOpenList() } },
                            onClearAll = {
                                viewModel.consumeFolderSummary()
                                viewModel.clearAllSessions()
                            }
                        )
                    }
                }
                LoadState.Idle -> {}
            }

            // v1.10.0：批次管理卡已删除——批次Yes纯内部实现（Automatically 分装/Automatically 腾内存/
            // Automatically 按需装载），用户界面上不再出现任何批次概念；AssetView唯一入口
            // = 上方汇总卡「ViewAsset」，Clear = 汇总卡右上角按钮

            // ---------- 手动解密对话框 ----------
            if (showDecryptDialog) {
                ManualDecryptDialog(
                    onDismiss = { showDecryptDialog = false },
                    onConfirm = { keyHex, mode, skipPrefix ->
                        showDecryptDialog = false
                        viewModel.reloadWithDecrypt(
                            context,
                            MainViewModel.ManualDecryptSpec(keyHex, mode, skipPrefix)
                        )
                    }
                )
            }

            // ---------- 应用内File选择器（多选，Load bundle/.assets/.resS） ----------
            if (showFilePicker) {
                FilePickerDialog(
                    allowMultiple = true,
                    onDismiss = { showFilePicker = false },
                    onConfirm = { files ->
                        showFilePicker = false
                        viewModel.specifyUnityVersion = versionInput.trim()
                        viewModel.manager.specifyUnityVersion = versionInput.trim().ifEmpty { null }
                        viewModel.consumeFolderSummary()
                        viewModel.loadFiles(files)
                    }
                )
            }

            // ---------- File夹选择器（选目录 → 扫描 → 确认后批量Load） ----------
            if (showFolderPicker) {
                FilePickerDialog(
                    pickDirectory = true,
                    onDismiss = { showFolderPicker = false },
                    onConfirm = { dirs ->
                        showFolderPicker = false
                        val dir = dirs.firstOrNull() ?: return@FilePickerDialog
                        viewModel.scanFolder(dir, folderRecursive)
                    }
                )
            }

            // ---------- File夹扫描确认对话框 ----------
            val scan = folderScan
            if (scan != null) {
                FolderLoadConfirmDialog(
                    scan = scan,
                    recursive = folderRecursive,
                    onRecursiveChange = { checked ->
                        folderRecursive = checked
                        // 切换后重扫（含/不含子File夹的清单不同）
                        viewModel.scanFolder(scan.folder, checked)
                    },
                    onDismiss = { viewModel.consumeFolderScan() },
                    onConfirm = {
                        viewModel.consumeFolderScan()
                        viewModel.specifyUnityVersion = versionInput.trim()
                        viewModel.manager.specifyUnityVersion = versionInput.trim().ifEmpty { null }
                        viewModel.consumeFolderSummary()
                        viewModel.loadFolder(scan, context)
                    }
                )
            }

            // ---------- 存储权限说明 ----------
            if (showPermDialog) {
                AlertDialog(
                    onDismissRequest = { showPermDialog = false },
                    title = { Text("Storage permission required") },
                    text = {
                        Text(
                            "浏览与读取手机存储中的File需要「所有File访问」权限。\n\n" +
                                "点击「Grant access」跳转系统Settings页，找到本应用并开启" +
                                "\"Allow management of all files\"后Back即可。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermDialog = false
                            requestStorage()
                        }) { Text("Grant access") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------- Settings对话框（MCP 服务器在此管理） ----------
            if (showSettings) {
                SettingsDialog(
                    mcpStatus = mcpStatus,
                    mcpStarting = mcpStarting,
                    onDismiss = { showSettings = false },
                    onStartMcp = { viewModel.startMcp(context) },
                    onStopMcp = { viewModel.stopMcp() }
                )
            }

            // ---------- About对话框 ----------
            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }

            // ---------- 功能说明 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Features", style = MaterialTheme.typography.titleMedium)
                    FeatureRow(Icons.Filled.Inventory2, "Asset browser", "解析 bundle 内AllAsset，按TypeFilter、Search；长按分类标签可置顶排序")
                    FeatureRow(Icons.Filled.Image, "Texture preview", "DXT/BC/ETC/ASTC/PVRTC 等Format硬解预览")
                    FeatureRow(Icons.Filled.SwapHoriz, "Texture / model replacement", "PNG ReplaceTexture、OBJ Replace model，重打包Save")
                    FeatureRow(Icons.Filled.ViewInAr, "Render information", "Material / Shader结构化TextView")
                    FeatureRow(Icons.Filled.Rule, "Asset export", "PNG / OBJ / WAV / Text / TypeTree dump")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/*
 * 已Load汇总卡（v1.10.0「无感Load」）：用户视角只有一Asset库——
 * 标题 = File数 · Asset数；内存装不下的部分显示为「暂存磁盘」一行轻提示。
 * 批次/驻留/释放等实现细节All隐形；右上角提供一键Clear。
 */
@Composable
private fun LoadedSummaryCard(
    fileName: String,
    fileCount: Int,
    assets: List<AssetItem>,
    errors: List<String>,
    lazyCount: Int,
    onOpenList: () -> Unit,
    onClearAll: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    fileName.ifEmpty { "已Load" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text("Clear")
                }
            }
            if (lazyCount > 0) {
                Text(
                    "$lazyCount items are stored on disk because they do not fit in memory at once. The list, search, and export remain available. " +
                        "The item is loaded from disk when opened (about 1 second).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            InfoRow("Serialized files", "$fileCount ")
            InfoRow("Total assets", "${assets.size} ")
            val textures = assets.count { it.type.value == 28 }
            val sprites = assets.count { it.type.value == 213 || it.type.value == 68 }
            val audios = assets.count { it.type.value == 83 }
            val texts = assets.count { it.type.value == 49 }
            InfoRow("Textures / sprites", "$textures / $sprites")
            InfoRow("Audio / text", "$audios / $texts")
            if (errors.isNotEmpty()) {
                InfoRow("Parse warnings", "${errors.size}  entries（部分对象可能不可用）")
            }
            // 唯一入口：AllAsset的统一列表
            Button(onClick = onOpenList, modifier = Modifier.fillMaxWidth()) {
                Text("View assets (${assets.size})")
            }
        }
    }
}

/*
 * v1.10.0：BatchListCard / BatchCardItem 已删除。
 * 批次退化为 AssetsManager 内部的分装单位（Automatically 分装、Automatically 腾内存、索引常驻、
 * 点开Automatically 按需装载），不再有任何用户可见的批次界面。
 */

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/*
 * Settings对话框：MCP 服务器（AI 助手接入）的启动 / 停止与状态展示。
 * mcpStatus 仅在服务器真正运行时非空：null → 显示说明 + 「Start server」；
 * 非 null → 显示运行状态（含连接地址）+ 「Stop server」。启动中禁用按钮防重复点击。
 */
@Composable
private fun SettingsDialog(
    mcpStatus: String?,
    mcpStarting: Boolean,
    onDismiss: () -> Unit,
    onStartMcp: () -> Unit,
    onStopMcp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI assistant integration (MCP)", style = MaterialTheme.typography.titleSmall)
                if (mcpStatus == null) {
                    Text(
                        "启动内置 MCP 服务器后，AI 助手可通过局域网查询 / 导出" +
                            "当前已Load的Asset（需与手机连接同一 Wi-Fi）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mcpStarting) {
                        Text(
                            "Starting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        mcpStatus,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (mcpStatus == null) {
                TextButton(
                    onClick = onStartMcp,
                    enabled = !mcpStarting
                ) { Text(if (mcpStarting) "Starting…" else "Start server") }
            } else {
                TextButton(onClick = onStopMcp) { Text("Stop server") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/*
 * About对话框：版本与功能简介。
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About AssetStudio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("版本 v1.10.2", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android 端 Unity AssetView / 导出 / Replace工具，Supports AssetBundle、" +
                        "Serialized files与 gzip/brotli/zip 容器，常见加密 bundle Automatically 探测解密。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Features: load entire folders with automatic disk fallback when memory is insufficient; " +
                        "Textures / sprites / Mesh预览，PNG / OBJ / WAV / Text导出，" +
                        "PNG ReplaceTexture、OBJ Replace model并重打包，Material与Shader结构化View，" +
                        "Built-in MCP server for remote asset queries and exports by AI assistants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "作者：醉莫",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

/*
 * File夹Load确认对话框：展示扫描结果与风险提示，用户确认后批量Load。
 *
 * 防崩溃信息透明化：
 * - 待LoadFile数 / 总Size / 内存预算（超了会在Load时逐跳过大File）
 * - 单File超限、数量截断等警示
 * - "Include subfolders"开关（切换即重扫）
 */
@Composable
private fun FolderLoadConfirmDialog(
    scan: com.assetstudio.mobile.FolderScanResult,
    recursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val memCap = com.assetstudio.mobile.MainViewModel.folderMemCapBytes()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load entire folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    scan.folder.absolutePath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                if (scan.scanError != null) {
                    Text(
                        scan.scanError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (scan.files.isEmpty()) {
                    Text(
                        "未找到可Load的资源File（bundle / unity3d / assets / zip / resS …）。\n" +
                            "可尝试开启「Include subfolders」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    InfoRow("待LoadFile", "${scan.files.size} ")
                    InfoRow("总Size", MainViewModel.formatBytes(scan.totalBytes))
                    if (scan.files.size < scan.scannedCount) {
                        InfoRow("目录内File总数", "${scan.scannedCount} （已Automatically 过滤非资源File）")
                    }
                    InfoRow("内存预算", MainViewModel.formatBytes(memCap) + "（超出部分Automatically 跳过大File）")
                    if (scan.skippedTooBig.isNotEmpty()) {
                        Text(
                            "以下 ${scan.skippedTooBig.size} File超过单File上限（768MB）将被跳过：\n" +
                                scan.skippedTooBig.take(3).joinToString("、") +
                                if (scan.skippedTooBig.size > 3) " 等" else "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (scan.truncatedCount > 0) {
                        Text(
                            "File数超过 ${MainViewModel.MAX_FOLDER_FILES}，将只Load较小的 ${scan.files.size} 。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (scan.overBudgetHint(memCap)) {
                        Text(
                            "总Size超过内存预算：Load会Automatically 在预算内进行，超出的大File将被跳过。\n" +
                                "如需AllLoad，请分批选择子File夹。",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        // 没有截断、没有超限：明确告知AllLoad
                        Text(
                            "${scan.files.size} FileAll在内存预算内，将AllLoad。",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(checked = recursive, onCheckedChange = onRecursiveChange)
                        Text("Include subfolders", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Load过程逐进行、可随时Cancel；单File损坏或过大只跳过该File，不影响其余。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = scan.files.isNotEmpty()
            ) { Text("Start loading") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/*
 * 手动解密对话框：已知密钥的加密 bundle 兜底入口。
 * - XOR：循环多字节Key (hex)，也可只填 2 位十六进制做单字节
 * - AES-ECB / AES-CBC：密钥长度Automatically 识别 128/192/256（hex 输入 32/48/64 位）
 * - 前缀偏移：先跳过File头部若干字节再解密（部分游戏仅加密头部之后的数据）
 */
@Composable
private fun ManualDecryptDialog(
    onDismiss: () -> Unit,
    onConfirm: (keyHex: String, mode: EncryptedBundleDecoder.DecryptMode, skipPrefix: Int) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var modeIndex by remember { mutableStateOf(0) }
    var prefixInput by remember { mutableStateOf("0") }
    var keyError by remember { mutableStateOf<String?>(null) }
    val modes = listOf("XOR", "AES-ECB", "AES-CBC")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual decryption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Automatically 探测无法识别该File时，若你知道游戏使用的加密密钥，" +
                        "可在此解密后重新Load。密钥用十六进制表示（如 0d0a1b2c 或多字节循环密钥）。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        keyError = null
                    },
                    label = { Text("Key (hex)") },
                    placeholder = { Text("例：1a2b3c4d 或 1a") },
                    isError = keyError != null,
                    supportingText = keyError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modes.forEachIndexed { i, label ->
                        FilterChip(
                            selected = modeIndex == i,
                            onClick = { modeIndex = i },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = { prefixInput = it.filter { c -> c.isDigit() } },
                    label = { Text("前缀偏移（跳过头部字节数）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "提示：AES 密钥须为 16/24/32 字节（hex 长度 32/48/64）；" +
                        "CBC 模式 IV 默认取密文前 16 字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hex = keyInput.trim().removePrefix("0x")
                // 校验
                try {
                    EncryptedBundleDecoder.hexToBytes(hex)
                    val prefix = prefixInput.toIntOrNull() ?: 0
                    onConfirm(hex, EncryptedBundleDecoder.DecryptMode.entries[modeIndex], prefix)
                } catch (e: Exception) {
                    keyError = "密钥FormatError：${e.message}"
                }
            }) { Text("Decrypt and load") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
