package com.yanyan.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * M6a：语音回复（TTS）音频的本地缓存。
 *
 * 目录：`filesDir/tts/`
 *   - `<speakId>.pcm`：边收边写的原始 PCM（24 kHz / 16-bit / 单声道，实测接口参数）；
 *   - `<speakId>.wav`：收完后加上 44 字节 WAV 头，供「点播放图标重播」直接用 MediaPlayer 播。
 *
 * 保留策略（按 M3 文档 2026-11-16 的修订）：
 *   - 周期：设置里 7 天 / 30 天 / 不自动删，默认 7 天；
 *   - 兜底：目录总大小超过 500 MB 时按最旧删起；
 *   - 正在收 / 正在播的文件登记在 [active] 里，清理时跳过；
 *   - 「立即清理」删掉所有不在用的音频。
 */
object TtsStore {
    private const val TAG = "TtsStore"

    /** 目录兜底上限：500 MB（M3 文档「总量兜底」）。 */
    const val MAX_DIR_BYTES = 500L * 1024 * 1024

    /** 保留期选项（0 = 不自动删）。 */
    val KEEP_DAY_OPTIONS = intArrayOf(7, 30, 0)

    private const val DIR_NAME = "tts"

    /** 正在收 / 正在播的文件，清理时必须跳过。 */
    private val active = LinkedHashSet<String>()

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    /** 确保目录存在（第一次写缓存时目录还不存在，直接 new FileOutputStream 会 FileNotFoundException）。 */
    fun ensureDir(ctx: Context): File {
        val d = dir(ctx)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun key(file: File): String = file.absolutePath

    fun markActive(file: File?) {
        if (file == null) return
        synchronized(active) { active.add(key(file)) }
    }

    fun unmarkActive(file: File?) {
        if (file == null) return
        synchronized(active) { active.remove(key(file)) }
    }

    fun isActive(file: File): Boolean = synchronized(active) { active.contains(key(file)) }

    fun pcmFile(ctx: Context, id: String): File = File(dir(ctx), safeName(id) + ".pcm")

    fun wavFile(ctx: Context, id: String): File = File(dir(ctx), safeName(id) + ".wav")

    private fun safeName(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
        return if (cleaned.isEmpty()) "speak" else cleaned.take(72)
    }

    /**
     * 把边收边写的 PCM 收尾成可播放的 WAV（44 字节头 + 原样数据）。
     * @return 生成的 WAV 文件；PCM 不存在或为空时返回 null（并清理残留）
     */
    fun finalizeWav(ctx: Context, id: String, sampleRate: Int, channels: Int): File? {
        ensureDir(ctx)
        val pcm = pcmFile(ctx, id)
        val wav = wavFile(ctx, id)
        if (!pcm.isFile || pcm.length() <= 0L) {
            if (pcm.exists()) pcm.delete()
            return null
        }
        return try {
            val header = wavHeader(pcm.length(), sampleRate, channels)
            // 头和 PCM 必须在同一个顺序写里落盘：RandomAccessFile 的写指针在开头，
            // 先写头再写数据只能这样来（分开两次打开会把头覆盖掉，MediaPlayer 会报 MEDIA_ERROR_UNKNOWN）
            RandomAccessFile(wav, "rw").use { out ->
                out.setLength(0)
                out.write(header)
                pcm.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            pcm.delete()
            wav
        } catch (t: Throwable) {
            Log.w(TAG, "收尾 WAV 失败：${t.javaClass.simpleName}")
            null
        }
    }

    /** 44 字节标准 WAV 头（PCM 16-bit）。 */
    private fun wavHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffSize = 36 + dataBytes
        val out = java.io.ByteArrayOutputStream(44)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(riffSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(le32(16))
        out.write(le16(1))
        out.write(le16(channels))
        out.write(le32(sampleRate.toLong()))
        out.write(le32(byteRate.toLong()))
        out.write(le16(blockAlign))
        out.write(le16(bitsPerSample))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(le32(dataBytes))
        return out.toByteArray()
    }

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    /** 占用：文件数与总字节（.pcm 与 .wav 都算）。 */
    fun usage(ctx: Context): Pair<Int, Long> {
        val files = audioFiles(ctx)
        return files.size to files.sumOf { it.length() }
    }

    private fun audioFiles(ctx: Context): List<File> {
        val d = dir(ctx)
        if (!d.isDirectory) return emptyList()
        return d.listFiles()?.filter { it.isFile && (it.name.endsWith(".wav") || it.name.endsWith(".pcm")) }
            ?: emptyList()
    }

    /** 该 speakId 有没有可重播的 WAV。 */
    fun hasWav(ctx: Context, id: String): Boolean = wavFile(ctx, id).isFile

    fun delete(ctx: Context, id: String): Int {
        var n = 0
        for (f in listOf(pcmFile(ctx, id), wavFile(ctx, id))) {
            if (f.isFile && !isActive(f) && f.delete()) n++
        }
        return n
    }

    /**
     * 兜底清理：先按保留天数（7 / 30 / 不自动删）删过期，再按总大小超过 500 MB 从最旧删起。
     * @return 删掉的文件数
     */
    fun prune(ctx: Context): Int {
        val keepDays = LocalStore.ttsKeepDays(ctx)
        val now = System.currentTimeMillis()
        var deleted = 0
        if (keepDays > 0) {
            val maxAgeMs = keepDays.toLong() * 24 * 3600 * 1000
            for (f in audioFiles(ctx)) {
                if (isActive(f)) continue
                if (now - f.lastModified() > maxAgeMs) {
                    if (f.delete()) deleted++
                }
            }
        }
        var remaining = audioFiles(ctx).sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        while (total > MAX_DIR_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.first()
            remaining = remaining.drop(1)
            if (isActive(oldest)) continue
            val size = oldest.length()
            if (oldest.delete()) {
                total -= size
                deleted++
            }
        }
        if (deleted > 0) Log.i(TAG, "语音缓存清理：删了 $deleted 个文件（保留 ${if (keepDays == 0) "不自动删" else "$keepDays 天"}）")
        return deleted
    }

    /** 「立即清理」：删掉所有不在用的语音缓存。 */
    fun clearAll(ctx: Context): Int {
        var deleted = 0
        for (f in audioFiles(ctx)) {
            if (isActive(f)) {
                Log.i(TAG, "清理时跳过正在用的：${f.name}")
                continue
            }
            if (f.delete()) deleted++
        }
        Log.i(TAG, "立即清理语音缓存：删了 $deleted 个文件")
        return deleted
    }
}
