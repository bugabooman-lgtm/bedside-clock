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

    private val PURE = listOf(1.0 to 1.0)
    private val CHIME = listOf(1.0 to 1.0, 2.01 to 0.2)
    private val MARIMBA = listOf(1.0 to 1.0, 4.0 to 0.12)
    private val PLUCK = listOf(1.0 to 1.0, 2.0 to 0.3, 3.0 to 0.1, 4.0 to 0.04)
    private val COMB = listOf(1.0 to 1.0, 3.0 to 0.3, 5.4 to 0.12)
    private val BOWL = listOf(1.0 to 1.0, 2.71 to 0.5, 5.12 to 0.25, 8.4 to 0.1)
    private val PIANO = listOf(1.0 to 1.0, 2.0 to 0.45, 3.0 to 0.22, 4.0 to 0.12, 5.0 to 0.06)
    private val BELL = listOf(0.5 to 0.6, 1.0 to 1.0, 1.19 to 0.6, 1.5 to 0.4, 2.0 to 0.5, 2.5 to 0.25, 3.0 to 0.2, 4.07 to 0.1)
    private val XYLO = listOf(1.0 to 1.0, 3.0 to 0.3, 6.2 to 0.1)
    private val PAD = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.33, 4.0 to 0.25, 5.0 to 0.2, 6.0 to 0.16)

    // note names -> Hz
    private fun n(name: String): Double {
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = name.last().digitToInt()
        val semis = names.indexOf(name.dropLast(1))
        val midi = (octave + 1) * 12 + semis
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
    }

    /** Adds one struck/plucked note to the buffer. */
    private fun hit(
        buf: FloatArray, freq: Double, start: Double, dur: Double, vol: Double,
        partials: List<Pair<Double, Double>> = PURE, attack: Double = 0.01,
        decay: Double = 5.0, square: Boolean = false, fadeOut: Double = 0.01
    ) {
        val s0 = (start * SR).toInt()
        val len = (dur * SR).toInt()
        val fadeN = max(1, (fadeOut * SR).toInt())
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SR
            var env = if (t < attack) t / attack else exp(-(t - attack) * decay / dur)
            val left = len - i
            if (left < fadeN) env *= left.toDouble() / fadeN
            var v = 0.0
            for ((ratio, amp) in partials) {
                val s = sin(2 * PI * freq * ratio * t)
                v += amp * (if (square) sign(s) * 0.5 else s)
            }
            buf[idx] += (v * env * vol).toFloat()
        }
    }

    private fun chirp(buf: FloatArray, start: Double, base: Double, rnd: Random) {
        val dur = 0.16
        val len = (dur * SR).toInt()
        val s0 = (start * SR).toInt()
        val peak = base * (1.3 + rnd.nextDouble() * 0.5)
        var phase = 0.0
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SR
            val f = if (t < 0.07) base + (peak - base) * (t / 0.07) else peak + (base * 0.8 - peak) * ((t - 0.07) / 0.09)
            phase += 2 * PI * f / SR
            val env = if (t < 0.02) t / 0.02 else exp(-(t - 0.02) * 22)
            buf[idx] += (sin(phase) * env * 0.22).toFloat()
        }
    }

    private fun seconds(s: Double) = FloatArray((s * SR).toInt())

    /** One loop of the named tone, as 16-bit mono PCM. */
    fun pattern(name: String): ShortArray {
        val buf: FloatArray
        when (name) {
            "marimba" -> {
                buf = seconds(2.9)
                listOf("C5", "E5", "G5", "E5", "A5", "G5", "E5", "C5").forEachIndexed { i, f ->
                    hit(buf, n(f), i * 0.22, 0.7, 0.35, MARIMBA, attack = 0.004)
                }
            }
            "classic" -> {
                buf = seconds(1.3)
                for (i in 0 until 4) hit(buf, 880.0, i * 0.18, 0.12, 0.3, attack = 0.003, decay = 1.0, square = true)
            }
            "digital" -> {
                buf = seconds(1.7)
                for (g in 0 until 2) for (i in 0 until 4) {
                    hit(buf, 2048.0, g * 0.8 + i * 0.12, 0.07, 0.3, attack = 0.002, decay = 0.5, square = true)
                }
            }
            "retro" -> {
                buf = seconds(2.6)
                listOf("C5", "E5", "G5", "C6", "G5", "E5", "D5", "F5", "A5", "D6", "A5", "F5", "G5", "B5", "D6", "G6")
                    .forEachIndexed { i, f -> hit(buf, n(f), i * 0.13, 0.12, 0.22, attack = 0.002, decay = 1.0, square = true) }
            }
            "birds" -> {
                buf = seconds(3.2)
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
            "harp" -> {
                buf = seconds(5.0)
                val up = listOf("C4", "E4", "G4", "C5", "E5", "G5", "C6")
                val up2 = listOf("F3", "A3", "C4", "F4", "A4", "C5", "F5")
                up.forEachIndexed { i, f -> hit(buf, n(f), i * 0.09, 1.8, 0.25, PLUCK, attack = 0.003, decay = 4.0) }
                up2.forEachIndexed { i, f -> hit(buf, n(f), 2.3 + i * 0.09, 1.8, 0.25, PLUCK, attack = 0.003, decay = 4.0) }
            }
            "musicbox" -> {
                buf = seconds(5.4)
                listOf("E6", "C6", "G5", "C6", "D6", "E6", "D6", "C6", "G5", "A5", "B5", "D6", "C6")
                    .forEachIndexed { i, f -> hit(buf, n(f), i * 0.3, 1.4, 0.28, COMB, attack = 0.003, decay = 4.0) }
            }
            "xylophone" -> {
                buf = seconds(3.0)
                listOf("G5", "C6", "E6", "G6", "E6", "C6", "D6", "F6", "A6", "F6", "D6", "G6")
                    .forEachIndexed { i, f -> hit(buf, n(f), i * 0.18, 0.5, 0.3, XYLO, attack = 0.002, decay = 8.0) }
            }
            "piano" -> {
                buf = seconds(5.6)
                listOf("C4", "G4", "E5", "G4", "C5", "G4", "E5", "G4", "A3", "E4", "C5", "E4", "A4", "E4", "C5", "E4")
                    .forEachIndexed { i, f -> hit(buf, n(f), i * 0.25, 1.8, 0.25, PIANO, attack = 0.005, decay = 4.0) }
            }
            "bowl" -> {
                buf = seconds(8.0)
                hit(buf, 220.0, 0.0, 4.2, 0.35, BOWL, attack = 0.02, decay = 2.5, fadeOut = 0.4)
                hit(buf, 293.66, 3.8, 4.2, 0.35, BOWL, attack = 0.02, decay = 2.5, fadeOut = 0.4)
            }
            "church" -> {
                buf = seconds(5.2)
                listOf("E4", "C4", "D4", "G3").forEachIndexed { i, f ->
                    hit(buf, n(f), i * 0.95, 2.5, 0.3, BELL, attack = 0.004, decay = 3.0, fadeOut = 0.2)
                }
            }
            "sunrise" -> {
                buf = seconds(9.6)
                val chords = listOf(
                    listOf("C3", "E3", "G3", "C4"), listOf("A2", "E3", "A3", "C4"),
                    listOf("F2", "F3", "A3", "C4"), listOf("G2", "G3", "B3", "D4")
                )
                chords.forEachIndexed { c, notes ->
                    for (f in notes) {
                        for (detune in listOf(0.997, 1.003)) {
                            hit(buf, n(f) * detune, c * 2.4, 2.6, 0.05, PAD, attack = 0.9, decay = 0.4, fadeOut = 0.6)
                        }
                    }
                }
            }
            "waves" -> {
                buf = seconds(12.0)
                val rnd = Random(3)
                var lp = 0.0
                var brown = 0.0
                for (i in buf.indices) {
                    val w = rnd.nextDouble() * 2 - 1
                    brown = (brown + 0.02 * w) / 1.02
                    lp += 0.08 * (brown * 4 - lp)
                    val swell = sin(PI * i / (buf.size / 2.0)).let { it * it }   // two waves per loop
                    buf[i] = (lp * (0.15 + swell) * 0.9).toFloat()
                }
                hit(buf, n("E5"), 2.6, 2.5, 0.12, CHIME, decay = 4.0)
                hit(buf, n("B5"), 8.6, 2.5, 0.12, CHIME, decay = 4.0)
            }
            else -> { // chimes
                buf = seconds(3.6)
                listOf(1046.5, 1318.5, 1568.0, 2093.0, 1568.0, 1318.5).forEachIndexed { i, f ->
                    hit(buf, f, i * 0.32, 2.2, 0.22, CHIME)
                }
            }
        }
        // make every tone equally loud
        var peak = 0f
        for (v in buf) peak = max(peak, kotlin.math.abs(v))
        val scale = if (peak > 0f) 0.9f / peak else 1f
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
