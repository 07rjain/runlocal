package app.pocket.local

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

enum class LibraryKind(val title: String, val symbol: String) {
    TEXT("Text", "Aa"), VOICE("Voice", "♫"), IMAGE("Image", "▧")
}

data class LibraryEntry(val key: String, val title: String, val kind: LibraryKind,
                        val detail: String, val bytes: Long)

/** Installed models share one list; setup, discovery and file details open on demand. */
class ModelLibraryView(
    activity: Activity,
    entries: List<LibraryEntry>,
    selectedKey: String,
    filter: String,
    filterChanged: (String) -> Unit,
    use: (LibraryEntry) -> Unit,
    more: (LibraryEntry) -> Unit,
    addModel: () -> Unit
) : LinearLayout(activity) {
    private val ink = 0xff222f27.toInt()
    private val green = 0xff335942.toInt()
    private val muted = 0xff6f7970.toInt()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun background(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    init {
        orientation = VERTICAL
        val filters = LinearLayout(activity).apply { orientation = HORIZONTAL }
        (listOf("All") + LibraryKind.entries.map { it.title }).forEach { title ->
            filters.addView(label(title, 13f, if (filter == title) Color.WHITE else green, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(18), dp(10), dp(18), dp(10))
                background = background(if (filter == title) green else 0xffe9eee0.toInt(), 24)
                isClickable = true; isFocusable = true; isSelected = filter == title
                contentDescription = "$title models"
                setOnClickListener { filterChanged(title) }
            }, LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; addView(filters)
        }, LayoutParams(-1, -2))
        val visible = entries.filter { filter == "All" || it.kind.title == filter }
        addView(label("${visible.size} ${if (visible.size == 1) "model" else "models"} on this phone", 12f, muted), LayoutParams(-1, -2).apply {
            topMargin = dp(20); bottomMargin = dp(4)
        })
        if (visible.isEmpty()) {
            val empty = LinearLayout(activity).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(36), dp(24), dp(36))
                background = background(Color.WHITE)
            }
            empty.addView(label(if (entries.isEmpty()) "Your models live here" else "No ${filter.lowercase()} models yet", 20f, bold = true))
            empty.addView(label("Add a model, then use it in Chat.", 14f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(18))
            })
            empty.addView(label("＋  Add model", 14f, green, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(20), 0, dp(20), 0)
                background = background(0xffe9eee0.toInt()); isClickable = true; isFocusable = true
                setOnClickListener { addModel() }
            })
            addView(empty, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        visible.forEach { entry ->
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL; setPadding(dp(16), dp(16), dp(8), dp(6))
                background = background(Color.WHITE)
            }
            val heading = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            heading.addView(label(entry.kind.symbol, 22f, green, true).apply {
                gravity = Gravity.CENTER; background = background(0xffedf1e8.toInt(), 14)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(44), dp(44)))
            val copy = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(dp(12), 0, 0, 0) }
            copy.addView(label(entry.title, 16f, bold = true).apply { maxLines = 3 })
            copy.addView(label(entry.kind.title + " · " + entry.detail, 12f, muted).apply {
                setPadding(0, dp(5), 0, 0)
            })
            heading.addView(copy, LayoutParams(0, -2, 1f))
            heading.addView(label("⋮", 24f, muted).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); contentDescription = "Manage ${entry.title}"
                isClickable = true; isFocusable = true; setOnClickListener { more(entry) }
            }, LayoutParams(dp(48), dp(48)))
            card.addView(heading, LayoutParams(-1, -2))
            val actions = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(label(if (entry.key == selectedKey || selectedKey.startsWith("${entry.key}:")) "Selected in Chat" else "Installed", 11f, muted), LayoutParams(0, -2, 1f))
            actions.addView(label("Use in Chat  →", 13f, green, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
                isClickable = true; isFocusable = true; contentDescription = "Use ${entry.title} in Chat"
                setOnClickListener { use(entry) }
            }, LayoutParams(-2, -2))
            card.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(card, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }
}
