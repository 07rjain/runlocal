package app.pocket.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.File

data class PhoneModelSource(val uri: String, val tree: Boolean, val name: String)
data class IndexedModelSource(val source: PhoneModelSource, val scan: PhoneModelScan, val scannedAt: Long)
data class PhoneLibraryIndex(val sources: List<IndexedModelSource> = emptyList())

class PhoneModelLibrary(private val context: Context) {
    private val file = File(context.filesDir, "phone-model-library.json")
    private val sessionGrants = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()
    private val imports = context.getSharedPreferences("phone-model-imports", Context.MODE_PRIVATE)
    private fun importKey(model: FoundPhoneModel) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${model.key}:${model.size}:${model.modified}".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun imported(model: FoundPhoneModel): Pair<String, String>? = imports.getString(importKey(model), null)?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun rememberImported(model: FoundPhoneModel, kind: String, id: String) { imports.edit().putString(importKey(model), "$kind:$id").apply() }
    fun index(): PhoneLibraryIndex = runCatching { gson.fromJson(file.readText(), PhoneLibraryIndex::class.java) }.getOrNull() ?: PhoneLibraryIndex()
    private fun save(index: PhoneLibraryIndex) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(gson.toJson(index).toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun hasAccess(source: PhoneModelSource) = source.uri in sessionGrants || context.contentResolver.persistedUriPermissions.any { it.uri.toString() == source.uri && it.isReadPermission }
    fun remember(uri: Uri, tree: Boolean): PhoneModelSource {
        require(uri.scheme == "content") { "Choose a model file or folder using the Android picker." }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        sessionGrants.add(uri.toString())
        val access = AndroidPhoneDocuments(context, uri.takeIf { tree })
        val source = PhoneModelSource(uri.toString(), tree, access.document(uri, tree).name)
        val existing = index().sources
        val previous = existing.find { it.source.uri == source.uri }
        save(PhoneLibraryIndex(existing.filterNot { it.source.uri == source.uri } + (previous?.copy(source = source)
            ?: IndexedModelSource(source, PhoneModelScan(emptyList(), emptyList(), false), 0))))
        return source
    }
    fun adoptPersistedLocations() {
        val known = index().sources.map { it.source.uri }.toSet()
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.uri.toString() !in known }.forEach { grant ->
            runCatching { remember(grant.uri, DocumentsContract.isTreeUri(grant.uri)) }
        }
    }
    fun refresh(): PhoneLibraryIndex {
        val indexed = index().sources.map { previous ->
            val source = previous.source
            if (!hasAccess(source)) return@map previous.copy(scan = previous.scan.copy(warnings = listOf("Access expired. Choose this folder or file again to reconnect it.")))
            runCatching {
                val uri = Uri.parse(source.uri)
                val access = AndroidPhoneDocuments(context, uri.takeIf { source.tree })
                IndexedModelSource(source, PhoneModelScanner(access).scan(access.document(uri, source.tree)), System.currentTimeMillis())
            }.getOrElse { previous.copy(scan = previous.scan.copy(warnings = listOf("Could not read this location. Reconnect it, or choose individual model files."))) }
        }
        return PhoneLibraryIndex(indexed).also { save(it) }
    }
    fun forget(source: PhoneModelSource) {
        save(PhoneLibraryIndex(index().sources.filterNot { it.source.uri == source.uri }))
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        sessionGrants.remove(source.uri)
    }
    fun discovered(): List<Pair<FoundPhoneModel, PhoneModelSource>> {
        val seen = mutableSetOf<String>()
        return index().sources.sortedBy { !hasAccess(it.source) }.flatMap { group -> group.scan.models.map { it to group.source } }.filter { seen.add(it.first.key) }
    }
}

private class AndroidPhoneDocuments(private val context: Context, private val tree: Uri?) : PhoneDocuments {
    private fun key(uri: Uri): String = runCatching { "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" }.getOrDefault(uri.toString())
    fun document(uri: Uri, isTree: Boolean = false): PhoneDocument {
        val target = if (isTree) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        context.contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "This model location is no longer available." }
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) } ?: "Model files"
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) } ?: -1
            val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE).takeIf { it >= 0 }?.let { cursor.getString(it) }
            return PhoneDocument(key(target), target.toString(), name.take(512), isTree || mime == DocumentsContract.Document.MIME_TYPE_DIR, size)
        }
        error("The selected location could not be opened.")
    }
    override fun children(directory: PhoneDocument): List<PhoneDocument> {
        val root = tree ?: error("Folder access is required.")
        val parent = Uri.parse(directory.uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent))
        val result = mutableListOf<PhoneDocument>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 5001) {
                val id = cursor.getString(0) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(root, id)
                result.add(PhoneDocument(key(uri), uri.toString(), cursor.getString(1).orEmpty().take(512), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (cursor.isNull(3)) -1 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4)))
            }
        } ?: error("Could not list this folder.")
        return result
    }
    override fun prefix(file: PhoneDocument, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
            val buffer = ByteArray(minOf(maximum, 16384))
            while (output.size() < maximum) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximum - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } ?: error("File cannot be read.")
        return output.toByteArray()
    }
}
