package com.gongpx.androidacpclient.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.ImageUnavailableException
import com.gongpx.androidacpclient.data.model.ImageAttachment
import com.gongpx.androidacpclient.data.model.Machine
import com.gongpx.androidacpclient.data.model.AvailableCommand
import com.gongpx.androidacpclient.data.store.ImageStore
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONException
import org.json.JSONObject

internal data class ImagePromptContext(
    val machine: Machine,
    val chatId: String,
    val client: BridgeClient,
    val submit: (String, ImageAttachment) -> Boolean,
)

private suspend fun imageAction(onError: (String) -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        onError(error.message ?: "Image I/O failed")
    } catch (error: IllegalArgumentException) {
        onError(error.message ?: "Invalid image")
    } catch (error: IllegalStateException) {
        onError(error.message ?: "Image operation unavailable")
    } catch (error: GeneralSecurityException) {
        onError("Cannot access encrypted image storage")
    } catch (error: SecurityException) {
        onError("Image permission denied; select the image again")
    } catch (error: JSONException) {
        onError("Invalid image response; update the Bridge")
    }
}

private suspend fun imageBitmap(bytes: ByteArray, maximumEdge: Int): ImageBitmap = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Invalid image dimensions" }
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) > maximumEdge * inSampleSize) inSampleSize *= 2
    }
    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Cannot decode image" }.asImageBitmap()
}

@Composable
internal fun ImagePromptComposer(
    chatId: String,
    isBusy: Boolean,
    onSend: (String) -> Boolean,
    enabled: Boolean,
    imageContext: ImagePromptContext,
    cancelling: Boolean,
    canStop: Boolean,
    onCancelTask: () -> Unit,
    commands: List<PromptCommand>,
    onCommand: (AvailableCommand) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ImageStore(context) }
    val scope = rememberCoroutineScope()
    var message by rememberSaveable(chatId) { mutableStateOf("") }
    var imageJson by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    val image = remember(imageJson) { imageJson?.let { ImageAttachment.fromJson(JSONObject(it)) } }
    var working by remember(chatId) { mutableStateOf(false) }
    var restoring by remember(chatId) { mutableStateOf(true) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    var thumbnail by remember(chatId, image?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(chatId, image) {
        image?.let { selected ->
            imageAction({ error = it }) {
                thumbnail = imageBitmap(store.read(selected) ?: throw IOException("Selected image is unavailable; select it again."), 128)
            }
        }
    }
    LaunchedEffect(chatId) {
        try {
            imageAction({ error = it }) {
                store.draft(chatId)?.let { imageJson = it.toJson().toString() }
            }
        } finally {
            restoring = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            working = true
            error = null
            try {
                imageAction({ error = it }) { imageJson = store.prepare(uri, chatId).toJson().toString() }
            } finally {
                working = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        CompactComposerRow(
            message = message, onMessageChange = { message = it }, isBusy = isBusy,
            editingEnabled = !working && !restoring,
            sendEnabled = enabled && !working && !restoring && (message.isNotBlank() || image != null),
            working = working || restoring,
            cancelling = cancelling, canStop = canStop, onCancelTask = onCancelTask,
            commands = commands, onCommand = onCommand,
            image = image, thumbnail = thumbnail,
            attachEnabled = enabled && !working && !restoring,
            onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveImage = {
                image?.let { selected ->
                    working = true
                    scope.launch {
                        try {
                            imageAction({ error = it }) {
                                store.clearDraft(chatId, selected)
                                imageJson = null
                                error = null
                            }
                        } finally { working = false }
                    }
                }
            },
            onSend = {
                if (image == null) {
                    if (onSend(message)) message = ""
                } else {
                    val selected = image
                    val submitted = message
                    val target = imageContext
                    working = true
                    error = null
                    scope.launch {
                        try {
                            imageAction({ error = it }) {
                                val bytes = store.read(selected) ?: throw IOException("Selected image is unavailable; select it again.")
                                val uploaded = target.client.uploadImage(target.machine, target.chatId, selected, bytes)
                                check(target.submit(submitted, uploaded)) { "Chat changed or could not be saved. Reconnect and retry; your draft was kept." }
                                imageJson = null
                                message = ""
                                withContext(NonCancellable) { store.clearDraft(chatId, selected) }
                            }
                        } finally {
                            working = false
                        }
                    }
                }
            },
        )
    }
}

internal data class PromptCommand(val command: AvailableCommand, val enabled: Boolean)

private enum class ComposerSymbol { Add, Send, Stop }

@Composable
private fun ComposerIcon(symbol: ComposerSymbol, label: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
        val stroke = 2.dp.toPx()
        when (symbol) {
            ComposerSymbol.Add -> {
                drawLine(color, Offset(size.width * .2f, center.y), Offset(size.width * .8f, center.y), stroke, StrokeCap.Round)
                drawLine(color, Offset(center.x, size.height * .2f), Offset(center.x, size.height * .8f), stroke, StrokeCap.Round)
            }
            ComposerSymbol.Send -> {
                drawLine(color, Offset(center.x, size.height * .8f), Offset(center.x, size.height * .2f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .25f, size.height * .45f), Offset(center.x, size.height * .2f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .75f, size.height * .45f), Offset(center.x, size.height * .2f), stroke, StrokeCap.Round)
            }
            ComposerSymbol.Stop -> drawRoundRect(color, Offset(size.width * .2f, size.height * .2f),
                Size(size.width * .6f, size.height * .6f), CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
internal fun CompactComposerRow(
    message: String,
    onMessageChange: (String) -> Unit,
    isBusy: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    cancelling: Boolean = false,
    canStop: Boolean = true,
    onCancelTask: () -> Unit = {},
    editingEnabled: Boolean = true,
    working: Boolean = false,
    commands: List<PromptCommand> = emptyList(),
    onCommand: (AvailableCommand) -> Unit = {},
    image: ImageAttachment? = null,
    thumbnail: ImageBitmap? = null,
    attachEnabled: Boolean = false,
    onAttach: (() -> Unit)? = null,
    onRemoveImage: () -> Unit = {},
) {
    val strings = LocalAppStrings.current
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var expandedEditor by remember { mutableStateOf(false) }
    var imageHelp by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("prompt-composer-row"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                val label = strings.reliability(
                    if (image != null) "Image and chat actions" else "Chat actions",
                    if (image != null) "图片与聊天操作" else "聊天操作",
                )
                if (thumbnail != null) Image(thumbnail, label, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                else ComposerIcon(ComposerSymbol.Add, label)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onAttach != null) DropdownMenuItem(
                    text = { Text(strings.reliability("Attach image", "添加图片")) },
                    enabled = attachEnabled,
                    onClick = { menu = false; onAttach() },
                )
                if (image != null) {
                    DropdownMenuItem(text = { Text(strings.reliability("Preview image", "预览图片")) },
                        onClick = { menu = false; preview = true })
                    DropdownMenuItem(text = { Text(strings.reliability("Remove image", "移除图片")) },
                        enabled = editingEnabled, onClick = { menu = false; onRemoveImage() })
                }
                DropdownMenuItem(text = { Text(strings.reliability("Expand editor", "展开编辑")) },
                    enabled = editingEnabled, onClick = { menu = false; expandedEditor = true })
                if (onAttach != null) DropdownMenuItem(text = { Text(strings.reliability("Image limits & storage", "图片限制与存储")) },
                    onClick = { menu = false; imageHelp = true })
                if (commands.isNotEmpty()) HorizontalDivider()
                commands.forEach { item ->
                    DropdownMenuItem(text = { Text(item.command.name) }, enabled = item.enabled && editingEnabled,
                        onClick = { menu = false; onCommand(item.command) })
                }
            }
        }
        CompactPromptField(message, onMessageChange, Modifier.weight(1f), enabled = editingEnabled)
        val stopLabel = strings.reliability(if (cancelling) "Requesting stop…" else "Stop current task",
            if (cancelling) "正在请求停止…" else "停止当前任务")
        if (isBusy) IconButton(
            onClick = onCancelTask, enabled = canStop && !cancelling,
            modifier = Modifier.size(48.dp).semantics { contentDescription = stopLabel },
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Box(Modifier.clearAndSetSemantics {}) {
                if (cancelling) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ComposerIcon(ComposerSymbol.Stop, stopLabel)
            }
        }
        val sendLabel = if (working) strings.reliability("Preparing image…", "正在处理图片…")
            else if (isBusy) strings.appendPrompt else strings.send
        FilledIconButton(onClick = onSend, enabled = sendEnabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = sendLabel }) {
            Box(Modifier.clearAndSetSemantics {}) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else ComposerIcon(ComposerSymbol.Send, sendLabel)
            }
        }
    }
    if (preview && image != null) AlertDialog(
        onDismissRequest = { preview = false },
        title = { Text(strings.reliability("Selected image", "已选图片")) },
        text = { AttachmentPreview(image, null) },
        confirmButton = { TextButton(onClick = { preview = false }) { Text(strings.reliability("Close", "关闭")) } },
        dismissButton = { TextButton(enabled = editingEnabled, onClick = { preview = false; onRemoveImage() }) {
            Text(strings.reliability("Remove image", "移除图片"))
        } },
    )
    if (expandedEditor) AlertDialog(
        onDismissRequest = { expandedEditor = false },
        title = { Text(strings.reliability("Edit message", "编辑消息")) },
        text = { CompactPromptField(message, onMessageChange, Modifier.fillMaxWidth(), enabled = editingEnabled, maxLines = 10) },
        confirmButton = { TextButton(onClick = { expandedEditor = false }) { Text(strings.reliability("Done", "完成")) } },
    )
    if (imageHelp) AlertDialog(
        onDismissRequest = { imageHelp = false },
        title = { Text(strings.reliability("Image limits & storage", "图片限制与存储")) },
        text = { Text(strings.reliability(
            "One PNG/JPEG, compressed to at most 1 MiB and 2048 px. New images trigger cleanup: 7-day retention and a 256 MiB cache limit. Drafts and queued/running images are protected.",
            "单张 PNG/JPEG，自动压缩至不超过 1 MiB、2048 像素。新增图片时检查 7 天保留期与 256 MiB 缓存上限；草稿和排队、执行中的图片受保护。",
        )) },
        confirmButton = { TextButton(onClick = { imageHelp = false }) { Text(strings.reliability("Close", "关闭")) } },
    )
}

@Composable
internal fun AttachmentPreview(image: ImageAttachment, source: ImagePromptContext?) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ImageStore(context) }
    var bitmap by remember(image.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(image.id) { mutableStateOf<String?>(null) }
    var retry by remember(image.id) { mutableIntStateOf(0) }
    var expanded by remember(image.id) { mutableStateOf(false) }
    suspend fun loadBytes(): ByteArray = store.read(image) ?: run {
        check(source != null) { "Image is not cached on this phone." }
        try {
            source.client.downloadImage(source.machine, source.chatId, image)
        } catch (_: ImageUnavailableException) {
            throw IllegalStateException(strings.reliability(
                "Image was automatically cleared or is no longer available. Chat text is preserved.",
                "图片已自动清理或不可用，聊天文字仍然保留。",
            ))
        }
    }
    LaunchedEffect(image, source?.machine?.id, source?.chatId, retry) {
        error = null
        imageAction({ error = it }) {
            val bytes = loadBytes()
            bitmap = imageBitmap(bytes, 512)
        }
    }
    Column {
        val loaded = bitmap
        if (loaded != null) {
            Image(loaded, strings.reliability("Attached image; tap to enlarge", "已附图片，点击放大"),
                Modifier.height(120.dp).fillMaxWidth().clickable { expanded = true }, contentScale = ContentScale.Fit)
            if (expanded) Dialog(onDismissRequest = { expanded = false }) {
                var fullImage by remember { mutableStateOf<ImageBitmap?>(null) }
                var fullError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(image) {
                    imageAction({ fullError = it }) {
                        fullImage = imageBitmap(loadBytes(), 2048)
                    }
                }
                Surface {
                    Column {
                        Image(fullImage ?: loaded, strings.reliability("Attached image", "已附图片"),
                            Modifier.fillMaxWidth().heightIn(max = 600.dp), contentScale = ContentScale.Fit)
                        fullError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { expanded = false }) { Text(strings.reliability("Close", "关闭")) }
                    }
                }
            }
        } else if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { retry++ }) { Text(strings.reliability("Retry image", "重试加载图片")) }
        } else Text(strings.reliability("Loading image…", "正在加载图片…"))
    }
}
