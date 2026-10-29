package cn.wavenote.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only capability URI for SDK export files. No private log segments are exposed. */
class DiagnosticsShareProvider : ContentProvider() {
    companion object {
        private val namePattern = Regex("wavenote-diagnostics-[0-9]+-[0-9a-fA-F-]{36}\\.jsonl")
        internal fun resolve(root: File, name: String): File {
            if (!namePattern.matches(name)) throw FileNotFoundException("Invalid diagnostics name")
            val file = File(root, name)
            if (file.canonicalFile.parentFile != root.canonicalFile || !file.isFile ||
                System.currentTimeMillis() - file.lastModified() >= 86400_000L) throw FileNotFoundException("Diagnostics unavailable")
            return file
        }
        fun uri(context: Context, file: File): Uri {
            val root = File(context.cacheDir, "WaveNoteDiagnosticsExports")
            if (resolve(root, file.name).canonicalFile != file.canonicalFile) throw FileNotFoundException("Invalid diagnostics location")
            return Uri.Builder().scheme("content").authority(context.packageName + ".diagnostics").appendPath(file.name).build()
        }
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val owner = context ?: throw FileNotFoundException()
        if (uri.scheme != "content" || uri.authority != owner.packageName + ".diagnostics" ||
            uri.pathSegments.size != 1 || uri.query != null || uri.fragment != null) throw FileNotFoundException("Invalid diagnostics URI")
        return resolve(File(owner.cacheDir, "WaveNoteDiagnosticsExports"), uri.pathSegments.single())
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Diagnostics are read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String { file(uri); return "application/octet-stream" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns, 1).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only")
}
