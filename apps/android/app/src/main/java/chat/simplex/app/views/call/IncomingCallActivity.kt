package chat.simplex.app.views.call

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.app.*
import chat.simplex.app.R
import chat.simplex.app.model.*
import chat.simplex.app.model.NtfManager.Companion.OpenChatAction
import chat.simplex.app.ui.theme.*
import chat.simplex.app.views.helpers.ProfileImage
import chat.simplex.app.views.onboarding.SimpleXLogo
import kotlinx.datetime.Clock

class IncomingCallActivity: ComponentActivity() {
  private val vm by viewModels<SimplexViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { IncomingCallActivityView(vm.chatModel) }
    unlockForIncomingCall()
  }

  override fun onDestroy() {
    super.onDestroy()
    lockAfterIncomingCall()
  }

  private fun unlockForIncomingCall() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      window.addFlags(activityFlags)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getKeyguardManager(this).requestDismissKeyguard(this, null)
    }
  }

  private fun lockAfterIncomingCall() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(false)
      setTurnScreenOn(false)
    } else {
      window.clearFlags(activityFlags)
    }
  }

  companion object {
    const val activityFlags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
  }
}

fun getKeyguardManager(context: Context): KeyguardManager =
  context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

@Composable
fun IncomingCallActivityView(m: ChatModel) {
  val switchingCall = m.switchingCall.value
  val invitation = m.activeCallInvitation.value
  val call = m.activeCall.value
  val showCallView = m.showCallView.value
  val activity = LocalContext.current as Activity
  LaunchedEffect(invitation, call, switchingCall, showCallView) {
    if (!switchingCall && invitation == null && (!showCallView || call == null)) {
      Log.d(TAG, "IncomingCallActivityView: finishing activity")
      activity.finish()
    }
  }
  SimpleXTheme {
    Surface(
      Modifier
        .background(MaterialTheme.colors.background)
        .fillMaxSize()) {
      if (showCallView) {
        Box {
          ActiveCallView(m)
          if (invitation != null) IncomingCallAlertView(invitation, m)
        }
      } else if (invitation != null) {
        IncomingCallLockScreenAlert(invitation, m)
      }
    }
  }
}

@Composable
fun IncomingCallLockScreenAlert(invitation: RcvCallInvitation, chatModel: ChatModel) {
  val cm = chatModel.callManager
  val callOnLockScreen by remember { mutableStateOf(chatModel.controller.appPrefs.callOnLockScreen.get()) }
  val context = LocalContext.current
  DisposableEffect(Unit) {
    onDispose {
      // Cancel notification whatever happens next since otherwise sound from notification and from inside the app can co-exist
      chatModel.controller.ntfManager.cancelCallNotification()
    }
  }
  IncomingCallLockScreenAlertLayout(
    invitation,
    callOnLockScreen,
    rejectCall = { cm.endCall(invitation = invitation) },
    ignoreCall = {
      chatModel.activeCallInvitation.value = null
      chatModel.controller.ntfManager.cancelCallNotification()
    },
    acceptCall = { cm.acceptIncomingCall(invitation = invitation) },
    openApp = {
      val intent = Intent(context, MainActivity::class.java)
        .setAction(OpenChatAction)
        .putExtra("chatId", invitation.contact.id)
      context.startActivity(intent)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        getKeyguardManager(context).requestDismissKeyguard((context as Activity), null)
      }
      (context as Activity).finish()
    }
  )
}

@Composable
fun IncomingCallLockScreenAlertLayout(
  invitation: RcvCallInvitation,
  callOnLockScreen: CallOnLockScreen?,
  rejectCall: () -> Unit,
  ignoreCall: () -> Unit,
  acceptCall: () -> Unit,
  openApp: () -> Unit
) {
  Column(
    Modifier
      .padding(30.dp)
      .fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    IncomingCallInfo(invitation)
    Spacer(Modifier.fillMaxHeight().weight(1f))
    if (callOnLockScreen == CallOnLockScreen.ACCEPT) {
      ProfileImage(size = 192.dp, image = invitation.contact.profile.image)
      Text(invitation.contact.chatViewName, style = MaterialTheme.typography.h2)
      Spacer(Modifier.fillMaxHeight().weight(1f))
      Row {
        LockScreenCallButton(stringResource(R.string.reject), Icons.Filled.CallEnd, Color.Red, rejectCall)
        Spacer(Modifier.size(48.dp))
        LockScreenCallButton(stringResource(R.string.ignore), Icons.Filled.Close, MaterialTheme.colors.primary, ignoreCall)
        Spacer(Modifier.size(48.dp))
        LockScreenCallButton(stringResource(R.string.accept), Icons.Filled.Check, SimplexGreen, acceptCall)
      }
    } else if (callOnLockScreen == CallOnLockScreen.SHOW) {
      SimpleXLogo()
      Text(stringResource(R.string.open_simplex_chat_to_accept_call), textAlign = TextAlign.Center, lineHeight = 22.sp)
      Text(stringResource(R.string.allow_accepting_calls_from_lock_screen), textAlign = TextAlign.Center, style = MaterialTheme.typography.body2, lineHeight = 22.sp)
      Spacer(Modifier.fillMaxHeight().weight(1f))
      SimpleButton(text = stringResource(R.string.open_verb), icon = Icons.Filled.Check, click = openApp)
    }
  }
}

@Composable
private fun LockScreenCallButton(text: String, icon: ImageVector, color: Color, action: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = Color.Transparent
  ) {
    Column(
      Modifier
        .defaultMinSize(minWidth = 50.dp)
        .padding(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      IconButton(action) {
        Icon(icon, text, tint = color, modifier = Modifier.scale(1.75f))
      }
      Spacer(Modifier.height(16.dp))
      Text(text, style = MaterialTheme.typography.body2, color = HighOrLowlight)
    }
  }
}

@Preview(
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  showBackground = true
)
@Composable
fun PreviewIncomingCallLockScreenAlert() {
  SimpleXTheme(true) {
    Surface(
      Modifier
        .background(MaterialTheme.colors.background)
        .fillMaxSize()) {
      IncomingCallLockScreenAlertLayout(
        invitation = RcvCallInvitation(
          contact = Contact.sampleData,
          callType = CallType(media = CallMediaType.Audio, capabilities = CallCapabilities(encryption = false)),
          sharedKey = null,
          callTs = Clock.System.now()
        ),
        callOnLockScreen = null,
        rejectCall = {},
        ignoreCall = {},
        acceptCall = {},
        openApp = {},
      )
    }
  }
}
