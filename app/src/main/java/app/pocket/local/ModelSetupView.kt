package app.pocket.local

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

/** Model setup only; prompts and generated outputs belong to Chat. */
class ModelSetupView(
    private val activity: Activity,
    private val store: ImageStore,
    private val importing: Boolean,
    private val importAsset: (ModelRole, String) -> Unit,
    private val remove: (ImageAsset) -> Unit,
    private val openLink: (String) -> Unit,
    private val setupAction: (String, String?) -> Unit
) : LinearLayout(activity) {
    private val prefs = activity.getSharedPreferences("image-ui", 0)
    private val green = 0xff335942.toInt()
    private val ink = 0xff222f27.toInt()
    private val muted = 0xff6f7970.toInt()
    private lateinit var setupStatus: TextView
    private lateinit var setupProgress: ProgressBar
    private var shownState = ""
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 15) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0xffe1e5da.toInt()) }
    private fun label(value: String, size: Float = 13f, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun add(view: View, top: Int = 12) { addView(view, LayoutParams(-1, -2).apply { topMargin = dp(top) }) }
    private fun action(value: String, primary: Boolean = false, callback: () -> Unit) = label(value, 13f, if (primary) Color.WHITE else green, true).apply {
        setPadding(dp(12), dp(14), dp(12), dp(14)); minHeight = dp(48); gravity = Gravity.CENTER
        background = bg(if (primary) green else 0xffe9eee0.toInt()); isClickable = true; isFocusable = true
        setOnClickListener { callback() }
    }
    private fun field(key: String, hintValue: String, default: String = "", numeric: Boolean = false): EditText = EditText(activity).apply {
        hint = hintValue; setText(prefs.getString(key, default)); textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg(0xfff7f8f2.toInt()); minHeight = dp(48)
        if (numeric) { isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        else { minLines = 2; maxLines = 5; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        contentDescription = hintValue
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { prefs.edit().putString(key, s.toString()).apply() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun size(bytes: Long) = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
    private fun locked() = importing || store.running() || store.setupRunning()
    init { orientation = VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = bg(Color.WHITE, 22); build() }

    private fun build() {
        runCatching { store.activateReadySetup() }
        removeAllViews()
        val moss = store.family == "moss"
        val advanced = prefs.getBoolean("advanced-image-settings", false)
        add(label("Add from Hugging Face", 20f, bold = true), 0)
        add(label("Paste a model or LoRA link. Add model downloads matching files automatically, including over mobile data.", 12f, muted), 6)
        val current = store.activeModel.ifEmpty { store.assigned(if (moss) ModelRole.DIFFUSION else ModelRole.CHECKPOINT)?.name.orEmpty() }
        if (current.isNotEmpty()) add(label("Model · ${current.substringAfter('/')}", 13f, green, true), 18)
        val link = field("model-link", "Paste a Hugging Face model or LoRA link")
        link.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        link.maxLines = 3
        add(link, 16)
        add(action("Add model", current.isEmpty()) {
            if (!locked()) setupAction(ModelDownloadService.RESOLVE, link.text.toString().trim())
        }.apply { isEnabled = !locked(); alpha = if (isEnabled) 1f else .55f }, 8)
        setupStatus = label("", 12f, green).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        add(setupStatus, 10)
        setupProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        add(setupProgress, 5)
        val setup = store.setup()
        val plan = runCatching { ModelSetupPlan.fromJson(setup.optString("plan")) }.getOrNull()
        if (store.setupRunning()) add(action("Pause setup") { setupAction(ModelDownloadService.PAUSE, null) })
        else if (plan != null && setup.optString("state") in listOf("review", "paused", "error")) {
            val memory = ActivityManager.MemoryInfo().also { activity.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val missing = plan.files.filter { store.cached(it) == null }
            val bytes = store.remainingDownloadBytes(plan)
            val canFit = plan.estimatedRam < memory.totalMem - 1024L * 1024 * 1024
            val storageFits = bytes + 256L * 1024 * 1024 < StatFs(activity.filesDir.path).availableBytes
            add(label(plan.title, 15f, bold = true))
            add(label(if (missing.isEmpty()) "Already downloaded. Your saved files will be reused." else if (bytes == 0L) "Files downloaded. Finish setup to check them." else "${size(bytes)} to download · required files are chosen automatically.", 12f, muted), 5)
            if (!canFit) add(label("This model is too large for this phone’s RAM. Try a lighter model below.", 12f, 0xff8c4a28.toInt()))
            else if (!storageFits) add(label("Free at least ${size(bytes + 256L * 1024 * 1024)} of storage to set up this model.", 12f, muted))
            else add(action(if (missing.isEmpty()) "Use downloaded model" else if (bytes == 0L) "Finish setup" else if (setup.optString("state") in listOf("paused", "error")) "Resume setup" else "Download & use model", true) {
                if (!locked()) setupAction(ModelDownloadService.DOWNLOAD, null)
            })
            if (plan.family == "moss") add(label("MOSS setup is experimental. Successful generation on your phone has not been verified.", 11f, muted), 7)
            add(action("View model & license  ↗") { openLink("https://huggingface.co/${plan.repo}") }, 8)
        } else if (setup.optString("state") in listOf("paused", "error") && plan == null) {
            add(action("Try this link again") { if (!locked()) setupAction(ModelDownloadService.RESOLVE, link.text.toString().trim()) })
        }
        if (current.isEmpty() || setup.optString("state") in listOf("error", "review", "paused")) add(action("Try a lighter starter model") {
            if (!locked()) {
                prefs.edit().putString("model-link", STARTER).apply()
                setupAction(ModelDownloadService.RESOLVE, STARTER)
            }
        })
        add(action(if (advanced) "Advanced  ▴" else "Advanced  ▾") {
            prefs.edit().putBoolean("advanced-image-settings", !advanced).apply(); build()
        }, 24)
        if (advanced) {
            add(label("Local image files", 15f, bold = true))
            add(action(if (moss) "Pipeline: MOSS Image SFT  ▾" else "Pipeline: Stable Diffusion 1.x  ▾") {
                if (!locked()) AlertDialog.Builder(activity).setTitle("Image pipeline").setItems(arrayOf("Stable Diffusion 1.x", "MOSS Image SFT")) { _, which -> store.family = if (which == 0) "sd15" else "moss"; build() }.show()
            })
            store.roles().forEach { component(it) }; component(ModelRole.LORA)
            add(action("Compatible local files  ↗") { openLink(if (moss) "https://github.com/leejet/stable-diffusion.cpp/blob/290148d2e31ca6ef9757d567905776b5f3646db5/docs/moss_image.md" else "https://github.com/leejet/stable-diffusion.cpp/blob/290148d2e31ca6ef9757d567905776b5f3646db5/docs/sd.md") })
            if (store.assets().isNotEmpty()) add(action("Manage downloaded & imported files") {
                if (locked()) return@action
                val assets = store.assets()
                AlertDialog.Builder(activity).setTitle("Remove an app-private copy").setItems(assets.map { "${it.name} · ${it.role.label}" }.toTypedArray()) { _, which -> remove(assets[which]) }.setNegativeButton("Close", null).show()
            })
            if (store.incompleteDownloadBytes() > 0) add(action("Discard unfinished downloads · ${size(store.incompleteDownloadBytes())}") {
                if (locked()) return@action
                AlertDialog.Builder(activity).setTitle("Discard unfinished downloads?").setMessage("This frees partial-download storage. Completed models are kept.")
                    .setNegativeButton("Keep", null).setPositiveButton("Discard") { _, _ ->
                        runCatching { store.discardIncompleteDownloads() }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                        build()
                    }.show()
            })
        }
        shownState = token()
        refreshJob()
    }
    private fun component(role: ModelRole) {
        add(label(if (role == ModelRole.LORA) "LoRA adapter (optional)" else role.label, 12f, muted))
        add(action(store.assigned(role)?.name ?: "＋  Import file") {
            if (locked()) return@action
            val choices = store.assets().filter { it.role == role }
            val names = choices.map { it.name } + listOf("Import a new file…") + if (role == ModelRole.LORA) listOf("Use no adapter") else emptyList()
            AlertDialog.Builder(activity).setTitle(role.label).setItems(names.toTypedArray()) { _, index ->
                when { index < choices.size -> { store.assign(role, choices[index]); build() }; index == choices.size -> importAsset(role, store.family); else -> { store.assign(role, null); build() } }
            }.setNegativeButton("Close", null).show()
        }, 5)
    }
    private fun token() = "${store.job().optString("state")}:${store.setup().optString("state")}:${store.setup().optString("id")}:${store.activeModel}"
    fun refreshJob() {
        runCatching { store.activateReadySetup() }.onFailure { setupStatus.text = it.message }
        if (token() != shownState) { build(); return }
        val setup = store.setup()
        setupStatus.text = when (setup.optString("state")) {
            "resolving", "downloading", "verifying", "error", "paused" -> setup.optString("message")
            "ready" -> "Model added. Select it in Chat to start."
            else -> ""
        }
        val total = setup.optLong("total"); val bytes = setup.optLong("bytes")
        setupProgress.visibility = if (store.setupRunning()) View.VISIBLE else View.GONE
        setupProgress.isIndeterminate = total <= 0 || setup.optString("state") == "resolving"
        if (total > 0) {
            setupProgress.progress = (bytes.toDouble() / total * 1000).toInt().coerceIn(0, 1000)
            if (store.setupRunning()) setupStatus.append("\n${size(bytes)} of ${size(total)}")
        }

    }
    companion object { const val STARTER = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF" }
}
