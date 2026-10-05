package com.gongpx.androidacpclient.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gongpx.androidacpclient.data.bridge.BridgeClient
import com.gongpx.androidacpclient.data.bridge.ImageUnavailableException
import com.gongpx.androidacpclient.data.model.ImageAttachment
import com.gongpx.androidacpclient.data.model.Machine
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
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ImageStore(context) }
    val scope = rememberCoroutineScope()
    var message by rememberSaveable(chatId) { mutableStateOf("") }
    var imageJson by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    val image = remember(imageJson) { imageJson?.let { ImageAttachment.fromJson(JSONObject(it)) } }
    var working by remember(chatId) { mutableStateOf(false) }
    var restoring by remember(chatId) { mutableStateOf(true) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
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
        image?.let { selected ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AttachmentPreview(selected, null) }
                TextButton(enabled = !working && !restoring, onClick = {
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
                }) {
                    Text(strings.reliability("Remove image", "移除图片"))
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactPromptField(message, { message = it }, Modifier.weight(1f), enabled = !working && !restoring)
            Button(enabled = enabled && !working && !restoring && (message.isNotBlank() || image != null), onClick = {
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
            }) { Text(if (isBusy) strings.appendPrompt else strings.send) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = enabled && !working && !restoring, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(strings.reliability("Attach image", "添加图片")) }
            Text(
                strings.reliability("One PNG/JPEG · ≤1 MiB · ≤2048 px", "单张 PNG/JPEG · ≤1 MiB · ≤2048 像素"),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(strings.reliability(
            "New images trigger cleanup: 7-day retention and a 256 MiB cache limit.",
            "新增图片时清理：保留 7 天，缓存上限 256 MiB。",
        ), style = MaterialTheme.typography.labelSmall)
    }
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
