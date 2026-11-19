package com.yanyan.assistant

import android.content.Context
import android.util.Log
import java.io.File

/**
 * M6 聊天页的「我的语音」缓存。
 *
 * 为什么要单独存一份：录音成功后 [RecordingStore] 会把原文件删掉（M3b 定的策略），
 * 聊天页里那条「我发的语音」就没法重播了。这里在录音刚结束、上传之前复制一份到
 * `files/chat_audio/`，专门给聊天页重播用 —— **不改**上传/识别/删除的任何流程。
 *
 * 占用受控：最多 200 段 / 100 MB，超出按最旧删；设置页「立即清理」也会一起清掉。
 */
object ChatAudioCache {
    private const val TAG = "ChatAudio"
    private const val DIR_NAME = "chat_audio"
    private const val MAX_FILES = 200
    private const val MAX_BYTES = 100L * 1024 * 1024

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR_NAME)

    fun fileFor(ctx: Context, name: String): File = File(dir(ctx), name)

    /**
     * 把一段录音复制进聊天缓存（已经复制过就直接返回）。
     * 只做「复制一份」这一件事，原文件怎么删由原来的链路决定。
     */
    fun cacheRecording(ctx: Context, src: File): File? {
        if (!src.isFile) return null
        val dst = fileFor(ctx, src.name)
        if (dst.isFile && dst.length() > 0) return dst
        return try {
            dir(ctx).mkdirs()
            src.copyTo(dst, overwrite = true)
            prune(ctx)
            Log.i(TAG, "聊天语音已缓存：${src.name} ${dst.length()} B")
            dst
        } catch (t: Throwable) {
            Log.w(TAG, "聊天语音缓存失败：${t.javaClass.simpleName}")
            null
        }
    }

    fun usage(ctx: Context): Pair<Int, Long> {
        val files = list(ctx)
        return Pair(files.size, files.sumOf { it.length() })
    }

    fun clearAll(ctx: Context): Int {
        var n = 0
        list(ctx).forEach { if (it.delete()) n++ }
        Log.i(TAG, "聊天语音缓存已清空：$n 个文件")
        return n
    }

    private fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.toList().orEmpty()

    private fun prune(ctx: Context) {
        val files = list(ctx).sortedBy { it.lastModified() }
        var count = files.size
        var bytes = files.sumOf { it.length() }
        var i = 0
        while (i < files.size && (count > MAX_FILES || bytes > MAX_BYTES)) {
            val f = files[i]
            val len = f.length()
            if (f.delete()) {
                count--
                bytes -= len
            }
            i++
        }
    }
}
