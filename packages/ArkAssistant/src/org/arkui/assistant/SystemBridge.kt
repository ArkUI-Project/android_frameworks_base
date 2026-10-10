/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.view.ScreenContent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.arkui.assistant.protocol.AssistantProtocol.*

internal class SystemOperationException(val code: String?, message: String) : IllegalStateException(message)

internal class SystemBridge(private val context: Context, private val overlay: AssistantOverlay) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val connected = CompletableFuture<Messenger>()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val sequence = AtomicInteger()
    private val session = UUID.randomUUID().toString()
    @Volatile private var closed = false
    @Volatile var unavailable = false
        private set
    @Volatile var background = false
        private set
    @Volatile private var sessionReady = false
    @Volatile private var executionActive = true
    private val receiver: Messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.SYSTEM_UID && message.sendingUid != hostUid) return@Handler true
        val data = message.data.apply { classLoader = ScreenContent::class.java.classLoader }
        if (message.what == END && !closed && data.getString(SESSION) == session) { fail(); return@Handler true }
        if (message.what == DISPLAY_STATE) {
            if (!closed && data.getString(SESSION) == session) {
                background = data.getBoolean("background")
                overlay.displayState(data)
            }
            return@Handler true
        }
        if (message.what == INPUT_STATE) {
            if (!closed && data.getString(SESSION) == session) {
                val pass = data.getBoolean("passthrough")
                val requestId = message.arg1
                overlay.setPassthrough(pass) {
                    if (pass && !closed && connected.isDone && !connected.isCompletedExceptionally) {
                        runCatching { connected.getNow(null)?.send(Message.obtain(null, INPUT_STATE).apply {
                            arg1 = requestId; this.data = Bundle().apply { putString(SESSION, session) }; replyTo = receiver
                        }) }
                    }
                }
            }
            return@Handler true
        }
        val waiting = pending.remove(message.arg1)
        if (waiting == null || !waiting.complete(data)) data.getParcelable(CONTENT, ScreenContent::class.java)?.close()
        true
    })
    private val hostUid = context.packageManager.getPackageUid("com.android.systemui", 0)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!closed) connected.complete(Messenger(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) { fail() }
        override fun onBindingDied(name: ComponentName) { fail() }
        override fun onNullBinding(name: ComponentName) { fail() }
    }
    private var bound = false

    @JvmOverloads
    fun connect(background: Boolean, autoBackground: Boolean = false) {
        main.post {
            if (!closed) {
                bound = runCatching {
                    context.bindService(Intent().setComponent(ComponentName("com.android.systemui", HOST)),
                        connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!bound) fail()
            }
        }
        request(BEGIN, Bundle().apply {
            putBoolean("background", background); putBoolean("auto_background", autoBackground)
        })
        this.background = background
        sessionReady = true
        setExecutionActive(executionActive)
    }

    fun setExecutionActive(active: Boolean) {
        executionActive = active
        if (closed || !sessionReady) return
        runCatching { connected.getNow(null)?.send(Message.obtain(null, EXECUTION_STATE).apply {
            data = Bundle().apply { putString(SESSION, session); putBoolean("active", active) }
            replyTo = receiver
        }) }
    }

    // Serialize preview and model requests so a progress refresh cannot race an input gesture.
    @Synchronized
    fun request(what: Int, data: Bundle = Bundle()): Bundle {
        check(!closed && !unavailable) { context.getString(R.string.host_unavailable) }
        val host = connected.get(8, TimeUnit.SECONDS)
        val id = sequence.incrementAndGet()
        val future = CompletableFuture<Bundle>()
        pending[id] = future
        try {
            data.putString(SESSION, session)
            host.send(Message.obtain(null, what).apply { arg1 = id; this.data = data; replyTo = receiver })
            val result = future.get(10, TimeUnit.SECONDS)
            result.getString(ERROR)?.let { throw SystemOperationException(result.getString(ERROR_CODE), it) }
            return result
        } catch (error: Exception) {
            if (!future.cancel(false) && future.isDone && !future.isCompletedExceptionally) {
                future.getNow(null)?.getParcelable(CONTENT, ScreenContent::class.java)?.close()
            }
            throw (error.cause as? IllegalStateException ?: error)
        } finally {
            pending.remove(id)
        }
    }

    private fun fail() {
        unavailable = true
        main.post { overlay.setPassthrough(false) }
        val error = IllegalStateException(context.getString(R.string.host_unavailable))
        connected.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    fun takeOver() {
        request(TAKE_OVER)
        background = false
    }

    override fun close() {
        if (closed) return
        closed = true
        if (connected.isDone && !connected.isCompletedExceptionally) runCatching {
            connected.getNow(null)?.send(Message.obtain(null, END).apply {
                data = Bundle().apply { putString(SESSION, session) }; replyTo = receiver
            })
        }
        fail()
        main.post { if (bound) { context.unbindService(connection); bound = false } }
    }
}
