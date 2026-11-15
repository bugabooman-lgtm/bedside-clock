package com.bedside.clock

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/** Builds the alarm tones in code, so the app needs no sound files. */
object Synth {
    const val SR = 44100

    private fun note(
        buf: FloatArray, freq: Double, start: Double, dur: Double, vol: Double,
        attack: Double = 0.01, square: Boolean = false, overtone: Double = 0.0
    ) {
        val s0 = (start * SR).toInt()
        val n = (dur * SR).toInt()
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SR
            val env = if (t < attack) t / attack else exp(-(t - attack) * 5.0 / dur)
            var v = sin(2 * PI * freq * t)
            if (square) v = sign(v) * 0.6
            if (overtone > 0) v += overtone * sin(2 * PI * freq * 2.01 * t)
            buf[idx] += (v * env * vol).toFloat()
        }
    }

    private fun chirp(buf: FloatArray, start: Double, base: Double, rnd: Random) {
        val dur = 0.16
        val n = (dur * SR).toInt()
        val s0 = (start * SR).toInt()
        val peak = base * (1.3 + rnd.nextDouble() * 0.5)
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SR
            val f = if (t < 0.07) base + (peak - base) * (t / 0.07) else peak + (base * 0.8 - peak) * ((t - 0.07) / 0.09)
            phase += 2 * PI * f / SR
            val env = if (t < 0.02) t / 0.02 else exp(-(t - 0.02) * 22)
            buf[idx] += (sin(phase) * env * 0.22).toFloat()
        }
    }

    /** One loop of the named tone, as 16-bit mono PCM. */
    fun pattern(name: String): ShortArray {
        val buf: FloatArray
        when (name) {
            "marimba" -> {
                buf = FloatArray((2.9 * SR).toInt())
                listOf(523.25, 659.25, 783.99, 659.25, 880.0, 783.99, 659.25, 523.25).forEachIndexed { i, f ->
                    note(buf, f, i * 0.22, 0.7, 0.35, 0.004, overtone = 0.12)
                }
            }
            "classic" -> {
                buf = FloatArray((1.3 * SR).toInt())
                for (i in 0 until 4) note(buf, 880.0, i * 0.18, 0.12, 0.25, 0.003, square = true)
            }
            "birds" -> {
                buf = FloatArray((3.2 * SR).toInt())
                val rnd = Random(7)
                var t = 0.1
                repeat(2) {
                    repeat(3 + rnd.nextInt(3)) {
                        chirp(buf, t, 2200 + rnd.nextDouble() * 1800, rnd)
                        t += 0.17 + rnd.nextDouble() * 0.12
                    }
                    t += 0.5 + rnd.nextDouble() * 0.4
                }
            }
            else -> { // chimes
                buf = FloatArray((3.6 * SR).toInt())
                listOf(1046.5, 1318.5, 1568.0, 2093.0, 1568.0, 1318.5).forEachIndexed { i, f ->
                    note(buf, f, i * 0.32, 2.2, 0.22, overtone = 0.2)
                }
            }
        }
        var peak = 0f
        for (v in buf) peak = max(peak, kotlin.math.abs(v))
        val scale = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(buf.size) { (buf[it] * scale * 32000f).toInt().toShort() }
    }

    /** A looping, ready-to-play track for the tone. */
    fun loopingTrack(tone: String, usage: Int): AudioTrack {
        val pcm = pattern(tone)
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, pcm.size, -1)
        return track
    }
}

/** Rain / ocean / fan / deep noise, generated live, with a fade-out timer. */
object SleepPlayer {
    val TYPES = listOf("rain" to "Rain", "ocean" to "Ocean", "fan" to "Fan", "brown" to "Deep noise")

    @Volatile var type = "rain"
    @Volatile var volume = 0.25f
    @Volatile var endAt = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    val isPlaying: Boolean get() = running

    fun play(newType: String, vol: Float, timerMin: Int) {
        stop()
        type = newType
        volume = vol
        setTimer(timerMin)
        running = true
        thread = Thread { loop() }.also { it.start() }
    }

    fun setTimer(min: Int) {
        endAt = if (min > 0) System.currentTimeMillis() + min * 60_000L else 0L
    }

    fun minutesLeft(): Int =
        if (endAt == 0L) -1 else max(1, ((endAt - System.currentTimeMillis()) / 60_000L).toInt() + 1)

    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun loop() {
        val sr = 44100
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, 16384))
            .build()
        track.play()
        val buf = ShortArray(2048)
        val rnd = Random()
        var brown = 0.0
        var lp1 = 0.0
        var lp2 = 0.0
        var n = 0L
        var gain = 0.0
        val fadeIn = 1.0 / (3.0 * sr)
        val fadeOut = 1.0 / (8.0 * sr)
        while (running) {
            val ending = endAt > 0 && System.currentTimeMillis() >= endAt
            val vol = volume.toDouble()
            for (i in buf.indices) {
                val w = rnd.nextDouble() * 2 - 1
                brown = (brown + 0.02 * w) / 1.02
                val s = when (type) {
                    "rain" -> { lp1 += 0.5 * (w - lp1); (w - lp1) * 0.45 + brown * 2.2 }
                    "ocean" -> {
                        val swell = 0.5 + 0.5 * sin(2 * PI * 0.08 * n / sr)
                        lp2 += 0.06 * (brown * 4.0 - lp2)
                        lp2 * (0.25 + swell) * 1.6
                    }
                    "fan" -> { lp1 += 0.15 * (w - lp1); lp1 * 1.1 }
                    else -> brown * 3.5
                }
                gain = if (ending) max(0.0, gain - fadeOut) else minOf(1.0, gain + fadeIn)
                buf[i] = (s * gain * vol * 32000).coerceIn(-32000.0, 32000.0).toInt().toShort()
                n++
            }
            track.write(buf, 0, buf.size)
            if (ending && gain <= 0.0) {
                running = false
                endAt = 0L
            }
        }
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }
}
