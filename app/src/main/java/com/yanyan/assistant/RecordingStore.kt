package com.yanyan.assistant

import android.content.Context
import android.util.Log
import java.io.File

/**
 * M3b：录音文件的清理与占用统计。
 *
 * 规则（expG09 要求）：
 * - **成功即删**：上传 + 识别成功的那段 WAV 立刻删（文字已经在调试页里，音频没必要留）；
 * - **失败保留**：上传失败 / 本地拦下的先留着，给「点重试」用；重试成功后再删；
 * - **兜底**：超过 7 天、或目录总大小超过 200 MB，按最旧的开始删，绝不无限堆；
 * - **不删正在用的**：正在录的那段、正在上传的那段都登记在 [active] 里，清理时跳过。
 */
object RecordingStore {
    private const val TAG = "RecordingStore"

    /** 目录总大小上限：超过就按最旧优先删到以下。 */
    const val MAX_DIR_BYTES = 200L * 1024 * 1024

    /** 最长保留时间：7 天。 */
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private const val DIR_NAME = "recordings"

    /** 正在录制 / 正在上传的文件，清理时必须跳过。 */
    private val active = LinkedHashSet<String>()

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

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

    /** 成功识别后调用：不是正在上传/录制的文件就删掉。返回是否真的删了。 */
    fun deleteAfterSuccess(file: File?): Boolean {
        if (file == null) return false
        if (isActive(file)) {
            Log.i(TAG, "跳过删除（文件仍被占用）：${file.name}")
            return false
        }
        val ok = try {
            file.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "删除录音失败：${file.name} ${t.javaClass.simpleName}")
            false
        }
        if (ok) Log.i(TAG, "识别成功，已删除本地录音：${file.name}")
        return ok
    }

    /** 当前占用：段数与总字节（只算 .wav，不含临时文件）。 */
    fun usage(ctx: Context): Pair<Int, Long> {
        val files = wavFiles(ctx)
        return files.size to files.sumOf { it.length() }
    }

    private fun wavFiles(ctx: Context): List<File> {
        val d = dir(ctx)
        if (!d.isDirectory) return emptyList()
        return d.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()
    }

    /**
     * 兜底清理：先按 7 天过期删，再按总大小超过 200 MB 从最旧删起。
     * 正在用的文件一律跳过。返回删掉的段数。
     */
    fun prune(ctx: Context): Int {
        val now = System.currentTimeMillis()
        var deleted = 0
        val files = wavFiles(ctx)
        for (f in files) {
            if (isActive(f)) continue
            if (now - f.lastModified() > MAX_AGE_MS) {
                if (f.delete()) {
                    deleted++
                    Log.i(TAG, "过期清理（>7 天）：${f.name}")
                }
            }
        }
        var remaining = wavFiles(ctx).sortedBy { it.lastModified() }
        var total = remaining.sumOf { it.length() }
        while (total > MAX_DIR_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.first()
            remaining = remaining.drop(1)
            if (isActive(oldest)) continue
            val size = oldest.length()
            if (oldest.delete()) {
                total -= size
                deleted++
                Log.i(TAG, "超限清理（>200 MB，最旧优先）：${oldest.name}")
            }
        }
        if (deleted > 0) Log.i(TAG, "兜底清理完成，删了 $deleted 段")
        return deleted
    }

    /**
     * 「立即清空录音」：删掉所有不在用的 WAV。
     * @return 删掉的段数
     */
    fun clearAll(ctx: Context): Int {
        var deleted = 0
        for (f in wavFiles(ctx)) {
            if (isActive(f)) {
                Log.i(TAG, "清空时跳过正在用的：${f.name}")
                continue
            }
            if (f.delete()) deleted++
        }
        Log.i(TAG, "手动清空录音：删了 $deleted 段")
        return deleted
    }
}
