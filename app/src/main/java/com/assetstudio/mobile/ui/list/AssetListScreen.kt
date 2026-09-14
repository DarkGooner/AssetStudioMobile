package com.assetstudio.mobile.ui.list

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetstudio.mobile.AssetItem
import com.assetstudio.mobile.MainViewModel
import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.ui.browser.FilePickerDialog
import com.assetstudio.mobile.ui.browser.FileSaveDialog
import com.assetstudio.mobile.ui.browser.rememberStoragePermission
import com.assetstudio.mobile.ui.components.EmptyPlaceholder
import com.assetstudio.mobile.ui.components.TypeDot
import com.assetstudio.mobile.ui.components.formatBytes
import kotlinx.coroutines.launch
import java.io.File

private data class TypeFilter(val typeName: String, val label: String, val count: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val assets by viewModel.assets.collectAsState()
    // rememberSaveable：导航到详情页后列表页会被销毁，普通 remember 的Filter/Search状态会丢失
    // （表现为Back后有时回到"All"分类）。Saveable 状态由导航栈持有，Back时原样恢复，
    // LazyColumn 的滚动位置同样由其内部 rememberSaveable Automatically 保留。
    var search by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf<String?>(null) }

    // ---------- 多选模式（长按Asset进入；导航离开后ResetYes预期行为） ----------
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    // ---------- Batch replace流程状态 ----------
    var showFolderPicker by remember { mutableStateOf(false) }
    var batchFolder by remember { mutableStateOf<File?>(null) }
    var batchResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showBatchSave by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    val (storageGranted, requestStorage) = rememberStoragePermission()

    // ---------- Batch export流程状态 ----------
    var showExportDirPicker by remember { mutableStateOf(false) }
    val exportState by viewModel.exportState.collectAsState()

    // 用户自定义 tab 顺序（长按置顶，持久化保存；null = 尚未初始化）
    var tabOrder by remember { mutableStateOf<List<String>?>(null) }
    val savedOrder = tabOrder ?: TabOrderStore.load(context).also { tabOrder = it }

    // TypeFilter：默认按数量降序，再按用户保存的顺序重排（保存过的在前，其余按默认序跟后）
    val typeFilters = remember(assets, savedOrder) {
        val byCount = assets.groupBy { it.type.name }
            .map { (name, list) -> TypeFilter(name, prettyTypeName(name), list.size) }
            .sortedByDescending { it.count }
        if (savedOrder.isEmpty()) {
            byCount
        } else {
            val orderedNames = TabOrderStore.applyOrder(byCount.map { it.typeName }, savedOrder)
            val byName = byCount.associateBy { it.typeName }
            orderedNames.mapNotNull { byName[it] }
        }
    }

    // tab 栏滚动状态：长按置顶后滚回最左侧，让新顺序立即可见
    val chipScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    /** 长按分类 tab → 置顶、持久化、滚回最左侧 */
    fun moveTabToFront(typeName: String) {
        // 以"当前完整显示顺序"为基础保存，保证下次Load顺序完全一致
        val currentDisplay = typeFilters.map { it.typeName }
        val newOrder = TabOrderStore.moveToFront(currentDisplay, typeName)
        tabOrder = newOrder
        TabOrderStore.save(context, newOrder)
        scope.launch { chipScroll.animateScrollTo(0) }
        Toast.makeText(
            context,
            "「${prettyTypeName(typeName)}」Moved to front (order saved)",
            Toast.LENGTH_SHORT
        ).show()
    }

    val filtered = remember(assets, search, selectedType) {
        assets.filter { item ->
            (selectedType == null || item.type.name == selectedType) &&
                (search.isEmpty() || item.name.contains(search, ignoreCase = true) ||
                    item.pathID.toString().contains(search))
        }
    }

    // 当前选中的Textureitems（Batch replace只处理 Texture2D）
    val selectedTextures = remember(assets, selectedIds) {
        assets.filter { it.id in selectedIds && it.type == ClassIDType.Texture2D }
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Selected ${selectedIds.size} items", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "其中Texture ${selectedTextures.size} textures（Batch replace仅处理Texture）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit multi-select")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selectedIds = filtered.map { it.id }.toSet()
                            }
                        ) { Text("Select all") }
                        TextButton(
                            onClick = { showFolderPicker = true },
                            enabled = selectedTextures.isNotEmpty()
                        ) { Text("Batch replace") }
                        TextButton(
                            onClick = { showExportDirPicker = true },
                            enabled = selectedIds.isNotEmpty() &&
                                exportState !is MainViewModel.ExportState.Exporting
                        ) { Text("Batch export") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Asset列表", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${filtered.size} / ${assets.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------- Search框 ----------
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("SearchName或 PathID…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            // ---------- TypeFilter ----------
            // 「All」固定第一位；Type tab：点击Filter，长按置顶（顺序持久化保存）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScroll)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TypeChip(
                    text = "All (${assets.size})",
                    selected = selectedType == null,
                    onClick = { selectedType = null }
                )
                typeFilters.forEach { tf ->
                    TypeChip(
                        text = "${tf.label} (${tf.count})",
                        selected = selectedType == tf.typeName,
                        onClick = { selectedType = if (selectedType == tf.typeName) null else tf.typeName },
                        onLongClick = { moveTabToFront(tf.typeName) }
                    )
                }
            }

            Spacer(Modifier.size(8.dp))

            if (selectionMode) {
                Text(
                    "勾选Asset后点右上角「Batch export」；Texture可「Batch replace」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.size(4.dp))
            }

            if (filtered.isEmpty()) {
                EmptyPlaceholder("没有匹配的Asset", "尝试调整Search词或TypeFilter")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        val checked = item.id in selectedIds
                        AssetRow(
                            item = item,
                            selectionMode = selectionMode,
                            checked = checked,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    // v1.10.1（图二体验优化）：懒 entries目在点下瞬间就开始从磁盘装载
                                    // （不等导航到详情页再触发），解析与页面切换Animation重叠，
                                    // 点开到出预览的等待时间整体缩短约一转场时长
                                    if (item.isLazy) viewModel.requestAssetLive(item.id)
                                    onOpenAsset(item.id)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ---------- Batch replace：选择ImageFile夹 ----------
    if (showFolderPicker) {
        FilePickerDialog(
            pickDirectory = true,
            onDismiss = { showFolderPicker = false },
            onConfirm = { files ->
                showFolderPicker = false
                batchFolder = files.firstOrNull()
            }
        )
    }

    // ---------- Batch export：选择输出File夹 ----------
    if (showExportDirPicker) {
        FilePickerDialog(
            pickDirectory = true,
            onDismiss = { showExportDirPicker = false },
            onConfirm = { files ->
                showExportDirPicker = false
                val dir = files.firstOrNull() ?: return@FilePickerDialog
                val selected = assets.filter { it.id in selectedIds }
                if (selected.isNotEmpty()) {
                    viewModel.batchExport(dir, selected)
                }
            }
        )
    }

    // ---------- Batch export：进度 / 结果 ----------
    when (val es = exportState) {
        is MainViewModel.ExportState.Exporting -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Batch export") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("第 ${es.current} / ${es.total} ：${es.name.ifBlank { "…" }}")
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { es.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Texture导出为 PNG，Model为 OBJ，Audio为 WAV…\n单Failed会跳过并继续，不会中断。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.requestCancelExport() }) {
                        Text("取消剩余")
                    }
                }
            )
        }
        is MainViewModel.ExportState.Done -> {
            AlertDialog(
                onDismissRequest = { viewModel.consumeExportState() },
                title = { Text(if (es.cancelled) "导出已取消" else "Export complete") },
                text = {
                    Text(
                        buildString {
                            append("Success导出 ${es.okCount} ")
                            if (es.cancelled) append("（已取消剩余部分）")
                            append("\n保存位置：${es.dirPath}")
                            if (es.failures.isNotEmpty()) {
                                append("\n\nFailed ${es.failures.size} ：")
                                append(es.failures.take(3).joinToString("\n"))
                                if (es.failures.size > 3) append("\n… 等 ${es.failures.size} items")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.consumeExportState()
                        exitSelection()
                    }) { Text("Complete") }
                }
            )
        }
        MainViewModel.ExportState.Idle -> Unit
    }

    // ---------- Batch replace：匹配确认 + 执行 ----------
    batchFolder?.let { folder ->
        BatchReplaceDialog(
            viewModel = viewModel,
            folder = folder,
            textureItems = selectedTextures,
            onBatchDone = { ok, msg -> batchResult = ok to msg },
            onDismiss = { batchFolder = null }
        )
    }

    // ---------- Batch replace结果 ----------
    batchResult?.let { (ok, msg) ->
        AlertDialog(
            onDismissRequest = { batchResult = null },
            title = { Text(if (ok) "ReplaceComplete" else "ReplaceFailed") },
            text = { Text(msg) },
            confirmButton = {
                if (ok) {
                    TextButton(onClick = {
                        batchResult = null
                        if (storageGranted) {
                            showBatchSave = true
                        } else {
                            showPermDialog = true
                        }
                    }) { Text("另存为") }
                } else {
                    TextButton(onClick = { batchResult = null }) { Text("知道了") }
                }
            },
            dismissButton = {
                if (ok) {
                    TextButton(onClick = { batchResult = null }) { Text("稍后") }
                }
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
                    "保存Replace后的File需要「所有File访问」权限。\n\n" +
                        "点击「Grant access」跳转系统设置页，找到本应用并开启" +
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
                TextButton(onClick = { showPermDialog = false }) { Text("取消") }
            }
        )
    }

    // ---------- Batch replace结果另存 ----------
    if (showBatchSave) {
        val pending = viewModel.pendingSave.collectAsState().value
        if (pending != null) {
            FileSaveDialog(
                initialName = pending.suggestedName,
                onDismiss = { showBatchSave = false },
                onConfirm = { file ->
                    showBatchSave = false
                    exitSelection()
                    viewModel.savePendingToFile(file) { ok, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            showBatchSave = false
        }
    }
}

/*
 * 自定义分类标签：不使用 Material3 FilterChip（其内部自带点击手势，
 * 外层叠加 pointerInput 的长按检测会被干扰，导致长按不触发）。
 * 改用 Surface + combinedClickable，点击/长按手势完全自控。
 * 视觉与 FilterChip 对齐：选中=secondaryContainer 填充，未选中=描边。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TypeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetRow(
    item: AssetItem,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectionMode) {
                    Checkbox(checked = checked, onCheckedChange = { onClick() })
                }
                TypeDot(item.type.value)
            }
        },
        headlineContent = {
            Text(
                item.name.ifEmpty { "(Unnamed)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                // v1.10.0：懒 entries目（磁盘暂存）加轻标记——点开时Automatically 按需装载（约 1 秒）
                "${item.type.name} · ${formatBytes(item.byteSize)} · ${item.fileName}" +
                    if (item.isLazy) " · 磁盘" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Text(
                "#${item.pathID}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

private fun prettyTypeName(name: String): String = when (name) {
    "Texture2D" -> "Texture"
    "Sprite" -> "Sprite"
    "TextAsset" -> "Text"
    "AudioClip" -> "Audio"
    "MonoBehaviour" -> "脚本"
    "GameObject" -> "对象"
    "Material" -> "Material"
    "Shader" -> "Shader"
    "Font" -> "Font"
    "VideoClip" -> "Video"
    "MovieTexture" -> "Video"
    "AnimatorController" -> "Animator Controller"
    "AnimationClip" -> "Animation"
    "Transform" -> "Transform"
    else -> name
}
