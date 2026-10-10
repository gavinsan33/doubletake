package com.doubletake.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

data class Receiver(val name: String, val host: String, val port: Int)

sealed interface Session {
    data object Idle : Session
    data class Connecting(val name: String) : Session
    data class Mirroring(val name: String) : Session
    data class Failed(val message: String) : Session
}

class CodePrompt(val text: String, private val answer: SynchronousQueue<String>) {
    fun reply(value: String) {
        answer.offer(value)
        Ui.prompt = null
    }
}

/** State shared by the activity and the mirroring service; it outlives activity recreation. */
object Ui {
    private val main = Handler(Looper.getMainLooper())

    val receivers = mutableStateListOf<Receiver>()
    var session by mutableStateOf<Session>(Session.Idle)
    var prompt by mutableStateOf<CodePrompt?>(null)
    var volume by mutableFloatStateOf(1f)

    fun post(block: () -> Unit) {
        main.post(block)
    }

    /** Blocks the calling (Go) thread until the user answers. "" = cancelled. */
    fun ask(text: String): String {
        val answer = SynchronousQueue<String>()
        post { prompt = CodePrompt(text, answer) }
        return answer.poll(2, TimeUnit.MINUTES) ?: "".also { post { prompt = null } }
    }
}
