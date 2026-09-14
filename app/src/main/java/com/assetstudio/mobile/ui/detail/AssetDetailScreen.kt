package com.assetstudio.mobile.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetstudio.mobile.AssetItem
import com.assetstudio.mobile.DemandState
import com.assetstudio.mobile.MainViewModel
import com.assetstudio.mobile.core.classes.AudioClip
import com.assetstudio.mobile.core.classes.Font
import com.assetstudio.mobile.core.classes.Material
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.MeshFilter
import com.assetstudio.mobile.core.classes.MonoBehaviour
import com.assetstudio.mobile.core.classes.Shader
import com.assetstudio.mobile.core.classes.SkinnedMeshRenderer
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.TextAsset
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.VideoClip
import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.serialized.TypeTree
import com.assetstudio.mobile.core.serialized.TypeTreeHelper
import com.assetstudio.mobile.export.AssetExporter
import com.assetstudio.mobile.render.RenderDumper
import com.assetstudio.mobile.ui.browser.FilePickerDialog
import com.assetstudio.mobile.ui.browser.FileSaveDialog
import com.assetstudio.mobile.ui.browser.rememberStoragePermission
import com.assetstudio.mobile.ui.components.InfoRow
import com.assetstudio.mobile.ui.components.LoadingOverlay
import com.assetstudio.mobile.ui.components.MeshPreview
import com.assetstudio.mobile.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: MainViewModel,
    assetId: String,
    onBack: () -> Unit,
    /** 点击关联Asset跳转其详情页（如 SkinnedMeshRenderer 引用的 Mesh） */
    onOpenAsset: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assets by viewModel.assets.collectAsState()
    val item = remember(assetId, assets) { assets.firstOrNull { it.id == assetId } }
    val pendingSave by viewModel.pendingSave.collectAsState()
    // v1.10.0：按需装载状态——懒 entries目（Source file未驻留内存）点开时Automatically 从磁盘载入
    val demandState by viewModel.demandState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // v1.10.0 核心补全：懒 entries目进入详情页即Automatically 装载（只读它所在的那一File）。
    // 装载Success后 MainViewModel 会回填统一列表，item.obj 变为非空并Automatically 重组出预览
    LaunchedEffect(assetId, item?.isLazy) {
        if (item?.isLazy == true) {
            viewModel.requestAssetLive(assetId)
        }
    }

    // Replace确认对话框状态
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var keepFormat by remember { mutableStateOf(true) }
    // 待确认的ModelReplace内容：Text + IO 线程统计好的摘要（主线程不做任何全文扫描）
    var pendingObj by remember { mutableStateOf<PendingObj?>(null) }

    val texture = item?.obj as? Texture2D
    val meshObj = item?.obj as? Mesh

    // ---------- 渲染器类Component（SkinnedMeshRenderer / MeshFilter）引用的Mesh ----------
    // 这些Component本身不含几何数据，几何在 m_Mesh PPtr 指向的 Mesh 对象里：
    // - 预览：解引用后复用 Mesh 预览器
    // - Replace：Replace目标就Yes这 Mesh（pathID 重写走 Mesh 对象Source file）
    val referencedMesh: Mesh? = remember(item) {
        when (val o = item?.obj) {
            is SkinnedMeshRenderer -> if (!o.m_Mesh.isNull) o.m_Mesh.tryGet() else null
            is MeshFilter -> if (!o.m_Mesh.isNull) o.m_Mesh.tryGet() else null
            else -> null
        }
    }

    // ModelReplace目标：直接Yes Mesh，或Yes渲染器Component引用的 Mesh
    val replaceTargetMesh = meshObj ?: referencedMesh

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    // ---------- 应用内File选择（替代 SAF OpenDocument） ----------
    /** 当前选择目标：ReplaceTexture（选Image）/ Replace model（选 OBJ） */
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }

    // ---------- 应用内Save（替代 SAF：SAF 会按 MIME 强制改后缀 .txt/.bin） ----------
    val (storageGranted, requestStorage) = rememberStoragePermission()

    /** 当前等待Save的请求：导出当前Asset / 另存Replace结果 */
    var saveRequest by remember { mutableStateOf<SaveRequest?>(null) }
    var showPermDialog by remember { mutableStateOf(false) }

    if (item == null) {
        Scaffold(topBar = { DetailTopBar("Asset", onBack) }) { padding ->
            Box(Modifier.padding(padding)) {
                Text("Asset不存在（可能已重新LoadFile）", Modifier.padding(24.dp))
            }
        }
        return
    }

    Scaffold(
        topBar = { DetailTopBar(item.name.ifEmpty { item.type.name }, onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // ---------- 预览区 ----------
                // v1.10.0：懒 entries目（obj=null）先显示装载占位卡——按需装载由上方
                // LaunchedEffect Automatically 触发，Success回填后此处Automatically 重组为真实预览
                if (item.obj == null) {
                    when (val ds = demandState) {
                        is DemandState.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Failed to load from disk", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    ds.reason.ifEmpty { "该Asset所在的File无法重新读取（可能已被移动或删除）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { viewModel.requestAssetLive(assetId) }) {
                                    Text("Retry")
                                }
                            }
                        }
                        else -> Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Column {
                                    Text("Loading from disk「${item.name}」…", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Only the source file for this item is loaded, usually within 1 second",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else when (val obj = item.obj) {
                    is Texture2D -> TexturePreview(viewModel, obj)
                    is Sprite -> SpritePreview(viewModel, obj)
                    is Mesh -> MeshPreview(obj)
                    is TextAsset -> TextPreview(obj)
                    is MonoBehaviour -> MonoPreview(obj)
                    is Material -> RenderDumpPreview("Material information") { RenderDumper.dumpMaterial(obj) }
                    is Shader -> RenderDumpPreview("Shader information") { RenderDumper.dumpShader(obj) }
                    // 渲染器Component：预览其引用的Mesh（几何数据在 Mesh 对象里，不在Component内）
                    is SkinnedMeshRenderer, is MeshFilter -> {
                        if (referencedMesh != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MeshPreview(referencedMesh)
                                Text(
                                    "Mesh来自 ${item.type.name} 引用的 Mesh" +
                                        "（${referencedMesh.m_VertexCount} vertices / ${referencedMesh.m_Indices.size / 3} triangles）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // 引用为空或指向未Load的External file（如剥离的依赖包）
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Asset preview", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${item.type.name} does not reference a loadable mesh" +
                                            "（reference is empty or points to an unloaded external dependency），\n可导出Raw data或 TypeTree dump。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    else -> GenericPreview(item)
                }

                // ---------- 信息卡 ----------
                // SelectionContainer：信息卡内所有Text支持长按自由选择复制
                //（长按出现系统选择手柄，可跨行拖动选择任意范围文字，点"复制"即可）
                Card(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Asset信息", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            InfoRow("Type", item.type.name)
                            InfoRow("PathID", item.pathID.toString())
                            InfoRow("Size", formatBytes(item.byteSize))
                            InfoRow("Source file", item.fileName)
                            if (item.containerPath.isNotEmpty()) {
                                InfoRow("Container path", item.containerPath.substringAfterLast('/'))
                            }
                            if (item.obj is Texture2D) {
                                val t = item.obj as Texture2D
                                InfoRow("Dimensions", "${t.m_Width} x ${t.m_Height}")
                                InfoRow("Format", t.m_TextureFormat.name)
                                InfoRow("Mip levels", t.m_MipCount.toString())
                                val streamed = t.m_StreamData
                                if (streamed != null && streamed.path.isNotEmpty()) {
                                    InfoRow("Data source", "Streamed (${streamed.path.substringAfterLast('/')})")
                                } else {
                                    InfoRow("Data source", "Inline")
                                }
                            }
                            if (item.obj is Sprite) {
                                val s = item.obj as Sprite
                                val info = try { s.getTextureInfo() } catch (e: Exception) { null }
                                if (info != null) {
                                    InfoRow(
                                        "Texture rect",
                                        "%.0f x %.0f".format(info.textureRect.width, info.textureRect.height)
                                    )
                                }
                            }
                            if (item.obj is Mesh) {
                                val m = item.obj as Mesh
                                InfoRow("submeshes", m.m_SubMeshes.size.toString())
                                InfoRow("vertices数", m.m_VertexCount.toString())
                                InfoRow("triangles", (m.m_Indices.size / 3).toString())
                                InfoRow("压缩Mesh", if (m.m_CompressedMesh != null &&
                                    (m.m_CompressedMesh!!.m_Vertices.m_NumItems > 0 ||
                                        m.m_CompressedMesh!!.m_Triangles.m_NumItems > 0)
                                ) "Yes（已解压）" else "No")
                            }
                            if (item.obj is SkinnedMeshRenderer) {
                                val smr = item.obj as SkinnedMeshRenderer
                                InfoRow("Bones", smr.m_Bones.size.toString())
                                InfoRow("Material slots", smr.m_Materials.size.toString())
                                smr.m_BlendShapeWeights?.let {
                                    InfoRow("Blend shape weights", it.size.toString())
                                }
                                InfoRow(
                                    "Skinned mesh",
                                    if (smr.m_Mesh.isNull) "No reference"
                                    else referencedMesh?.let { m ->
                                        "${m.m_VertexCount} vertices / ${m.m_SubMeshes.size} submeshes"
                                    } ?: "Reference not loaded (external file)"
                                )
                            }
                            if (item.obj is MeshFilter) {
                                val mf = item.obj as MeshFilter
                                InfoRow(
                                    "Mesh reference",
                                    if (mf.m_Mesh.isNull) "No reference"
                                    else referencedMesh?.let { m ->
                                        "${m.m_VertexCount} vertices / ${m.m_SubMeshes.size} submeshes"
                                    } ?: "Reference not loaded (external file)"
                                )
                            }
                            if (item.obj is AudioClip) {
                                val samples = try { (item.obj as AudioClip).listSamples().size } catch (e: Exception) { 0 }
                                InfoRow("Audio samples", "$samples")
                            }
                        }
                    }
                }

                // ---------- 关联Asset卡（渲染器Component引用的 Mesh，点击跳转详情） ----------
                referencedMesh?.let { mesh ->
                    // 关联 Mesh YesNo在已LoadAsset列表中（跨File引用未Load时跳转无效，只展示信息）
                    val meshAssetId = remember(mesh, assets) {
                        val id = "${mesh.assetsFile.fileName}#${mesh.m_PathID}"
                        if (assets.any { it.id == id }) id else null
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("关联Asset", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        mesh.m_Name ?: "Mesh #${mesh.m_PathID}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${mesh.m_VertexCount} vertices · ${mesh.m_SubMeshes.size} submeshes · ${mesh.m_Indices.size / 3} triangles",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (meshAssetId != null) {
                                    TextButton(onClick = { onOpenAsset(meshAssetId) }) {
                                        Text("View")
                                    }
                                } else {
                                    Text(
                                        "External file",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 操作区 ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!storageGranted) {
                                showPermDialog = true
                                return@OutlinedButton
                            }
                            // 按钮在懒 entries目装载Complete前已禁用（enabled = item.obj != null），此处必非空
                            val ext = AssetExporter.suggestedExtension(item.obj!!)
                            val base = item.name.ifEmpty { item.type.name }
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            saveRequest = SaveRequest.Export("$base.$ext")
                        },
                        // v1.10.0：懒 entries目装载Complete前禁用（装载Automatically 进行，通常 1 秒内解锁）
                        enabled = item.obj != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (item.obj == null) "Loading…" else "导出")
                    }
                    if (texture != null) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    pickTarget = PickTarget.Texture
                                } else {
                                    showPermDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("ReplaceTexture")
                        }
                    }
                    // Replace model：Mesh Asset本身，或渲染器Component（SkinnedMeshRenderer/MeshFilter）引用的 Mesh
                    if (replaceTargetMesh != null) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    pickTarget = PickTarget.Model
                                } else {
                                    showPermDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (meshObj != null) "Replace model" else "Replace mesh")
                        }
                    }
                }

                // ---------- 待Save提示 ----------
                pendingSave?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Replacement complete; waiting for save",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "Repacked ${formatBytes(result.bytes.size.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "提示：另存时建议保持原File名与后缀（如 .ab/.bundle），可直接Replace游戏内同名File。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                            Button(
                                onClick = {
                                    if (!storageGranted) {
                                        showPermDialog = true
                                    } else {
                                        saveRequest = SaveRequest.Pending(result.suggestedName)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save as new file")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            // ---------- 忙碌遮罩 ----------
            if (busy != null) {
                LoadingOverlay(busy!!)
            }
        }
    }

    // ---------- Replace model确认对话框 ----------
    pendingObj?.let { pending ->
        if (replaceTargetMesh != null) {
            AlertDialog(
                onDismissRequest = { pendingObj = null },
                title = { Text("确认Replace model") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Original model: ${replaceTargetMesh.m_VertexCount} vertices / ${replaceTargetMesh.m_SubMeshes.size} submeshes / ${replaceTargetMesh.m_Indices.size / 3} triangles")
                        Text("New model: ${pending.summary}")
                        if (meshObj == null) {
                            Text(
                                "Will replace ${item.type.name} 引用的 Mesh（${replaceTargetMesh.m_Name ?: "#" + replaceTargetMesh.m_PathID}），bone bindings and material slots remain unchanged.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "Replace将重写vertices / 法线 / UV / 索引 / 包围盒（Name保留）。" +
                                "若缺少法线或 UV，将Automatically 生成占位数据；三角带Model请先转换为triangles列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val text = pending.text
                        pendingObj = null
                        busy = "Replace model并重打包…"
                        viewModel.replaceMesh(replaceTargetMesh, text) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }) {
                        Text("Replace")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingObj = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    // ---------- ReplaceTexture确认对话框 ----------
    if (pendingBitmap != null && texture != null) {
        AlertDialog(
            onDismissRequest = { pendingBitmap = null },
            title = { Text("Confirm texture replacement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Original texture: ${texture.m_Width} x ${texture.m_Height} (${texture.m_TextureFormat.name})")
                    Text("New texture: ${pendingBitmap!!.width} x ${pendingBitmap!!.height}")
                    if (pendingBitmap!!.width != texture.m_Width || pendingBitmap!!.height != texture.m_Height) {
                        Text(
                            "注意：Dimensions与原图不同，Dimensions字段将同步更新；引用该Texture的Sprite裁剪区域可能偏移。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepFormat, onCheckedChange = { keepFormat = it })
                        Text(
                            "Keep original format where possible（Unsupported时回退 RGBA32）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bmp = pendingBitmap!!
                    pendingBitmap = null
                    busy = "Replacing and repacking…"
                    viewModel.replaceTexture(texture, bmp, keepFormat) { ok, msg ->
                        busy = null
                        message = msg
                    }
                }) {
                    Text("Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBitmap = null }) {
                    Text("Cancel")
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
                    "In-app save files需要「所有File访问」权限。\n\n" +
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
                TextButton(onClick = { showPermDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ---------- In-app save files管理器 ----------
    saveRequest?.let { request ->
        FileSaveDialog(
            initialName = request.suggestedName,
            onDismiss = { saveRequest = null },
            onConfirm = { file ->
                saveRequest = null
                when (request) {
                    is SaveRequest.Export -> {
                        val target = item ?: return@FileSaveDialog
                        busy = "Exporting…"
                        viewModel.exportAssetToFile(target, file) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }
                    is SaveRequest.Pending -> {
                        busy = "Saving…"
                        viewModel.savePendingToFile(file) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }
                }
            }
        )
    }
    // ---------- 应用内File选择器（ReplaceTexture选Image / Replace model选 OBJ） ----------
    pickTarget?.let { target ->
        FilePickerDialog(
            extensions = if (target == PickTarget.Texture) {
                setOf("png", "jpg", "jpeg", "webp", "bmp")
            } else {
                // 兼容此前被 SAF 存成 .txt 的 OBJ 导出File
                setOf("obj", "txt")
            },
            onDismiss = { pickTarget = null },
            onConfirm = { files ->
                pickTarget = null
                val file = files.firstOrNull() ?: return@FilePickerDialog
                when (target) {
                    PickTarget.Texture -> {
                        busy = "Reading image…"
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                try {
                                    BitmapFactory.decodeFile(file.absolutePath)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            busy = null
                            if (bmp != null) {
                                pendingBitmap = bmp
                            } else {
                                message = "Failed to read image; please choose another image"
                            }
                        }
                    }
                    PickTarget.Model -> {
                        busy = "Reading model file…"
                        scope.launch {
                            // 读取 + vertices/面统计All在 IO 线程Complete后才回主线程弹确认框，
                            // 主线程不做任何全文扫描（No则大File会 ANR）
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val bytes = file.readBytes()
                                    if (bytes.size > 96 * 1024 * 1024) {
                                        return@withContext null to
                                            "File too large (${bytes.size / 1024 / 1024} MB），; make sure the selected file is an OBJ text file"
                                    }
                                    val text = bytes.toString(Charsets.UTF_8)
                                    var v = 0
                                    var f = 0
                                    for (line in text.lineSequence()) {
                                        if (line.startsWith("v ")) v++
                                        else if (line.startsWith("f ")) f++
                                    }
                                    PendingObj(text, "$v vertices / $f 面") to null
                                } catch (e: OutOfMemoryError) {
                                    null to "Not enough memory: file is too large to read"
                                } catch (e: Exception) {
                                    null to "Failed to read OBJ file:${e.message}"
                                }
                            }
                            busy = null
                            val (pending, err) = result
                            if (pending != null) {
                                pendingObj = pending
                            } else {
                                message = err
                            }
                        }
                    }
                }
            }
        )
    }
}

/** File选择目标 */
private enum class PickTarget { Texture, Model }

/** Save请求：导出当前Asset / 另存Replace重打包结果 */
private sealed interface SaveRequest {
    val suggestedName: String
    data class Export(override val suggestedName: String) : SaveRequest
    data class Pending(override val suggestedName: String) : SaveRequest
}

/** 待确认的ModelReplace内容：完整 OBJ Text + IO 线程统计好的摘要 */
private data class PendingObj(
    val text: String,
    val summary: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

// ============================ 预览Component ============================

@Composable
private fun TexturePreview(viewModel: MainViewModel, texture: Texture2D) {
    val bitmap by produceState<Bitmap?>(initialValue = null, texture) {
        value = viewModel.decodeTexture(texture)
    }
    PreviewCard(bitmap, "Texture preview")
}

@Composable
private fun SpritePreview(viewModel: MainViewModel, sprite: Sprite) {
    val bitmap by produceState<Bitmap?>(initialValue = null, sprite) {
        value = viewModel.decodeSprite(sprite)
    }
    PreviewCard(bitmap, "Sprite preview")
}

@Composable
private fun PreviewCard(bitmap: Bitmap?, title: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (bitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Unable to preview",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "FormatUnsupported或流数据缺失",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                AdaptiveImagePreview(bitmap)
            }
        }
    }
}

/**
 * 自适应Texture preview：小DimensionsTexture等比放大到可辨识Size（原始比例不变），
 * 并提供滑杆拖拉控制等比Scale。
 *
 * 适配规则：
 * 1. 基准：完整放入（可用宽度 × 最大预览高度 340dp）——小图放大、大图缩小；
 *    可辨识下限（显示高度 ≥96dp、宽度 ≥48dp，受总高 640dp 上限约束）
 * 2. 滑杆在此基础上乘Scale系数（25% ~ 800%），切换Texture时Automatically Reset为 100%
 * 3. Scale后内容超出视口（480dp 高 / 卡片宽）时双向滑动View
 * 4. 放大超过原始像素时用 FilterQuality.None（最近邻，像素块清晰）
 */
@Composable
private fun AdaptiveImagePreview(bitmap: Bitmap) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val density = LocalDensity.current
        val w = bitmap.width
        val h = bitmap.height
        if (w > 0 && h > 0) {
            // 用户Scale系数（相对自适应基准），切换Texture时Reset
            var zoom by remember(bitmap) { mutableStateOf(1f) }

            val maxWpx = constraints.maxWidth.toFloat()
            val maxHpx = with(density) { 340.dp.toPx() }
            val minHpx = with(density) { 96.dp.toPx() }
            val minWpx = with(density) { 48.dp.toPx() }
            val hardMaxHpx = with(density) { 640.dp.toPx() }

            // 自适应基准（小图等比放大 + 可辨识下限）
            var fitScale = minOf(maxWpx / w, maxHpx / h)
            fitScale = maxOf(fitScale, minOf(minHpx / h, hardMaxHpx / h))
            fitScale = maxOf(fitScale, minOf(minWpx / w, hardMaxHpx / h))

            val scale = fitScale * zoom
            val dispW = (w * scale).roundToInt()
            val dispH = (h * scale).roundToInt()
            val scrollable = dispW > constraints.maxWidth ||
                dispH > with(density) { 480.dp.toPx() }

            Column {
                // 预览视口：内容超出（宽 > 卡片 / 高 > 480dp）时双向滑动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = with(density) { 480.dp })
                        .clipToBounds()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = BitmapPainter(
                            bitmap.asImageBitmap(),
                            filterQuality = if (scale > 1f) FilterQuality.None else FilterQuality.Low
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(
                            with(density) { dispW.toDp() },
                            with(density) { dispH.toDp() }
                        )
                    )
                }
                // Scale滑杆：拖拉控制等比Scale（相对自适应基准的百分比）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Scale",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 0.25f..8f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(zoom * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                Text(
                    buildString {
                        append("$w x $h")
                        if (scale > 1.01f) append("（预览放大 ${"%.1f".format(scale)}x）")
                        if (scrollable) append("，超出部分可滑动View")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TextPreview(textAsset: TextAsset) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Text content", style = MaterialTheme.typography.titleSmall)
            val content = remember(textAsset) {
                try {
                    val raw = textAsset.m_Script
                    if (raw.size > 200_000) {
                        String(raw.copyOfRange(0, 200_000), Charsets.UTF_8) + "\n…（已截断，共 ${raw.size} 字节）"
                    } else {
                        String(raw, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    "(非 UTF-8 Text，共 ${textAsset.m_Script.size} 字节)"
                }
            }
            Text(
                content,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun MonoPreview(mono: MonoBehaviour) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TypeTree dump", style = MaterialTheme.typography.titleSmall)
            val dump by produceState<String?>(initialValue = null, mono) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val typeTree = mono.reader.serializedType?.m_Type
                        if (typeTree != null) {
                            mono.reader.reset()
                            TypeTreeHelper.readTypeString(typeTree, mono.reader)
                        } else {
                            "（This object has no TypeTree information）"
                        }
                    } catch (e: Exception) {
                        "Dump failed: ${e.message}"
                    }
                }
            }
            Text(
                dump ?: "Dumping…",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/**
 * 渲染Asset（Material / Shader）Text转储预览：
 * 转储在 IO 线程执行，结果可滚动View，支持长按选择复制。
 */
@Composable
private fun RenderDumpPreview(title: String, dump: () -> String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val text by produceState<String?>(initialValue = null, title) {
                value = withContext(Dispatchers.IO) {
                    try {
                        dump()
                    } catch (e: Exception) {
                        "Dump failed: ${e.message}"
                    }
                }
            }
            SelectionContainer {
                Text(
                    text ?: "Parsing…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun GenericPreview(item: AssetItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Asset preview", style = MaterialTheme.typography.titleSmall)
            Text(
                "${item.type.name} Type暂Unsupported可视化预览，\n可导出Raw data或 TypeTree dump。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
