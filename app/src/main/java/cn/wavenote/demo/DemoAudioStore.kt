package cn.wavenote.demo

import java.io.File
import java.security.MessageDigest

/** 完整下载才写入索引；只在后台队列访问磁盘。partial 永不视作可播放文件。 */
class DemoAudioStore(private val root: File) {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun directory(sn: String, file: DemoAudioFile) = File(File(root, digest(sn)), digest(file.key))
    fun prepare(sn: String, file: DemoAudioFile): Pair<File, Boolean> {
        val dir = directory(sn, file)
        check(dir.isDirectory || dir.mkdirs()) { "mkdir" }
        val marker = File(dir, "complete.txt")
        val lines = runCatching { marker.readLines() }.getOrNull()
        if (lines?.size == 2) {
            val name = lines[0]; val size = lines[1].toLongOrNull()
            val result = File(dir, name)
            if (name == File(name).name && name.endsWith(".ogg") && size != null && size > 0 && result.isFile && result.length() == size) return result to true
        }
        return File(dir, "${java.util.UUID.randomUUID()}.ogg") to false
    }
    fun commit(result: File, sn: String, file: DemoAudioFile) {
        val dir = directory(sn, file)
        check(result.parentFile == dir && result.extension == "ogg" && result.isFile && result.length() > 0)
        val temp = File(dir, "complete.${java.util.UUID.randomUUID()}.tmp")
        temp.writeText("${result.name}\n${result.length()}")
        java.nio.file.Files.move(temp.toPath(), File(dir, "complete.txt").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
