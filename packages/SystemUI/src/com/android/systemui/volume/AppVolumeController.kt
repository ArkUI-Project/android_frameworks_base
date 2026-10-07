/* Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.volume

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.text.Collator
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

data class AppVolumeEntry(
    val packageName: String,
    val uid: Int,
    val label: String,
    val icon: Drawable,
    val percent: Int,
    val isPlaying: Boolean,
    val isSaved: Boolean,
    val hasSoundEffects: Boolean = false,
)
data class AppVolumeState(val userId: Int, val apps: List<AppVolumeEntry>)

/** The protected Settings provider is the only write path; the audio service owns playback gain. */
@SysUISingleton
class AppVolumeController @Inject constructor(
    @Application private val context: Context,
    @Main private val main: Handler,
    @Background private val background: Executor,
    private val users: UserTracker,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val writeLock = Any()
    private val pending = mutableMapOf<Pair<Int, Int>, Runnable>()

    fun observe(): Flow<AppVolumeState> = callbackFlow {
        val generation = AtomicInteger()
        fun refresh() {
            val token = generation.incrementAndGet()
            val user = users.userId
            background.execute {
                val result = snapshot(user)
                if (token == generation.get() && users.userId == user) trySend(result)
            }
        }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                refresh()
            }
        }
        val userCallback = object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) { refresh() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY), false, observer, UserHandle.USER_ALL)
        audio.registerAudioPlaybackCallback(playback, main)
        users.addCallback(userCallback, Executor { main.post(it) })
        refresh()
        awaitClose {
            generation.incrementAndGet()
            context.contentResolver.unregisterContentObserver(observer)
            audio.unregisterAudioPlaybackCallback(playback)
            users.removeCallback(userCallback)
        }
    }.conflate()

    private fun levels(user: Int): JSONObject {
        val value = Settings.Secure.getStringForUser(context.contentResolver, KEY, user)
        return try {
            if (value == null || value.length > 65536) JSONObject() else JSONObject(value)
        } catch (_: org.json.JSONException) { JSONObject() }
    }

    private fun snapshot(user: Int): AppVolumeState {
        val pm = context.createContextAsUser(UserHandle.of(user), 0).packageManager
        val saved = levels(user)
        val configurations = audio.activePlaybackConfigurations.filter {
            UserHandle.getUserId(it.clientUid) == user &&
                it.audioAttributes.usage in MEDIA_USAGES
        }
        val playing = configurations.filter { it.isActive }.map { it.clientUid }.toSet()
        // SoundPool reports no per-stream start/stop events; show its live engine honestly.
        val effects = configurations.filter {
            it.playerType == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL
        }.map { it.clientUid }.toSet()
        val packages = saved.keys().asSequence().toMutableSet()
        for (uid in playing + effects) pm.getPackagesForUid(uid)?.let { packages.addAll(it) }
        val entries = packages.mapNotNull { pkg ->
            try {
                val app = pm.getApplicationInfo(pkg, 0)
                if (!UserHandle.isApp(app.uid)) return@mapNotNull null
                val shared = pm.getPackagesForUid(app.uid).orEmpty()
                val percent = shared.minOfOrNull { saved.optInt(it, 100).coerceIn(0, 100) } ?: 100
                AppVolumeEntry(pkg, app.uid, app.loadLabel(pm).toString(), app.loadIcon(pm),
                    percent, app.uid in playing, shared.any { saved.has(it) }, app.uid in effects)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }.distinctBy { it.uid }.sortedWith(compareBy(Collator.getInstance()) { it.label })
        return AppVolumeState(user, entries)
    }

    suspend fun installedApps(user: Int): List<AppVolumeEntry> = suspendCancellableCoroutine { call ->
        background.execute {
            val pm = context.createContextAsUser(UserHandle.of(user), 0).packageManager
            val saved = levels(user)
            val apps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { activity ->
                    val app = activity.activityInfo.applicationInfo
                    if (!UserHandle.isApp(app.uid)) null else AppVolumeEntry(
                        app.packageName, app.uid, app.loadLabel(pm).toString(), app.loadIcon(pm),
                        saved.optInt(app.packageName, 100).coerceIn(0, 100), false,
                        saved.has(app.packageName))
                }.distinctBy { it.uid }.sortedWith(compareBy(Collator.getInstance()) { it.label })
            if (call.isActive) call.resume(apps)
        }
    }

    fun setLevel(entry: AppVolumeEntry, user: Int, percent: Int) {
        if (UserHandle.getUserId(entry.uid) != user) return
        val key = user to entry.uid
        pending.remove(key)?.let { main.removeCallbacks(it) }
        val operation = Runnable {
            pending.remove(key)
            write(user) { json ->
                packagesFor(entry, user).forEach {
                    json.put(it, percent.coerceIn(0, 100))
                }
            }
        }
        pending[key] = operation
        main.postDelayed(operation, 40)
    }

    fun remove(entry: AppVolumeEntry, user: Int) {
        if (UserHandle.getUserId(entry.uid) != user) return
        pending.remove(user to entry.uid)?.let { main.removeCallbacks(it) }
        write(user) { json ->
            json.remove(entry.packageName)
            packagesFor(entry, user).forEach { json.remove(it) }
        }
    }

    fun reset(user: Int) {
        if (user < 0) return
        pending.keys.filter { it.first == user }.forEach { key ->
            pending.remove(key)?.let { main.removeCallbacks(it) }
        }
        write(user) { json -> json.keys().asSequence().toList().forEach { json.remove(it) } }
    }

    private fun packagesFor(entry: AppVolumeEntry, user: Int): List<String> {
        val pm = context.packageManager
        return try {
            if (pm.getPackageUidAsUser(entry.packageName, user) == entry.uid)
                pm.getPackagesForUid(entry.uid).orEmpty().toList() else emptyList()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { emptyList() }
    }

    private fun write(user: Int, change: (JSONObject) -> Unit) {
        background.execute {
            synchronized(writeLock) {
                val json = levels(user)
                change(json)
                Settings.Secure.putStringForUser(context.contentResolver, KEY,
                    if (json.length() == 0) null else json.toString(), user)
            }
        }
    }

    private companion object {
        const val KEY = "arkui_app_volume_levels"
        val MEDIA_USAGES = setOf(android.media.AudioAttributes.USAGE_MEDIA,
            android.media.AudioAttributes.USAGE_GAME, android.media.AudioAttributes.USAGE_UNKNOWN)
    }
}
