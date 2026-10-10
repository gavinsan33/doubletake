package com.doubletake.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var nsd: NsdManager
    private var pending: Receiver? = null

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pending
        val grant = result.data
        if (target == null || result.resultCode != RESULT_OK || grant == null) {
            Ui.session = Session.Idle
            return@registerForActivityResult
        }
        startForegroundService(Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_HOST, target.host)
            .putExtra(MirrorService.EXTRA_PORT, target.port)
            .putExtra(MirrorService.EXTRA_NAME, target.name)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(MirrorService.EXTRA_GRANT, grant))
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = r.host?.hostAddress ?: return
                    Ui.post {
                        if (Ui.receivers.none { it.host == host }) Ui.receivers.add(Receiver(r.serviceName, host, r.port))
                    }
                }
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
            })
        }
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {}
        override fun onStopDiscoveryFailed(t: String, code: Int) {}
    }

    override fun onCreate(b: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 0)
        nsd = getSystemService(NsdManager::class.java)
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        setContent { DoubletakeTheme { Screen(::begin, ::stop) } }
        if (b == null) quickConnect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        quickConnect(intent)
    }

    /** From the Quick Settings tile: reconnect to the last receiver unless already busy. */
    private fun quickConnect(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_QUICK_CONNECT, false)) return
        intent.removeExtra(EXTRA_QUICK_CONNECT)
        if (Ui.session is Session.Connecting || Ui.session is Session.Mirroring) return
        lastReceiver(this)?.let(::begin)
    }

    private fun begin(target: Receiver) {
        pending = target
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("name", target.name).putString("host", target.host).putInt("port", target.port)
            .apply()
        Ui.volume = 1f // mirroring setup puts the TV at full volume
        Ui.session = Session.Connecting(target.name)
        capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun stop() {
        startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }

    override fun onDestroy() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUICK_CONNECT = "quickConnect"
        private const val PREFS = "lastReceiver"

        fun lastReceiver(context: Context): Receiver? {
            val p = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val host = p.getString("host", null) ?: return null
            return Receiver(p.getString("name", host)!!, host, p.getInt("port", 7000))
        }
    }
}

@Composable
private fun DoubletakeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(onConnect: (Receiver) -> Unit, onStop: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val session = Ui.session
    val busy = session is Session.Connecting || session is Session.Mirroring
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Doubletake") }, scrollBehavior = scroll) },
    ) { insets ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (session) {
                is Session.Connecting -> item { SessionCard(session.name, connecting = true, onStop) }
                is Session.Mirroring -> item { SessionCard(session.name, connecting = false, onStop) }
                is Session.Failed -> item { ErrorCard(session.message) }
                Session.Idle -> {}
            }
            item { SectionHeader("AirPlay receivers", searching = Ui.receivers.isEmpty()) }
            if (Ui.receivers.isEmpty()) {
                item {
                    Text(
                        "Looking for Apple TVs and other AirPlay receivers on this Wi-Fi network…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(Ui.receivers, key = { it.host }) { r -> ReceiverRow(r, enabled = !busy) { onConnect(r) } }
            item { ManualEntry(enabled = !busy, onConnect) }
            item {
                Text(
                    "Audio from other apps plays on the TV and your phone stays muted while mirroring. " +
                        "Some apps block audio capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
    Ui.prompt?.let { CodeDialog(it) }
}

@Composable
private fun SessionCard(name: String, connecting: Boolean, onStop: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Default.CastConnected, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (connecting) "Connecting…" else "Mirroring", style = MaterialTheme.typography.labelLarge)
                    Text(name, style = MaterialTheme.typography.titleLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "TV volume down")
                Slider(
                    value = Ui.volume,
                    onValueChange = { Ui.volume = it; MirrorService.setTvVolume(it.toDouble()) },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "TV volume up")
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connecting) "Cancel" else "Stop mirroring") }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(text: String, searching: Boolean) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (searching) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ReceiverRow(r: Receiver, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled) {
        ListItem(
            headlineContent = { Text(r.name) },
            supportingContent = { Text(r.host) },
            leadingContent = { Icon(Icons.Default.Tv, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

@Composable
private fun ManualEntry(enabled: Boolean, onConnect: (Receiver) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    val go = { ip.trim().takeIf { it.isNotEmpty() }?.let { onConnect(Receiver(it, it, 7000)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Or enter an IP address") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { go() }, enabled = enabled && ip.isNotBlank()) { Text("Connect") }
    }
}

@Composable
private fun CodeDialog(prompt: CodePrompt) {
    var value by remember(prompt) { mutableStateOf("") }
    val pin = "PIN" in prompt.text
    AlertDialog(
        onDismissRequest = { prompt.reply("") },
        title = { Text(if (pin) "Enter PIN" else "Enter password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(prompt.text)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = if (pin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.reply(value) }, enabled = value.isNotEmpty()) { Text("OK") } },
        dismissButton = { TextButton(onClick = { prompt.reply("") }) { Text("Cancel") } },
    )
}
