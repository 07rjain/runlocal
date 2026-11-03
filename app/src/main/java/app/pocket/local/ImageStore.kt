package app.pocket.local

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ImageAsset(val id: String, val name: String, val role: ModelRole, val file: File, val remoteKey: String = "")

class ImageStore(private val context: Context) {
    private val directory = File(context.filesDir, "image-models").apply { mkdirs() }
    val outputs = File(context.filesDir, "generated").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("image-models", Context.MODE_PRIVATE)
    var family: String
        get() = prefs.getString("family", "sd15") ?: "sd15"
        set(value) { require(value in listOf("sd15", "moss")); prefs.edit().putString("family", value).remove("activeModel").remove("activeTrigger").apply() }
    val activeModel: String get() = prefs.getString("activeModel", "").orEmpty()
    val activeTrigger: String get() = prefs.getString("activeTrigger", "").orEmpty()

    fun assets(): List<ImageAsset> = directory.listFiles().orEmpty().filter { it.name.endsWith(".meta") }.mapNotNull { meta ->
        runCatching {
            val json = JSONObject(meta.readText())
            val id = meta.nameWithoutExtension
            val file = File(directory, "$id.${json.getString("extension")}")
            if (file.isFile) ImageAsset(id, json.getString("name"), ModelRole.valueOf(json.getString("role")), file, json.optString("remoteKey")) else null
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    fun assigned(role: ModelRole, profile: String = family): ImageAsset? {
        val id = prefs.getString("${profile}_${role.name}", null)
        return assets().find { it.id == id }
    }
    fun assign(role: ModelRole, asset: ImageAsset?, profile: String = family) {
        require(asset == null || asset.role == role)
        prefs.edit().putString("${profile}_${role.name}", asset?.id).remove("activeModel").remove("activeTrigger")
            .remove("${profile}_savedLabel").remove("${profile}_savedTrigger").apply()
    }

    fun import(uri: Uri, role: ModelRole, progress: (Long) -> Unit): ImageAsset {
        var name = "model"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in if (role == ModelRole.TOKENIZER) listOf("json") else listOf("gguf", "safetensors")) { "Select ${if (role == ModelRole.TOKENIZER) "tokenizer.json" else "a .gguf or .safetensors file"}. Download repository files individually before importing." }
        val reserve = 256L * 1024 * 1024
        require(size < 0 || size < StatFs(directory.path).availableBytes - reserve) { "Not enough storage for a full app-private copy of this file." }
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.part")
        val output = File(directory, "$id.$extension")
        val metadata = File(directory, "$id.meta")
        try {
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { stream ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(StatFs(directory.path).availableBytes > count + reserve) { "Storage is almost full. Import stopped safely." }
                        stream.write(buffer, 0, count); copied += count; progress(copied)
                    }
                    stream.fd.sync()
                }
            } ?: error("Could not open this download.")
            require(size < 0 || size == copied) { "The downloaded file is incomplete." }
            ModelInspector.validate(partial, name, role)
            check(partial.renameTo(output)) { "Could not finish saving the model." }
            metadata.writeText(JSONObject().put("name", name).put("role", role.name).put("extension", extension).toString())
            return ImageAsset(id, name, role, output)
        } catch (e: Exception) { output.delete(); metadata.delete(); throw e }
        finally { partial.delete() }
    }

    fun delete(asset: ImageAsset) {
        val profiles = listOf("sd15", "moss").filter { assigned(asset.role, it)?.id == asset.id }
        check(asset.file.delete()) { "Could not delete the app's copy." }
        File(directory, "${asset.id}.meta").delete()
        profiles.forEach { assign(asset.role, null, it) }
    }
    fun roles(profile: String = family) = if (profile == "moss") listOf(ModelRole.DIFFUSION, ModelRole.ENCODER, ModelRole.VAE, ModelRole.TOKENIZER) else listOf(ModelRole.CHECKPOINT)
    fun configuredProfiles() = listOf("sd15", "moss").filter { profile -> roles(profile).all { assigned(it, profile) != null } }
    fun profileLabel(profile: String) = (if (profile == family) activeModel else prefs.getString("${profile}_savedLabel", "").orEmpty())
        .ifEmpty { if (profile == "moss") "MOSS Image SFT" else "Stable Diffusion 1.x" }
    fun selectLocalProfile(profile: String) {
        require(profile in configuredProfiles()) { "Some files for this image model are missing." }
        if (profile == family) return
        val edit = prefs.edit().putString("${family}_savedLabel", activeModel).putString("${family}_savedTrigger", activeTrigger)
            .putString("family", profile).putString("activeModel", prefs.getString("${profile}_savedLabel", ""))
            .putString("activeTrigger", prefs.getString("${profile}_savedTrigger", ""))
        check(edit.commit()) { "Could not select the image model." }
    }
    fun missing() = roles().filter { assigned(it) == null }
    fun cached(file: RemoteModelFile): ImageAsset? = if (file.role == ModelRole.TEXT) ModelStore(context).cached(file)?.let { ImageAsset(it.id, it.name, ModelRole.TEXT, it.file, file.key) } else assets().find { it.remoteKey == file.key && it.role == file.role && it.file.length() == file.size }
    fun remainingDownloadBytes(plan: ModelSetupPlan): Long = plan.files.filter { cached(it) == null }.sumOf { file ->
        (file.size - File(context.filesDir, "model-downloads/${file.key}.part").length().coerceAtMost(file.size)).coerceAtLeast(0)
    }
    fun installRemote(source: File, file: RemoteModelFile): ImageAsset {
        file.validate()
        if (file.role == ModelRole.TEXT) return ModelStore(context).installRemote(source, file).let { ImageAsset(it.id, it.name, ModelRole.TEXT, it.file, file.key) }
        cached(file)?.let { return it }
        ModelInspector.validate(source, file.name, file.role)
        val id = file.key
        val extension = file.name.substringAfterLast('.')
        val destination = File(directory, "$id.$extension")
        if (source.canonicalFile != destination.canonicalFile) check(source.renameTo(destination)) { "Could not save the downloaded model." }
        val atomic = AtomicFile(File(directory, "$id.meta"))
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("name", file.name).put("role", file.role.name).put("extension", extension).put("remoteKey", file.key).toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        return ImageAsset(id, file.name, file.role, destination, file.key)
    }
    // AtomicFile.openRead may delete a live writer's .new file. Read the atomically
    // replaced base directly so UI polling cannot disturb the download process.
    fun setup(): JSONObject = runCatching { JSONObject(File(context.filesDir, "model-setup.json").readText()) }.getOrElse { JSONObject().put("state", "idle") }
    fun writeSetup(value: JSONObject) {
        val atomic = AtomicFile(File(context.filesDir, "model-setup.json")); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun setupRunning() = setup().optString("state") in listOf("resolving", "downloading", "verifying")
    fun incompleteDownloadBytes(): Long = File(context.filesDir, "model-downloads").listFiles().orEmpty().filter { it.name.endsWith(".part") }.sumOf { it.length() }
    fun discardIncompleteDownloads() {
        check(!setupRunning() && !running()) { "Pause setup and generation first." }
        File(context.filesDir, "model-downloads").listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { check(it.delete()) { "Could not remove an unfinished download." } }
        writeSetup(JSONObject().put("state", "idle"))
    }
    /** Main-process commit prevents Android's cross-process SharedPreferences cache from hiding new selections. */
    fun activateReadySetup(): Boolean {
        val state = setup()
        val id = state.optString("id")
        if (state.optString("state") != "ready" || id.isEmpty() || prefs.getString("appliedSetup", "") == id) return false
        val plan = ModelSetupPlan.fromJson(state.getString("plan"))
        if (plan.family == "text") {
            val texts = ModelStore(context)
            val model = texts.cached(plan.files.single()) ?: error("The downloaded text model was removed. Add it again.")
            texts.selectedId = model.id
            check(prefs.edit().putString("appliedSetup", id).commit())
            return true
        }
        val selected = plan.files.map { file -> cached(file) ?: error("A downloaded file was removed. Add this model again to repair it.") }
        val edit = prefs.edit().putString("family", plan.family).putString("activeModel", plan.repo).putString("activeTrigger", plan.trigger).putString("appliedSetup", id)
            .putString("${plan.family}_savedLabel", plan.repo).putString("${plan.family}_savedTrigger", plan.trigger)
        ModelRole.entries.forEach { role -> edit.remove("${plan.family}_${role.name}") }
        selected.forEach { edit.putString("${plan.family}_${it.role.name}", it.id) }
        check(edit.commit()) { "Could not save this model's setup." }
        return true
    }

    fun request(prompt: String, negative: String, width: Int, height: Int, steps: Int, seed: Long, cfg: Float, loraScale: Float, strength: Float, input: String,
                profile: String = family, snapshot: Map<String, String>? = null, trigger: String = activeTrigger): JSONObject {
        val family = profile
        val selected = assets()
        fun asset(role: ModelRole) = if (snapshot == null) assigned(role, profile) else selected.find { it.id == snapshot[role.name] && it.role == role }
        require(snapshot == null || snapshot.all { (role, id) -> selected.any { it.id == id && it.role.name == role } }) { "An image component or LoRA for this chat was removed. Select a model again." }
        require(roles(profile).all { asset(it) != null }) { "Some files for this conversation’s image model were removed. Select a model again." }
        require(prompt.isNotBlank() && prompt.length <= 4000) { "Use a prompt between 1 and 4,000 characters." }
        require(width in 128..768 && height in 128..768 && width % 16 == 0 && height % 16 == 0) { "Choose a supported image size." }
        require(steps in 1..50 && cfg in 0f..15f && loraScale in 0f..2f && strength in .1f..1f && seed >= 0) { "Check steps, guidance, adapter strength, image strength and seed." }
        require(family != "moss" || input.isEmpty()) { "MOSS image-to-image has not been enabled. Use the SD 1.x pipeline for image-to-image." }
        val fullPrompt = withModelTrigger(prompt, trigger)
        val json = JSONObject().put("family", family).put("prompt", fullPrompt).put("negative", negative)
            .put("width", width).put("height", height).put("steps", steps).put("seed", seed)
            .put("cfg", cfg.toDouble()).put("loraScale", loraScale.toDouble()).put("strength", strength.toDouble()).put("input", input)
            .put("output", File(outputs, "${UUID.randomUUID()}.png").absolutePath)
        (roles(profile) + ModelRole.LORA).forEach { role -> json.put(role.name, asset(role)?.file?.absolutePath ?: "") }
        return json
    }

    fun job(): JSONObject = runCatching { JSONObject(File(context.filesDir, "image-job.json").readText()) }.getOrElse { JSONObject().put("state", "idle") }
    fun writeJob(json: JSONObject) {
        val atomic = AtomicFile(File(context.filesDir, "image-job.json"))
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun running() = job().optString("state") in listOf("queued", "running")
}
