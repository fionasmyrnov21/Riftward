package com.riftwardechoprotocol.k7m4x9

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.riftwardechoprotocol.k7m4x9.core.Settings

class GameAudio(private val context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(5).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val sounds = mutableMapOf<String, Int>(); private val loaded = hashSetOf<Int>()
    private var music: MediaPlayer? = null; private var ambient: MediaPlayer? = null
    private var track = ""; private var ambience = ""; private var paused = false; private var settings = Settings()
    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        listOf("click", "move", "shot", "reload", "interact", "ability", "supply", "hit", "round", "emergency", "extract", "warning", "upgrade").forEach { name ->
            runCatching { context.assets.openFd("audio/$name.wav").use { sounds[name] = pool.load(it, 1) } }
        }
    }
    private fun player(name: String): MediaPlayer? = runCatching {
        MediaPlayer().apply { context.assets.openFd("audio/$name.wav").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }; isLooping = true; prepare(); if (!paused) start() }
    }.getOrNull()
    fun scene(mission: Boolean, theme: Int = 0) {
        val next = if (mission) "mission" else "menu"
        if (next != track) { music?.release(); track = next; music = player(next) }
        val nextAmbient = if (mission) "ambience-$theme" else ""
        if (nextAmbient != ambience) { ambient?.release(); ambience = nextAmbient; ambient = if (mission) player(nextAmbient) else null }
        configure(settings)
    }
    fun configure(s: Settings) { settings = s; music?.setVolume(s.music, s.music); ambient?.setVolume(s.effects * .3f, s.effects * .3f) }
    fun play(name: String) { val id = sounds[name] ?: return; if (!paused && id in loaded) pool.play(id, settings.effects, settings.effects, 1, 0, 1f) }
    fun pause() { paused = true; music?.pause(); ambient?.pause(); pool.autoPause() }
    fun resume() { paused = false; music?.start(); ambient?.start(); pool.autoResume() }
    fun close() { music?.release(); ambient?.release(); pool.release() }
}
