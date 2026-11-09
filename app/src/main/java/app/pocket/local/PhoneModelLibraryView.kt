package app.pocket.local

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class PhoneModelLibraryView(
    private val activity: Activity, private val texts: ModelStore, private val images: ImageStore,
    private val library: PhoneModelLibrary, private val busy: Boolean,
    private val chooseFolder: () -> Unit, private val chooseFiles: () -> Unit, private val refresh: () -> Unit,
    private val importFound: (FoundPhoneModel) -> Unit,
    private val forget: (PhoneModelSource) -> Unit
) : LinearLayout(activity) {
    private val green = 0xff335942.toInt()
    private val muted = 0xff6f7970.toInt()
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 13f, color: Int = 0xff222f27.toInt(), bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun put(parent: LinearLayout, view: android.view.View, top: Int = 10) { parent.addView(view, LayoutParams(-1, -2).apply { topMargin = dp(top) }) }
    private fun button(value: String, callback: () -> Unit) = label(value, 13f, green, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply { setColor(0xffe9eee0.toInt()); cornerRadius = dp(14).toFloat() }
        isClickable = true; isFocusable = true; isEnabled = !busy; alpha = if (busy) .55f else 1f
        setOnClickListener { callback() }
    }
    private fun card(title: String, description: String, parent: LinearLayout = this): LinearLayout {
        val box = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xffe1e5da.toInt()) }
        }
        put(box, label(title, 16f, bold = true), 0); put(box, label(description, 12f, muted), 5); put(parent, box, 14)
        return box
    }
    private fun size(bytes: Long) = if (bytes < 0) "Size unavailable" else if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
    init {
        orientation = VERTICAL
        val textModels = texts.models()
        val imageAssets = images.assets()
        put(this, button("Connect another model folder", chooseFolder), 18)
        put(this, button("Import model files", chooseFiles), 8)
        put(this, label("Connected folders are scanned automatically. Android requires a first grant for shared folders and hides other apps’ private downloads, including private Cactus models.", 12f, muted))
        val index = library.index()
        if (index.sources.isNotEmpty()) {
            put(this, label("Saved locations", 18f, bold = true), 26)
            put(this, button(if (busy) "Scanning…" else "Refresh folders & files", refresh))
            index.sources.forEach { indexed ->
                val access = library.hasAccess(indexed.source)
                val date = if (indexed.scannedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(indexed.scannedAt)) else "Not scanned yet"
                val box = card(indexed.source.name, "${if (indexed.source.tree) "Folder" else "File"} · ${if (access) date else "Access needed — choose it again to reconnect"}")
                indexed.scan.warnings.take(2).forEach { put(box, label(it, 11f, muted)) }
                put(box, button("Disconnect location") { forget(indexed.source) })
            }
            val found = library.discovered()
            put(this, label("Found in your folders · ${found.size}", 18f, bold = true), 26)
            if (found.isNotEmpty()) put(this, label("Import & use makes a private app copy. Your original files stay where they are.", 12f, muted))
            if (found.isEmpty()) put(this, label("No recognized model files found. Try a model subfolder, select individual files, or export the models from Cactus first.", 13f, muted))
            val pages = LinearLayout(activity).apply { orientation = VERTICAL }
            put(this, pages, 0)
            var page = 0
            fun showPage() {
                pages.removeAllViews()
                found.drop(page * 50).take(50).forEach { (model, source) ->
                val access = library.hasAccess(source)
                val previous = library.imported(model)
                val imported = previous != null && ((previous.first == "text" && textModels.any { it.id == previous.second }) || (previous.first == "image" && imageAssets.any { it.id == previous.second }))
                val box = card(model.name, "${model.kind.label}\n${size(model.size)} · ${model.location}", pages)
                put(box, label(if (imported) "Private copy already in RunLocal" else if (access) "Found on this phone · not yet imported" else "Access needed · reconnect this location", 11f, muted))
                val actionable = (access || imported) && model.kind.canImport
                put(box, button(if (actionable && imported) "Use existing copy" else if (actionable) "Import & use" else "Details") {
                    if (actionable) importFound(model)
                    else {
                        val explanation = when (model.kind) {
                            PhoneModelKind.CACTUS_BUNDLE, PhoneModelKind.CACTUS_FILE -> "This is a Cactus-native model. It is visible in your library, but RunLocal currently needs a Cactus-compatible runtime to execute it."
                            PhoneModelKind.CACTUS_ARCHIVE -> "This looks like a Cactus download archive. RunLocal can list it, but cannot directly run or extract this archive."
                            PhoneModelKind.GGUF -> "GGUF is used by several model families. This build cannot run GGUF language models. Image GGUF files can be configured under Models → Add model → Advanced image setup with their matching components."
                            else -> "This file is listed, but its runtime compatibility has not been established. Only supported formats can be imported and run."
                        }
                        AlertDialog.Builder(activity).setTitle(model.name).setMessage(if (access) explanation else "Access to this location is no longer available. Choose the folder or file again to reconnect it.").setPositiveButton("Got it", null).show()
                    }
                })
            }
                if (found.size > 50) {
                    put(pages, label("${page * 50 + 1}–${minOf((page + 1) * 50, found.size)} of ${found.size}", 12f, muted))
                    if (page > 0) put(pages, button("Previous models") { page--; showPage() })
                    if ((page + 1) * 50 < found.size) put(pages, button("More models") { page++; showPage() })
                }
            }
            showPage()

        }
    }
}
