package chat.simplex.app.views.chat

import ComposeVoiceView
import ComposeFileView
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.DecodeException
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import chat.simplex.app.*
import chat.simplex.app.R
import chat.simplex.app.model.*
import chat.simplex.app.ui.theme.HighOrLowlight
import chat.simplex.app.views.chat.item.*
import chat.simplex.app.views.helpers.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

@Serializable
sealed class ComposePreview {
  @Serializable object NoPreview: ComposePreview()
  @Serializable class CLinkPreview(val linkPreview: LinkPreview?): ComposePreview()
  @Serializable class ImagePreview(val images: List<String>): ComposePreview()
  @Serializable class VoicePreview(val voice: String, val durationMs: Int, val finished: Boolean): ComposePreview()
  @Serializable class FilePreview(val fileName: String): ComposePreview()
}

@Serializable
sealed class ComposeContextItem {
  @Serializable object NoContextItem: ComposeContextItem()
  @Serializable class QuotedItem(val chatItem: ChatItem): ComposeContextItem()
  @Serializable class EditingItem(val chatItem: ChatItem): ComposeContextItem()
}

@Serializable
data class ComposeState(
  val message: String = "",
  val preview: ComposePreview = ComposePreview.NoPreview,
  val contextItem: ComposeContextItem = ComposeContextItem.NoContextItem,
  val inProgress: Boolean = false,
  val useLinkPreviews: Boolean
) {
  constructor(editingItem: ChatItem, useLinkPreviews: Boolean): this(
    editingItem.content.text,
    chatItemPreview(editingItem),
    ComposeContextItem.EditingItem(editingItem),
    useLinkPreviews = useLinkPreviews
  )

  val editing: Boolean
    get() =
      when (contextItem) {
        is ComposeContextItem.EditingItem -> true
        else -> false
      }
  val sendEnabled: () -> Boolean
    get() = {
      val hasContent = when (preview) {
        is ComposePreview.ImagePreview -> true
        is ComposePreview.VoicePreview -> true
        is ComposePreview.FilePreview -> true
        else -> message.isNotEmpty()
      }
      hasContent && !inProgress
    }
  val linkPreviewAllowed: Boolean
    get() =
      when (preview) {
        is ComposePreview.ImagePreview -> false
        is ComposePreview.VoicePreview -> false
        is ComposePreview.FilePreview -> false
        else -> useLinkPreviews
      }
  val linkPreview: LinkPreview?
    get() =
      when (preview) {
        is ComposePreview.CLinkPreview -> preview.linkPreview
        else -> null
      }

  companion object {
    fun saver(): Saver<MutableState<ComposeState>, *> = Saver(
      save = { json.encodeToString(serializer(), it.value) },
      restore = {
        mutableStateOf(json.decodeFromString(it))
      }
    )
  }
}

fun chatItemPreview(chatItem: ChatItem): ComposePreview {
  return when (val mc = chatItem.content.msgContent) {
    is MsgContent.MCText -> ComposePreview.NoPreview
    is MsgContent.MCLink -> ComposePreview.CLinkPreview(linkPreview = mc.preview)
    is MsgContent.MCImage -> ComposePreview.ImagePreview(images = listOf(mc.image))
    is MsgContent.MCVoice -> ComposePreview.VoicePreview(voice = chatItem.file?.fileName ?: "", mc.duration / 1000, true)
    is MsgContent.MCFile -> {
      val fileName = chatItem.file?.fileName ?: ""
      ComposePreview.FilePreview(fileName)
    }
    is MsgContent.MCUnknown, null -> ComposePreview.NoPreview
  }
}

@Composable
fun ComposeView(
  chatModel: ChatModel,
  chat: Chat,
  composeState: MutableState<ComposeState>,
  attachmentOption: MutableState<AttachmentOption?>,
  showChooseAttachment: () -> Unit
) {
  val context = LocalContext.current
  val linkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val prevLinkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val pendingLinkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val cancelledLinks = rememberSaveable { mutableSetOf<String>() }
  val useLinkPreviews = chatModel.controller.appPrefs.privacyLinkPreviews.get()
  val smallFont = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground)
  val textStyle = remember { mutableStateOf(smallFont) }
  // attachments
  val chosenContent = rememberSaveable { mutableStateOf<List<UploadContent>>(emptyList()) }
  val audioSaver = Saver<MutableState<Pair<Uri, Int>?>, Pair<String, Int>>(
    save = { it.value.let { if (it == null) null else it.first.toString() to it.second } },
    restore = { mutableStateOf(Uri.parse(it.first) to it.second) }
  )
  val chosenAudio = rememberSaveable(saver = audioSaver) { mutableStateOf(null) }
  val chosenFile = rememberSaveable { mutableStateOf<Uri?>(null) }
  val cameraLauncher = rememberCameraLauncher { uri: Uri? ->
    if (uri != null) {
      val source = ImageDecoder.createSource(SimplexApp.context.contentResolver, uri)
      val bitmap = ImageDecoder.decodeBitmap(source)
      val imagePreview = resizeImageToStrSize(bitmap, maxDataSize = 14000)
      chosenContent.value = listOf(UploadContent.SimpleImage(uri))
      composeState.value = composeState.value.copy(preview = ComposePreview.ImagePreview(listOf(imagePreview)))
    }
  }
  val cameraPermissionLauncher = rememberPermissionLauncher { isGranted: Boolean ->
    if (isGranted) {
      cameraLauncher.launchWithFallback()
    } else {
      Toast.makeText(context, generalGetString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
    }
  }
  val processPickedImage = { uris: List<Uri>, text: String? ->
    val content = ArrayList<UploadContent>()
    val imagesPreview = ArrayList<String>()
    uris.forEach { uri ->
      val source = ImageDecoder.createSource(context.contentResolver, uri)
      val drawable = try {
        ImageDecoder.decodeDrawable(source)
      } catch (e: DecodeException) {
        AlertManager.shared.showAlertMsg(
          title = generalGetString(R.string.image_decoding_exception_title),
          text = generalGetString(R.string.image_decoding_exception_desc)
        )
        Log.e(TAG, "Error while decoding drawable: ${e.stackTraceToString()}")
        null
      }
      var bitmap: Bitmap? = if (drawable != null) ImageDecoder.decodeBitmap(source) else null
      if (drawable is AnimatedImageDrawable) {
        // It's a gif or webp
        val fileSize = getFileSize(context, uri)
        if (fileSize != null && fileSize <= MAX_FILE_SIZE) {
          content.add(UploadContent.AnimatedImage(uri))
        } else {
          bitmap = null
          AlertManager.shared.showAlertMsg(
            generalGetString(R.string.large_file),
            String.format(generalGetString(R.string.maximum_supported_file_size), formatBytes(MAX_FILE_SIZE))
          )
        }
      } else {
        content.add(UploadContent.SimpleImage(uri))
      }
      if (bitmap != null) {
        imagesPreview.add(resizeImageToStrSize(bitmap, maxDataSize = 14000))
      }
    }

    if (imagesPreview.isNotEmpty()) {
      chosenContent.value = content
      composeState.value = composeState.value.copy(message = text ?: composeState.value.message, preview = ComposePreview.ImagePreview(imagesPreview))
    }
  }
  val processPickedFile = { uri: Uri?, text: String? ->
    if (uri != null) {
      val fileSize = getFileSize(context, uri)
      if (fileSize != null && fileSize <= MAX_FILE_SIZE) {
        val fileName = getFileName(SimplexApp.context, uri)
        if (fileName != null) {
          chosenFile.value = uri
          composeState.value = composeState.value.copy(message = text ?: composeState.value.message, preview = ComposePreview.FilePreview(fileName))
        }
      } else {
        AlertManager.shared.showAlertMsg(
          generalGetString(R.string.large_file),
          String.format(generalGetString(R.string.maximum_supported_file_size), formatBytes(MAX_FILE_SIZE))
        )
      }
    }
  }
  val galleryLauncher = rememberLauncherForActivityResult(contract = PickMultipleFromGallery()) { processPickedImage(it, null) }
  val galleryLauncherFallback = rememberGetMultipleContentsLauncher { processPickedImage(it, null) }
  val filesLauncher = rememberGetContentLauncher { processPickedFile(it, null) }

  LaunchedEffect(attachmentOption.value) {
    when (attachmentOption.value) {
      AttachmentOption.TakePhoto -> {
        when (PackageManager.PERMISSION_GRANTED) {
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
            cameraLauncher.launchWithFallback()
          }
          else -> {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }
        attachmentOption.value = null
      }
      AttachmentOption.PickImage -> {
        try {
          galleryLauncher.launch(0)
        } catch (e: ActivityNotFoundException) {
          galleryLauncherFallback.launch("image/*")
        }
        attachmentOption.value = null
      }
      AttachmentOption.PickFile -> {
        filesLauncher.launch("*/*")
        attachmentOption.value = null
      }
      else -> {}
    }
  }

  fun isSimplexLink(link: String): Boolean =
    link.startsWith("https://simplex.chat", true) || link.startsWith("http://simplex.chat", true)

  fun parseMessage(msg: String): String? {
    val parsedMsg = runBlocking { chatModel.controller.apiParseMarkdown(msg) }
    val link = parsedMsg?.firstOrNull { ft -> ft.format is Format.Uri && !cancelledLinks.contains(ft.text) && !isSimplexLink(ft.text) }
    return link?.text
  }

  fun loadLinkPreview(url: String, wait: Long? = null) {
    if (pendingLinkUrl.value == url) {
      composeState.value = composeState.value.copy(preview = ComposePreview.CLinkPreview(null))
      withApi {
        if (wait != null) delay(wait)
        val lp = getLinkPreview(url)
        if (lp != null && pendingLinkUrl.value == url) {
          composeState.value = composeState.value.copy(preview = ComposePreview.CLinkPreview(lp))
          pendingLinkUrl.value = null
        } else if (pendingLinkUrl.value == url) {
          composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
          pendingLinkUrl.value = null
        }
      }
    }
  }

  fun showLinkPreview(s: String) {
    prevLinkUrl.value = linkUrl.value
    linkUrl.value = parseMessage(s)
    val url = linkUrl.value
    if (url != null) {
      if (url != composeState.value.linkPreview?.uri && url != pendingLinkUrl.value) {
        pendingLinkUrl.value = url
        loadLinkPreview(url, wait = if (prevLinkUrl.value == url) null else 1500L)
      }
    } else {
      composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    }
  }

  fun resetLinkPreview() {
    linkUrl.value = null
    prevLinkUrl.value = null
    pendingLinkUrl.value = null
    cancelledLinks.clear()
  }

  fun checkLinkPreview(): MsgContent {
    val cs = composeState.value
    return when (val composePreview = cs.preview) {
      is ComposePreview.CLinkPreview -> {
        val url = parseMessage(cs.message)
        val lp = composePreview.linkPreview
        if (lp != null && url == lp.uri) {
          MsgContent.MCLink(cs.message, preview = lp)
        } else {
          MsgContent.MCText(cs.message)
        }
      }
      else -> MsgContent.MCText(cs.message)
    }
  }

  fun updateMsgContent(msgContent: MsgContent): MsgContent {
    val cs = composeState.value
    return when (msgContent) {
      is MsgContent.MCText -> checkLinkPreview()
      is MsgContent.MCLink -> checkLinkPreview()
      is MsgContent.MCImage -> MsgContent.MCImage(cs.message, image = msgContent.image)
      is MsgContent.MCVoice -> MsgContent.MCVoice(cs.message, duration = msgContent.duration)
      is MsgContent.MCFile -> MsgContent.MCFile(cs.message)
      is MsgContent.MCUnknown -> MsgContent.MCUnknown(type = msgContent.type, text = cs.message, json = msgContent.json)
    }
  }

  fun clearState() {
    composeState.value = ComposeState(useLinkPreviews = useLinkPreviews)
    textStyle.value = smallFont
    chosenContent.value = emptyList()
    chosenAudio.value = null
    chosenFile.value = null
    linkUrl.value = null
    prevLinkUrl.value = null
    pendingLinkUrl.value = null
    cancelledLinks.clear()
  }

  fun sendMessage() {
    composeState.value = composeState.value.copy(inProgress = true)
    val cInfo = chat.chatInfo
    val cs = composeState.value
    when (val contextItem = cs.contextItem) {
      is ComposeContextItem.EditingItem -> {
        val ei = contextItem.chatItem
        val oldMsgContent = ei.content.msgContent
        if (oldMsgContent != null) {
          withApi {
            val updatedItem = chatModel.controller.apiUpdateChatItem(
              type = cInfo.chatType,
              id = cInfo.apiId,
              itemId = ei.meta.itemId,
              mc = updateMsgContent(oldMsgContent)
            )
            if (updatedItem != null) chatModel.upsertChatItem(cInfo, updatedItem.chatItem)
            clearState()
          }
        }
      }
      else -> {
        val msgs: ArrayList<MsgContent> = ArrayList()
        val files: ArrayList<String> = ArrayList()
        when (val preview = cs.preview) {
          ComposePreview.NoPreview -> msgs.add(MsgContent.MCText(cs.message))
          is ComposePreview.CLinkPreview -> msgs.add(checkLinkPreview())
          is ComposePreview.ImagePreview -> {
            chosenContent.value.forEachIndexed { index, it ->
              val file = when (it) {
                is UploadContent.SimpleImage -> saveImage(context, it.uri)
                is UploadContent.AnimatedImage -> saveAnimImage(context, it.uri)
              }
              if (file != null) {
                files.add(file)
                msgs.add(MsgContent.MCImage(if (msgs.isEmpty()) cs.message else "", preview.images[index]))
              }
            }
          }
          is ComposePreview.VoicePreview -> {
            val chosenAudioVal = chosenAudio.value
            if (chosenAudioVal != null) {
              val file = chosenAudioVal.first.toFile().name
              files.add((file))
              chatModel.filesToDelete.remove(chosenAudioVal.first.toFile())
              msgs.add(MsgContent.MCVoice(if (msgs.isEmpty()) cs.message else "", chosenAudioVal.second / 1000))
            }
          }
          is ComposePreview.FilePreview -> {
            val chosenFileVal = chosenFile.value
            if (chosenFileVal != null) {
              val file = saveFileFromUri(context, chosenFileVal)
              if (file != null) {
                files.add((file))
                msgs.add(MsgContent.MCFile(if (msgs.isEmpty()) cs.message else ""))
              }
            }
          }
        }
        val quotedItemId: Long? = when (contextItem) {
          is ComposeContextItem.QuotedItem -> contextItem.chatItem.id
          else -> null
        }
        if (msgs.isNotEmpty()) {
          withApi {
            msgs.forEachIndexed { index, content ->
              if (index > 0) delay(100)
              val aChatItem = chatModel.controller.apiSendMessage(
                type = cInfo.chatType,
                id = cInfo.apiId,
                file = files.getOrNull(index),
                quotedItemId = if (index == 0) quotedItemId else null,
                mc = content
              )
              if (aChatItem != null) chatModel.addChatItem(cInfo, aChatItem.chatItem)
            }
            clearState()
          }
        } else {
          clearState()
        }
      }
    }
  }

  fun onMessageChange(s: String) {
    composeState.value = composeState.value.copy(message = s)
    if (isShortEmoji(s)) {
      textStyle.value = if (s.codePoints().count() < 4) largeEmojiFont else mediumEmojiFont
    } else {
      textStyle.value = smallFont
      if (composeState.value.linkPreviewAllowed) {
        if (s.isNotEmpty()) showLinkPreview(s)
        else resetLinkPreview()
      }
    }
  }

  fun onAudioAdded(filePath: String, durationMs: Int, finished: Boolean) {
    val file = File(filePath)
    chosenAudio.value = file.toUri() to durationMs
    chatModel.filesToDelete.add(file)
    composeState.value = composeState.value.copy(preview = ComposePreview.VoicePreview(filePath, durationMs, finished))
  }

  fun allowVoiceToContact() {
    val contact = (chat.chatInfo as ChatInfo.Direct?)?.contact ?: return
    val prefs = contact.mergedPreferences.toPreferences().copy(voice = ChatPreference(allow = FeatureAllowed.YES))
    withApi {
      val toContact = chatModel.controller.apiSetContactPrefs(contact.contactId, prefs)
      if (toContact != null) {
        chatModel.updateContact(toContact)
      }
    }
  }

  fun showDisabledVoiceAlert() {
    AlertManager.shared.showAlertMsg(
      title = generalGetString(R.string.voice_messages_prohibited),
      text = generalGetString(
        if (chat.chatInfo is ChatInfo.Direct)
          R.string.ask_your_contact_to_enable_voice
        else
          R.string.only_group_owners_can_enable_voice
      )
    )
  }

  fun cancelLinkPreview() {
    val uri = composeState.value.linkPreview?.uri
    if (uri != null) {
      cancelledLinks.add(uri)
    }
    pendingLinkUrl.value = null
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
  }

  fun cancelImages() {
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    chosenContent.value = emptyList()
  }

  fun cancelVoice() {
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    chosenAudio.value = null
  }

  fun cancelFile() {
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    chosenFile.value = null
  }

  @Composable
  fun previewView() {
    when (val preview = composeState.value.preview) {
      ComposePreview.NoPreview -> {}
      is ComposePreview.CLinkPreview -> ComposeLinkView(preview.linkPreview, ::cancelLinkPreview)
      is ComposePreview.ImagePreview -> ComposeImageView(
        preview.images,
        ::cancelImages,
        cancelEnabled = !composeState.value.editing
      )
      is ComposePreview.VoicePreview -> ComposeVoiceView(
        preview.voice,
        preview.durationMs,
        preview.finished,
        cancelEnabled = !composeState.value.editing,
        ::cancelVoice
      )
      is ComposePreview.FilePreview -> ComposeFileView(
        preview.fileName,
        ::cancelFile,
        cancelEnabled = !composeState.value.editing
      )
    }
  }

  @Composable
  fun contextItemView() {
    when (val contextItem = composeState.value.contextItem) {
      ComposeContextItem.NoContextItem -> {}
      is ComposeContextItem.QuotedItem -> ContextItemView(contextItem.chatItem, Icons.Outlined.Reply) {
        composeState.value = composeState.value.copy(contextItem = ComposeContextItem.NoContextItem)
      }
      is ComposeContextItem.EditingItem -> ContextItemView(contextItem.chatItem, Icons.Filled.Edit) {
        clearState()
      }
    }
  }

  LaunchedEffect(chatModel.sharedContent.value) {
    // Important. If it's null, don't do anything, chat is not closed yet but will be after a moment
    if (chatModel.chatId.value == null) return@LaunchedEffect

    when (val shared = chatModel.sharedContent.value) {
      is SharedContent.Text -> onMessageChange(shared.text)
      is SharedContent.Images -> processPickedImage(shared.uris, shared.text)
      is SharedContent.File -> processPickedFile(shared.uri, shared.text)
      null -> {}
    }
    chatModel.sharedContent.value = null
  }

  Column {
    contextItemView()
    when {
      composeState.value.editing && composeState.value.preview is ComposePreview.VoicePreview -> {}
      composeState.value.editing && composeState.value.preview is ComposePreview.FilePreview -> {}
      else -> previewView()
    }
    Row(
      modifier = Modifier.padding(end = 8.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      val attachEnabled = !composeState.value.editing && composeState.value.preview !is ComposePreview.VoicePreview
      IconButton(showChooseAttachment, enabled = attachEnabled) {
        Icon(
          Icons.Filled.AttachFile,
          contentDescription = stringResource(R.string.attach),
          tint = if (attachEnabled) MaterialTheme.colors.primary else HighOrLowlight,
          modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
        )
      }
      val allowedVoiceByPrefs = remember(chat.chatInfo) { chat.chatInfo.voiceMessageAllowed }
      val needToAllowVoiceToContact = remember(chat.chatInfo) {
        when (chat.chatInfo) {
          is ChatInfo.Direct -> with(chat.chatInfo.contact.mergedPreferences.voice) {
            ((userPreference as? ContactUserPref.User)?.preference?.allow == FeatureAllowed.NO || (userPreference as? ContactUserPref.Contact)?.preference?.allow == FeatureAllowed.NO) &&
                contactPreference.allow == FeatureAllowed.YES
          }
          else -> false
        }
      }
      LaunchedEffect(allowedVoiceByPrefs) {
        if (!allowedVoiceByPrefs && chosenAudio.value != null) {
          // Voice was disabled right when this user records it, just cancel it
          cancelVoice()
        }
      }
      SendMsgView(
        composeState,
        showVoiceRecordIcon = true,
        allowedVoiceByPrefs = allowedVoiceByPrefs,
        needToAllowVoiceToContact = needToAllowVoiceToContact,
        sendMessage = {
          sendMessage()
          resetLinkPreview()
        },
        ::onMessageChange,
        ::onAudioAdded,
        ::allowVoiceToContact,
        ::showDisabledVoiceAlert,
        textStyle
      )
    }
  }
}

class PickFromGallery: ActivityResultContract<Int, Uri?>() {
  override fun createIntent(context: Context, input: Int) =
    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI).apply {
      type = "image/*"
    }

  override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

class PickMultipleFromGallery: ActivityResultContract<Int, List<Uri>>() {
  override fun createIntent(context: Context, input: Int) =
    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI).apply {
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      type = "image/*"
    }

  override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
    if (intent?.data != null)
      listOf(intent.data!!)
    else if (intent?.clipData != null)
      with(intent.clipData!!) {
        val uris = ArrayList<Uri>()
        for (i in 0 until kotlin.math.min(itemCount, 10)) {
          val uri = getItemAt(i).uri
          if (uri != null) uris.add(uri)
        }
        if (itemCount > 10) {
          AlertManager.shared.showAlertMsg(R.string.images_limit_title, R.string.images_limit_desc)
        }
        uris
      }
    else
      emptyList()
}
