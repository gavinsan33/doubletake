package com.doubletake.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

private const val REQ_CAPTURE = 1

class MainActivity : Activity() {
    private class Target(val name: String, val host: String, val port: Int) {
        override fun toString() = "$name  ($host)"
    }

    private val targets = mutableListOf<Target>()
    private lateinit var adapter: ArrayAdapter<Target>
    private lateinit var statusView: TextView
    private lateinit var volume: SeekBar
    private lateinit var nsd: NsdManager
    private var pending: Target? = null

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = r.host?.hostAddress ?: return
                    runOnUiThread {
                        if (targets.none { it.host == host }) adapter.add(Target(r.serviceName, host, r.port))
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
        super.onCreate(b)
        instance = this
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.RECORD_AUDIO), 0)

        statusView = TextView(this).apply { text = "Pick a receiver"; setPadding(32, 32, 32, 16) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, targets)
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ -> begin(targets[i]) }
        }
        val manual = EditText(this).apply { hint = "…or enter an IP address" ; inputType = InputType.TYPE_CLASS_PHONE }
        val go = Button(this).apply {
            text = "Connect"
            setOnClickListener { manual.text.toString().trim().takeIf { it.isNotEmpty() }?.let { begin(Target(it, it, 7000)) } }
        }
        val volumeLabel = TextView(this).apply { text = "TV volume"; setPadding(32, 16, 32, 0) }
        volume = SeekBar(this).apply {
            max = 100
            progress = 100 // mirroring setup puts the TV at full volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) MirrorService.setTvVolume(value / 100.0)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { startService(Intent(context, MirrorService::class.java).setAction(MirrorService.ACTION_STOP)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView); addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(manual); addView(go); addView(stop)
            addView(volumeLabel); addView(volume)
        })

        nsd = getSystemService(NsdManager::class.java)
        nsd.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun begin(t: Target) {
        pending = t
        volume.progress = 100
        statusView.text = "Connecting to ${t.name}…"
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        val t = pending
        if (req != REQ_CAPTURE || t == null || result != RESULT_OK || data == null) {
            statusView.text = "Screen capture not allowed"
            return
        }
        startForegroundService(Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.EXTRA_HOST, t.host)
            .putExtra(MirrorService.EXTRA_PORT, t.port)
            .putExtra(MirrorService.EXTRA_RESULT_CODE, result)
            .putExtra(MirrorService.EXTRA_GRANT, data))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { nsd.stopServiceDiscovery(discovery) }
        super.onDestroy()
    }

    companion object {
        private var instance: MainActivity? = null
        private val ui = Handler(Looper.getMainLooper())

        fun status(msg: String) {
            ui.post { instance?.statusView?.text = msg }
        }

        /** Blocks the calling (Go) thread until the user answers. "" = cancelled. */
        fun ask(prompt: String): String {
            val answer = SynchronousQueue<String>()
            ui.post {
                val a = instance
                if (a == null) { answer.offer(""); return@post }
                val input = EditText(a).apply { inputType = InputType.TYPE_CLASS_TEXT }
                AlertDialog.Builder(a).setTitle(prompt).setView(input)
                    .setPositiveButton("OK") { _, _ -> answer.offer(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> answer.offer("") }
                    .setOnCancelListener { answer.offer("") }
                    .show()
            }
            return answer.poll(2, TimeUnit.MINUTES) ?: ""
        }
    }
}
