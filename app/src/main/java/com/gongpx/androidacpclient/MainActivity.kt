package com.gongpx.androidacpclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.gongpx.androidacpclient.data.notification.EXTRA_CHAT_ID
import com.gongpx.androidacpclient.data.notification.ChatMonitorService
import com.gongpx.androidacpclient.ui.AgentLinkApp

class MainActivity : ComponentActivity() {
    private val incomingPairingLink = mutableStateOf<String?>(null)
    private val incomingChatId = mutableStateOf<String?>(null)
    private val appInForeground = mutableStateOf(false)
    private val notificationPermissionRevision = mutableStateOf(0)
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionRevision.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeIntent(intent)
        requestNotificationPermission()
        setContent {
            AgentLinkApp(
                incomingPairingLink = incomingPairingLink,
                incomingChatId = incomingChatId,
                appInForeground = appInForeground,
                notificationPermissionRevision = notificationPermissionRevision.value,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        ChatMonitorService.stop(this)
        appInForeground.value = true
    }

    override fun onResume() {
        super.onResume()
        notificationPermissionRevision.value++
    }

    override fun onStop() {
        appInForeground.value = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        incomingPairingLink.value = intent?.dataString
        incomingChatId.value = intent?.getStringExtra(EXTRA_CHAT_ID)
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
