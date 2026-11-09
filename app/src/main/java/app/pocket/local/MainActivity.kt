package app.pocket.local

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.Locale
import java.util.concurrent.Executors
import java.io.File
import org.json.JSONObject

class MainActivity : Activity() {
    private val ink = Color.rgb(34, 47, 39)
    private val green = Color.rgb(51, 89, 66)
    private val muted = Color.rgb(111, 121, 112)
    private val paper = Color.rgb(247, 248, 242)
    private val line = Color.rgb(225, 229, 218)
    private val pale = Color.rgb(233, 238, 224)
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ModelStore
    private lateinit var engine: TextRuntime
    private lateinit var imageStore: ImageStore
    private lateinit var phoneLibrary: PhoneModelLibrary
    private var lastPhoneAutoScan = 0L
    private var imagePane: ModelSetupView? = null
    private var pendingModelRole: String? = null
    private var pendingImageFamily = "sd15"
    private var pendingExport: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val imageUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshBackgroundWork() }
    }
    private val imageHealthCheck = object : Runnable {
        override fun run() {
            recoverImageJob(); recoverModelSetup(); refreshBackgroundWork()
            uiHandler.postDelayed(this, 2000)
        }
    }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var chats: ChatRepository
    private lateinit var speechTurn: SpeechChatTurn
    private lateinit var chat: SavedChat
    private var history: MutableList<ChatLine>
        get() = chat.messages
        set(value) { chat.messages = value }
    private var lastJobToken = ""
    private var automaticImportAttempted = false
    private var screen = "Chat"
    private var mode = "Text"
    private var draft = ""
    private var editor: EditText? = null
    private var busy = false
    private var busyMessage = ""
    private var statusView: TextView? = null
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var listenAction: FrameLayout? = null
    private data class PendingGeneration(val chatId: String, val replyId: String, val startedAt: Long, var progress: GenerationProgress)
    private var pendingGeneration: PendingGeneration? = null
    private var streamingTextView: TextView? = null
    private var streamingTimingView: TextView? = null
    private val generationHeartbeat = object : Runnable {
        override fun run() {
            refreshGenerationViews()
            if (pendingGeneration != null && !destroyed) uiHandler.postDelayed(this, 250)
        }
    }
    private var destroyed = false
    private val updateWorker = Executors.newSingleThreadExecutor()
    private var checkingUpdates = false
    private lateinit var speechModels: SpeechModelStore
    private lateinit var speechClient: SpeechClient
    private var speechStatusView: TextView? = null
    private var speechStatus = ""
    private var speechActive = false
    private var speechState = ""
    private var speechOwner = ""
    private var speechLaunch = 0
    private var sendControl: FrameLayout? = null
    private var libraryStatus: TextView? = null
    private var modelFilter = "All"
    private var modelSheet: android.app.Dialog? = null
    private var renderedChatId = ""
    private var speechDownloading = false
    private val speechDownloadCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val speechSetupWorker = Executors.newSingleThreadExecutor()

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API 31–32 use a signature permission instead.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(this)
        imageStore = ImageStore(this)
        phoneLibrary = PhoneModelLibrary(this)
        speechModels = SpeechModelStore(this)
        speechClient = SpeechClient(this) { state, message ->
            if (!destroyed) {
                speechState = state
                speechActive = state in listOf("generating", "playing")
                updateSpeechStatus(message)
                saveDraft()
                val changed = speechTurn.event(state, message)
                if (changed) { reloadChat(); render() } else refreshSendControl()
                if (state == "error") notice("Speech could not run", message)
            }
        }
        engine = LocalEngine(cacheDir.absolutePath, LiteRtTextSessionFactory(cacheDir.absolutePath) {
            ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
        })
        pendingModelRole = savedInstanceState?.getString("pendingModelRole")
        pendingImageFamily = savedInstanceState?.getString("pendingImageFamily") ?: "sd15"
        pendingExport = savedInstanceState?.getString("pendingExport")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(imageUpdates, IntentFilter(ImageGenerationService.UPDATE), RECEIVER_NOT_EXPORTED)
        else registerReceiver(imageUpdates, IntentFilter(ImageGenerationService.UPDATE), "$packageName.INTERNAL_UPDATES", null)
        chats = ChatRepository(File(filesDir, "conversations"))
        speechTurn = SpeechChatTurn(chats)
        if (chats.all().isEmpty()) {
            val migrated = chats.create()
            migrated.messages = store.loadHistory()
            migrated.title = migrated.messages.firstOrNull { it.role == "user" }?.text?.take(50) ?: "New chat"
            chats.save(migrated)
        }
        // A text inference belongs to the old Activity and cannot survive its process.
        chats.all().forEach { saved ->
            var changed = false
            saved.messages = saved.messages.map { message ->
                if (message.state == "pending" && imageStore.job().optString("replyId") != message.id) {
                    changed = true; message.copy(text = "Generation was interrupted. Send your message again to retry.", state = "error")
                } else message
            }.toMutableList()
            if (changed) chats.save(saved)
        }
        chat = chats.selected() ?: chats.all().first()
        screen = savedInstanceState?.getString("screen")?.takeIf { it == "Models" } ?: "Chat"
        mode = modeForChat()
        modelFilter = savedInstanceState?.getString("modelFilter") ?: "All"
        draft = savedInstanceState?.getString("draft") ?: chat.draft
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = column().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        autoSelectModel()
        render()
        uiHandler.post { if (!destroyed) scanPhoneModels() }
        val updatePrefs = getSharedPreferences("app-updates", MODE_PRIVATE)
        if (UpdateNotifications(this).enabled && !updatePrefs.getBoolean("permissionAsked", false)) {
            updatePrefs.edit().putBoolean("permissionAsked", true).apply()
            requestUpdateNotifications()
        }
        tts = TextToSpeech(this) { result ->
            speechReady = result == TextToSpeech.SUCCESS
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, radius: Int = 20, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun space(parent: LinearLayout, height: Int = 16) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun add(parent: LinearLayout, view: View, height: Int = -2, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { topMargin = dp(top) })
    }
    private fun clickable(view: View, color: Int = Color.WHITE, radius: Int = 20, action: () -> Unit) {
        view.background = RippleDrawable(ColorStateList.valueOf(0x18345942), shape(color, radius), null)
        view.isClickable = true; view.isFocusable = true
        view.setOnClickListener { action() }
    }
    private fun button(label: String, primary: Boolean = true, action: () -> Unit): TextView = text(label, 14f, if (primary) Color.WHITE else green, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(50); setPadding(dp(14), dp(12), dp(14), dp(12))
        clickable(this, if (primary) green else pale, 15, action)
    }
    private fun label(value: String) = text(value.uppercase(Locale.ROOT), 10f, muted, true).apply { letterSpacing = .14f }
    private fun iconButton(kind: String, description: String, primary: Boolean = false, action: () -> Unit) = FrameLayout(this).apply {
        contentDescription = description; minimumWidth = dp(48); minimumHeight = dp(48)
        addView(Symbol(kind, if (primary) Color.WHITE else green), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        clickable(this, if (primary) green else Color.TRANSPARENT, 16, action)
    }
    private fun modeForChat() = when {
        chat.model.startsWith("image:") -> "Image"
        chat.model.startsWith("speech:") -> "Speech"
        else -> "Text"
    }
    private fun saveDraft() {
        editor?.let { draft = it.text.toString() }
        if (::chat.isInitialized) { chat.draft = draft; saveChat() }
    }
    private fun navigate(destination: String) {
        saveDraft(); screen = if (destination == "Models") "Models" else "Chat"; render()
    }
    private fun render() {
        autoSelectModel()
        val previousScroll = if (::body.isInitialized) body.parent as? ScrollView else null
        val sameChat = screen == "Chat" && renderedChatId == chat.id
        val previousY = previousScroll?.scrollY ?: 0
        val followBottom = !sameChat || previousScroll == null || body.height - (previousY + previousScroll.height) < dp(48)
        val restoreFocus = sameChat && editor?.hasFocus() == true
        val cursor = editor?.selectionStart ?: 0
        renderedChatId = if (screen == "Chat") chat.id else ""
        root.removeAllViews(); editor = null; statusView = null; listenAction = null
        speechStatusView = null; sendControl = null; libraryStatus = null
        streamingTextView = null; streamingTimingView = null
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = column().apply { setPadding(dp(20), dp(16), dp(20), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (screen == "Chat") chatPage() else models()
        if (busy) {
            statusView = text(busyMessage, 12f, green).apply { setPadding(dp(24), dp(8), dp(24), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            val loading = row().apply { setPadding(dp(18), 0, dp(12), 0) }
            loading.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(18), dp(18)))
            statusView?.let { loading.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            add(root, loading)
        }
        navigation = row().apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = shape(Color.WHITE, 0) }
        listOf("Chat" to "chat", "Models" to "models").forEach { (title, icon) ->
            val selected = screen == title
            val item = row().apply { gravity = Gravity.CENTER; contentDescription = title; isSelected = selected }
            item.addView(Symbol(icon, if (selected) green else muted), LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(text(title, 13f, if (selected) green else muted, selected), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            clickable(item, if (selected) pale else Color.WHITE, 16) { navigate(title) }
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        add(root, navigation)
        if (screen == "Chat") scroll.post {
            if (followBottom) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0, previousY)
            if (restoreFocus) editor?.let { it.requestFocus(); it.setSelection(cursor.coerceIn(0, it.text.length)) }
        }
    }

    private fun autoSelectModel() {
        if (chat.model.isNotEmpty()) return
        if (store.selected() == null) store.models().minByOrNull { it.size }?.let { store.selectedId = it.id }
        if (store.selected() != null) { mode = "Text"; bindSelectedModel() }
        else {
            val profile = imageStore.configuredProfiles().firstOrNull()
            if (profile != null) { imageStore.selectLocalProfile(profile); mode = "Image"; bindSelectedModel() }
            else SpeechModelCatalog.models.firstOrNull { speechModels.installed(it) }?.let {
                speechModels.selectedId = it.id; mode = "Speech"; bindSelectedModel()
            }
        }
    }

    private fun bindSelectedModel() {
        val key: String
        val title: String
        if (mode == "Speech") {
            val model = selectedSpeechModel() ?: return
            val voice = model.voices.first { it.id == speechModels.voice(model) }
            key = speechChatKey(model, voice); title = "${model.title} · ${voice.name}"
        } else if (mode == "Image" && imageStore.family in imageStore.configuredProfiles()) {
            key = "image:${imageStore.family}"; title = imageStore.profileLabel(imageStore.family).substringAfter('/')
        } else {
            val model = store.selected() ?: return
            key = "text:${model.id}"; title = model.name
        }
        val assets = if (key.startsWith("image:")) (imageStore.roles() + ModelRole.LORA).mapNotNull { role -> imageStore.assigned(role)?.let { role.name to it.id } }.toMap() else emptyMap()
        if (chat.model == key && chat.imageAssets == assets) return
        if (history.isNotEmpty() && chat.model.isNotEmpty()) { chat = chats.create(); draft = "" }
        chat.model = key; chat.modelLabel = title; chat.imageAssets = assets
        chat.trigger = if (key.startsWith("image:")) imageStore.activeTrigger else ""
        saveChat()
    }

    private fun newChat() {
        saveDraft()
        val previous = chat
        chat = chats.create(previous.model, previous.modelLabel).apply { imageAssets = previous.imageAssets; trigger = previous.trigger }
        draft = ""; saveChat(); screen = "Chat"; render()
    }

    private fun reloadChat() { chats.get(chat.id)?.let { saved -> chat = saved; draft = saved.draft } }

    private fun appendTurn(prompt: String, chatId: String = chat.id): String {
        val target = if (chatId == chat.id) chat else chats.get(chatId) ?: error("Conversation unavailable")
        val turn = java.util.UUID.randomUUID().toString()
        val reply = ChatLine("assistant", "Working on your phone…", state = "pending", turn = turn)
        target.messages.add(ChatLine("user", prompt, turn = turn)); target.messages.add(reply)
        if (target.messages.size == 2) target.title = prompt.replace('\n', ' ').take(50)
        val pendingDraft = if (chatId == chat.id) editor?.text?.toString() ?: draft else target.draft
        target.draft = if (pendingDraft.trim() == prompt.trim()) "" else pendingDraft
        target.updatedAt = System.currentTimeMillis(); chats.save(target)
        if (chatId == chat.id) { draft = target.draft; editor?.setText(draft) }
        return reply.id
    }

    private fun libraryEntries(): List<LibraryEntry> = buildList {
        store.models().forEach { model ->
            add(LibraryEntry("text:${model.id}", model.name, LibraryKind.TEXT, "${size(model.size)} · LiteRT-LM", model.size))
        }
        SpeechModelCatalog.models.filter { speechModels.installed(it) }.forEach { spec ->
            add(LibraryEntry("speech:${spec.id}", spec.title, LibraryKind.VOICE,
                "${spec.voices.joinToString { it.name }} · ${size(spec.downloadBytes)}", spec.downloadBytes))
        }
        imageStore.configuredProfiles().forEach { profile ->
            val files = (imageStore.roles(profile) + ModelRole.LORA).mapNotNull { imageStore.assigned(it, profile) }.distinctBy { it.id }
            val bytes = files.sumOf { it.file.length() }
            add(LibraryEntry("image:$profile", imageStore.profileLabel(profile).substringAfter('/'), LibraryKind.IMAGE,
                "${size(bytes)} · ${files.size} files", bytes))
        }
    }

    private fun useLibraryEntry(entry: LibraryEntry) {
        saveDraft()
        when (entry.kind) {
            LibraryKind.TEXT -> {
                store.selectedId = entry.key.removePrefix("text:"); mode = "Text"; bindSelectedModel(); navigate("Chat")
            }
            LibraryKind.IMAGE -> runCatching {
                imageStore.selectLocalProfile(entry.key.removePrefix("image:")); mode = "Image"; bindSelectedModel(); navigate("Chat")
            }.onFailure { notice("Could not select model", it.message.orEmpty()) }
            LibraryKind.VOICE -> chooseSpeechVoice(SpeechModelCatalog.find(entry.key.removePrefix("speech:")))
        }
    }

    private fun chooseSpeechVoice(spec: SpeechModelSpec) {
        AlertDialog.Builder(this).setTitle("Choose a voice")
            .setItems(spec.voices.map { "${it.name} · ${it.language}" }.toTypedArray()) { _, index ->
                selectSpeechModel(spec, spec.voices[index])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun selectSpeechModel(spec: SpeechModelSpec, voice: SpeechVoice) {
        saveDraft(); stopSpeech()
        speechModels.selectedId = spec.id; speechModels.selectVoice(spec, voice.id)
        mode = "Speech"; bindSelectedModel(); navigate("Chat")
    }

    private fun showModelPicker() {
        saveDraft()
        showPanel("Choose a model") { panel, dialog ->
            val entries = libraryEntries()
            if (entries.isEmpty()) add(panel, text("Add your first model to get started.", 14f, muted), top = 8)
            LibraryKind.entries.forEach { kind ->
                val models = entries.filter { it.kind == kind }
                if (models.isNotEmpty()) add(panel, label(if (kind == LibraryKind.TEXT) "Text chat" else if (kind == LibraryKind.VOICE) "Text to speech" else "Image generation"), top = 18)
                models.forEach { entry ->
                    if (kind == LibraryKind.VOICE) {
                        val spec = SpeechModelCatalog.find(entry.key.removePrefix("speech:"))
                        spec.voices.forEach { voice ->
                            add(panel, pickerRow("${spec.title} · ${voice.name}", voice.language, chat.model == speechChatKey(spec, voice)) {
                                dialog.dismiss(); selectSpeechModel(spec, voice)
                            }, top = 8)
                        }
                    } else add(panel, pickerRow(entry.title, entry.detail, chat.model == entry.key) {
                        dialog.dismiss(); useLibraryEntry(entry)
                    }, top = 8)
                }
            }
            add(panel, button("＋  Add a model", false) { dialog.dismiss(); navigate("Models"); showAddModel() }, top = 24)
        }
    }

    private fun pickerRow(title: String, detail: String, selected: Boolean = false, action: () -> Unit): View = row().apply {
        setPadding(dp(14), dp(12), dp(12), dp(12)); minimumHeight = dp(64)
        val copy = column()
        add(copy, text(title, 15f, ink, true)); add(copy, text(detail, 12f, muted), top = 4)
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Symbol(if (selected) "check" else "chevron", green), LinearLayout.LayoutParams(dp(20), dp(20)))
        clickable(this, if (selected) pale else paper, 16, action)
    }

    private fun showPanel(title: String, content: (LinearLayout, android.app.Dialog) -> Unit) {
        modelSheet?.dismiss()
        val dialog = android.app.Dialog(this)
        modelSheet = dialog
        val panel = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)); background = shape(Color.WHITE, 24) }
        val heading = row()
        heading.addView(text(title, 22f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(iconButton("close", "Close $title") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        add(panel, heading)
        val contents = column().apply { setPadding(0, dp(4), 0, dp(8)) }
        content(contents, dialog)
        // Keep the heading and close control visible while setup content scrolls.
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(contents) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.setOnDismissListener { if (modelSheet === dialog) { modelSheet = null; imagePane = null } }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(-1, (resources.displayMetrics.heightPixels * .85).toInt())
        }
    }

    private fun showHistory() {
        saveDraft()
        val dialog = android.app.Dialog(this)
        val panel = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)); setBackgroundColor(paper) }
        add(panel, text("Your chats", 24f, ink, true))
        add(panel, button("＋  New chat") { dialog.dismiss(); newChat() }, top = 18)
        val list = column()
        chats.all().forEach { saved ->
            val item = text("${saved.title}\n${saved.modelLabel.ifBlank { "No model selected" }}", 14f, if (saved.id == chat.id) green else ink).apply {
                setPadding(dp(12), dp(14), dp(12), dp(14)); maxLines = 3
                clickable(this, if (saved.id == chat.id) pale else paper, 12) {
                    chats.select(saved.id); chat = chats.get(saved.id) ?: saved; draft = chat.draft
                    mode = modeForChat()
                    screen = "Chat"; dialog.dismiss(); render()
                }
                setOnLongClickListener {
                    if (saved.messages.any { it.state == "pending" }) toast("Wait for this conversation’s reply before deleting it.")
                    else AlertDialog.Builder(this@MainActivity).setTitle("Delete this chat?").setMessage(saved.title)
                        .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            runCatching { chats.delete(saved.id) }.onFailure { toast(it.message.orEmpty()) }
                            if (saved.id == chat.id) { chat = chats.all().firstOrNull() ?: chats.create(); chats.select(chat.id); draft = chat.draft }
                            dialog.dismiss(); render()
                        }.show()
                    true
                }
            }
            add(list, item, top = 8)
        }
        panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        add(panel, text("Hold a conversation to delete it.", 11f, muted), top = 12)
        add(panel, button("App updates", false) { dialog.dismiss(); showUpdateSettings() }, top = 12)
        add(panel, button("About & device", false) {
            dialog.dismiss()
            val info = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            notice("RunLocal ${BuildConfig.VERSION_NAME}", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n${size(info.totalMem)} RAM · ${size(StatFs(filesDir.path).availableBytes)} free storage\n\nModels download from Hugging Face. Conversations and inference stay on this phone.\n\nText: LiteRT-LM with CPU/GPU choice. Images: SD 1.x; MOSS experimental. Speech: Android offline voices and supported downloaded models, starting with Underdog Woof (non-commercial license). Video and arbitrary speech model formats are not supported.")
        }, top = 12)
        dialog.setContentView(panel)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout((resources.displayMetrics.widthPixels * .88).toInt().coerceAtMost(dp(360)), -1); setGravity(Gravity.START); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.35f) }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .88).toInt().coerceAtMost(dp(360)), -1)
    }

    private fun selectedProcessor(): ProcessingBackend = ProcessingBackend.fromPreference(
        getSharedPreferences("processing", MODE_PRIVATE).getString(chat.model, null))

    private fun showProcessorPicker() {
        if (busy || imageStore.running()) { toast("Wait for the current operation to finish before changing processors."); return }
        val modelKey = chat.model
        val selected = selectedProcessor()
        AlertDialog.Builder(this).setTitle("Text processing")
            .setSingleChoiceItems(arrayOf("CPU only", "Use GPU"), ProcessingBackend.entries.indexOf(selected)) { dialog, index ->
                val backend = ProcessingBackend.entries[index]
                getSharedPreferences("processing", MODE_PRIVATE).edit().putString(modelKey, backend.name).apply()
                saveDraft()
                dialog.dismiss()
                // Dispose on the same worker as decoding. Backend switch always gets a new engine.
                worker.execute { runCatching { engine.close() } }
                render()
                toast("${backend.title} selected for this model. Applies to the next message.")
            }
            .setNeutralButton("About processors") { _, _ ->
                notice("CPU and GPU", "CPU only requests the CPU backend. Use GPU requests local GPU acceleration; support and speed depend on the model and phone’s graphics driver. If GPU fails, select CPU only and retry. RunLocal never silently changes your choice.\n\nThis setting applies to text models. Image generation currently uses the CPU. Both options run locally without cloud inference.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chatPage() {
        val isImage = chat.model.startsWith("image:")
        val isSpeech = chat.model.startsWith("speech:")
        val voiceSelection = speechChatSelection(chat.model)
        val top = row().apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
        top.addView(iconButton("history", "Chat history") { showHistory() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val model = column().apply { setPadding(dp(12), dp(6), dp(8), dp(6)) }
        add(model, text(chat.modelLabel.ifBlank { "Choose a model" } + "  ▾", 16f, ink, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subtitle = when {
            isSpeech -> "Text to speech · ${voiceSelection?.second?.name ?: "Voice unavailable"}"
            isImage -> "Image generation · CPU"
            chat.model.isNotBlank() -> "Text chat · ${selectedProcessor().title}"
            else -> "Text, voice & images · on device"
        }
        add(model, text(subtitle, 11f, muted), top = 2)
        clickable(model, Color.TRANSPARENT, 12) { showModelPicker() }
        model.contentDescription = "${chat.modelLabel.ifBlank { "Choose a model" }}. $subtitle. Change model"
        top.addView(model, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton("plus", "New conversation") { newChat() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, 0)
        if (history.isEmpty()) {
            space(body, 56)
            add(body, FrameLayout(this).apply {
                addView(Symbol(if (isSpeech) "voice" else if (isImage) "image" else "pocket", green).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(36), dp(36)))
            }, height = 36)
            add(body, text(when {
                isSpeech -> "Give your words a voice."
                isImage -> "What will you create?"
                else -> "What’s on your mind?"
            }, 28f, ink, true), top = 24)
            add(body, text(when {
                chat.model.isBlank() -> "Choose a model to chat, speak text or create an image."
                isSpeech -> "Type below and send to hear ${voiceSelection?.second?.name ?: "your selected voice"}."
                isImage -> "Describe an image. Each prompt creates something new."
                else -> "A conversation that stays on your phone."
            }, 15f, muted), top = 12)
            if (chat.model.isBlank()) add(body, button("Choose a model", false) { showModelPicker() }, top = 24)
        }
        history.forEach { message ->
            val user = message.role == "user"
            val box = column().apply {
                setPadding(dp(16), dp(14), dp(16), dp(14)); background = shape(if (user) pale else Color.WHITE, 20)
            }
            if (!user) add(box, label(if (isSpeech) "Voice · ${voiceSelection?.second?.name ?: "Speech"}" else "RunLocal"))
            val running = pendingGeneration?.takeIf { it.chatId == chat.id && it.replyId == message.id }
            if (isSpeech && !user && message.state == "complete") {
                val play = row()
                play.addView(Symbol("play", green), LinearLayout.LayoutParams(dp(24), dp(24)))
                play.addView(text("Play again", 15f, green, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
                play.minimumHeight = dp(48)
                clickable(play, Color.WHITE, 12) { playSpeechReading(message.text) }
                play.contentDescription = "Read this text again with the conversation's voice"
                play.addView(text("Text", 12f, muted).apply {
                    minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
                    setOnClickListener { notice("Spoken text", message.text) }
                    contentDescription = "View spoken text"; setPadding(dp(12), 0, dp(4), 0)
                }, LinearLayout.LayoutParams(-2, -2))
                add(box, play, top = 8)
            } else {
                val content = if (isSpeech && !user && message.state == "pending") "Preparing speech…"
                    else if (running != null) running.progress.text.ifEmpty { running.progress.stage } else message.text
                val messageText = text(content, 15f, if (message.state == "error") 0xff8c4a28.toInt() else ink).apply { setTextIsSelectable(true) }
                add(box, messageText, top = if (user) 0 else 8)
                if (message.state == "pending") {
                    val progressRow = row()
                    progressRow.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(16), dp(16)))
                    val timing = text(if (running != null) generationStatus(running) else "Working locally…", 12f, muted).apply {
                        setPadding(dp(10), dp(6), 0, dp(6)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                    progressRow.addView(timing, LinearLayout.LayoutParams(0, -2, 1f)); add(box, progressRow, top = 8)
                    if (running != null) { streamingTextView = messageText; streamingTimingView = timing }
                }
            }
            val hasListen = !isSpeech && !user && message.state == "complete" && message.image.isBlank()
            message.metrics?.takeUnless { hasListen }?.let { metrics ->
                add(box, text(metrics.caption() + "  ⓘ", 11f, muted).apply {
                    minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
                    contentDescription = "Generation speed and timing. Tap for details."
                    setOnClickListener { notice("Generation performance", metrics.details()) }
                }, top = 4)
            }
            if (message.image.isNotBlank()) {
                val file = File(message.image)
                if (file.isFile) {
                    BitmapFactory.decodeFile(file.path)?.let { bitmap -> add(box, ImageView(this).apply {
                        setImageBitmap(bitmap); adjustViewBounds = true; contentDescription = "Image generated locally"
                    }, top = 10) }
                    add(box, button("Save image", false) {
                        pendingExport = file.path
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            type = "image/png"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, file.name)
                        }, 202)
                    }, top = 8)
                } else add(box, text("The image file is no longer available.", 12f, muted), top = 8)
            } else if (hasListen) {
                val listen = row().apply { minimumHeight = dp(48) }
                val timing = text(message.metrics?.caption().orEmpty(), 11f, muted).apply {
                    minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                    message.metrics?.let { metrics ->
                        contentDescription = "Generation speed and timing. Tap for details."
                        isClickable = true; isFocusable = true
                        setOnClickListener { notice("Generation performance", metrics.details()) }
                    }
                }
                listen.addView(timing, LinearLayout.LayoutParams(0, -2, 1f))
                listen.addView(iconButton("speaker", "Read this reply aloud") { speak(message.text) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                add(box, listen, top = 4)
            }
            body.addView(box, LinearLayout.LayoutParams(if (user) -2 else -1, -2).apply {
                topMargin = dp(14); gravity = if (user) Gravity.END else Gravity.START
                if (user) leftMargin = dp(24)
            })
        }
        val composer = column().apply {
            setPadding(dp(8), dp(4), dp(8), dp(6)); background = shape(Color.WHITE, 24, line)
        }
        val messageInput = input(when {
            isSpeech -> "Type something to say…"
            isImage -> "Describe an image…"
            else -> "Message…"
        }, draft).apply {
            minLines = 1; maxLines = 5; minHeight = dp(48); background = null
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        editor = messageInput; add(composer, messageInput)
        val controls = row()
        controls.addView(iconButton("plus", "Message tools") { showComposerTools(isImage) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val credit = voiceSelection?.first ?: selectedSpeechModel()
        if (credit != null) controls.addView(text(credit.credit + "  ↗", 11f, muted).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; maxLines = 2
            isClickable = true; isFocusable = true; setOnClickListener { openUrl(credit.creditUrl) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        else controls.addView(text("On device", 11f, muted), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        listenAction = iconButton("voice", "Dictate on this device") {
            if (listening) recognizer?.stopListening() else startListening()
        }
        controls.addView(listenAction, LinearLayout.LayoutParams(dp(48), dp(48)))
        sendControl = iconButton("send", "Send message", true) {
            when {
                imageStore.running() -> startService(Intent(this, ImageGenerationService::class.java).setAction(ImageGenerationService.CANCEL))
                speechActive -> stopSpeech()
                isSpeech -> sendSpeechPrompt()
                isImage -> sendImagePrompt()
                else -> generate()
            }
        }
        controls.addView(sendControl, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
        add(composer, controls)
        speechStatusView = text(speechStatus, 11f, green).apply {
            setPadding(dp(12), dp(4), dp(12), dp(6)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (showSpeechStatus()) View.VISIBLE else View.GONE
        }
        speechStatusView?.let { add(composer, it) }
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(8)) })
        refreshSendControl(); refreshListenControl()
    }

    private fun refreshListenControl() {
        (listenAction?.getChildAt(0) as? Symbol)?.showKind(if (listening) "stop" else "voice")
        listenAction?.contentDescription = if (listening) "Stop dictation" else "Dictate on this device"
    }

    private fun refreshSendControl() {
        val imageRunning = imageStore.running()
        val stop = imageRunning || speechActive
        val speech = chat.model.startsWith("speech:")
        (sendControl?.getChildAt(0) as? Symbol)?.showKind(if (stop) "stop" else if (speech) "play" else "send")
        sendControl?.apply {
            contentDescription = if (stop) "Stop ${if (imageRunning) "image generation" else "speech"}" else if (speech) "Speak text" else "Send message"
            isEnabled = stop || (!busy && chat.model.isNotBlank()); alpha = if (isEnabled) 1f else .45f
        }
    }

    private fun showComposerTools(isImage: Boolean) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isImage && chat.model == "image:sd15") {
            actions.add("Attach starting image" to { chooseStartingImage() })
            if (!getSharedPreferences("image-ui", MODE_PRIVATE).getString("input", "").isNullOrEmpty()) actions.add("Remove starting image" to {
                getSharedPreferences("image-ui", MODE_PRIVATE).edit().remove("input").apply(); toast("Starting image removed.")
            })
        }
        if (isImage) actions.add("Image options" to { imageOptions() })
        else if (chat.model.startsWith("text:")) actions.add("CPU / GPU processing" to { showProcessorPicker() })
        if (chat.model.startsWith("speech:")) {
            actions.add("Change voice" to { showModelPicker() })
        } else {
            actions.add("Read-aloud voice" to { showSpeechOptions() })
            actions.add("Read message aloud" to { speak(editor?.text?.toString().orEmpty()) })
        }
        actions.add("Stop speech" to { stopSpeech() })
        AlertDialog.Builder(this).setTitle("Message tools").setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun showUpdateSettings() {
        val updates = UpdateNotifications(this)
        if (!updates.configured) { notice("App updates", "Automatic updates will be available when the official GitHub release repository is configured. This build has no release server configured."); return }
        AlertDialog.Builder(this).setTitle("App updates")
            .setMessage("Check GitHub for stable releases about once a day and show a notification. No chats or models are sent. Android may delay background checks. Updates open the GitHub release page; you approve installation.")
            .setPositiveButton("Check now") { _, _ -> checkAppUpdates(true) }
            .setNeutralButton(if (updates.enabled) "Disable automatic checks" else "Enable automatic checks") { _, _ ->
                updates.enabled = !updates.enabled
                if (updates.enabled) { requestUpdateNotifications(); checkAppUpdates(true) }
            }.setNegativeButton("Close", null).show()
    }

    private fun requestUpdateNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
    }

    private fun checkAppUpdates(manual: Boolean = false) {
        val updates = UpdateNotifications(this)
        if (checkingUpdates || !updates.configured) return
        updates.schedule()
        if (!manual && !updates.enabled) return
        checkingUpdates = true
        updateWorker.execute {
            val result = runCatching { updates.check(manual) }
            runOnUiThread {
                checkingUpdates = false
                if (destroyed) return@runOnUiThread
                result.onSuccess { update ->
                    if (update == null) { if (manual) toast("No newer stable release is available.") }
                    else {
                        val prefs = getSharedPreferences("app-updates", MODE_PRIVATE)
                        if (!manual && prefs.getInt("shownVersion", 0) >= update.versionCode) return@onSuccess
                        prefs.edit().putInt("shownVersion", update.versionCode).apply()
                        runCatching { updates.notify(update) }
                        AlertDialog.Builder(this).setTitle("RunLocal ${update.versionName} is available")
                            .setMessage("Open the GitHub release to download the signed update. Your chats and models stay on this device.")
                            .setPositiveButton("View release") { _, _ -> openUrl(update.releaseUrl) }.setNegativeButton("Later", null).show()
                    }
                }.onFailure { if (manual) notice("Could not check updates", it.message.orEmpty()) }
            }
        }
    }

    private fun chooseStartingImage() {
        saveDraft()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 201)
    }

    private fun imageOptions() {
        val family = chat.model.removePrefix("image:")
        val prefs = getSharedPreferences("image-ui", MODE_PRIVATE)
        val panel = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val resolution = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("256 × 256", "384 × 384", "512 × 512", "768 × 768"))
            setSelection(listOf(256, 384, 512, 768).indexOf(prefs.getInt("resolution_$family", 256)).coerceAtLeast(0))
        }
        add(panel, text("Image size", 12f, muted)); add(panel, resolution)
        val fields = linkedMapOf<String, EditText>()
        listOf("negative" to "Negative prompt", "steps_$family" to "Steps (1–50)", "cfg_$family" to "Guidance (0–15)", "loraScale" to "LoRA strength (0–2)").forEach { (key, title) ->
            val default = when { key.startsWith("steps") -> if (family == "moss") "50" else "20"; key.startsWith("cfg") -> if (family == "moss") "3.0" else "7.0"; key == "loraScale" -> "1.0"; else -> "" }
            add(panel, text(title, 12f, muted), top = 12)
            fields[key] = input(title, prefs.getString(key, default).orEmpty()).apply { minLines = 1; maxLines = 2 }
            add(panel, fields.getValue(key))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Image options").setView(ScrollView(this).apply { addView(panel) }).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                require(fields.getValue("steps_$family").text.toString().toInt() in 1..50) { "Steps must be between 1 and 50." }
                require(fields.getValue("cfg_$family").text.toString().toFloat() in 0f..15f) { "Guidance must be between 0 and 15." }
                require(fields.getValue("loraScale").text.toString().toFloat() in 0f..2f) { "LoRA strength must be between 0 and 2." }
                val edit = prefs.edit().putInt("resolution_$family", listOf(256, 384, 512, 768)[resolution.selectedItemPosition])
                fields.forEach { (key, field) -> edit.putString(key, field.text.toString()) }; edit.apply(); dialog.dismiss()
            }.onFailure { toast(it.message ?: "Check your options.") }
        } }
        dialog.show()
    }

    private fun sendImagePrompt() {
        if (speechActive) { toast("Stop speech before starting image generation."); return }
        val prompt = editor?.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) { toast("Write a prompt first."); return }
        val family = chat.model.removePrefix("image:")
        runCatching {
            val prefs = getSharedPreferences("image-ui", MODE_PRIVATE)
            val input = if (family == "sd15") prefs.getString("input", "").orEmpty() else ""
            val resolution = prefs.getInt("resolution_$family", 256)
            val request = imageStore.request(prompt, prefs.getString("negative", "").orEmpty(), resolution, resolution,
                prefs.getString("steps_$family", if (family == "moss") "50" else "20").orEmpty().toInt(),
                java.security.SecureRandom().nextInt(Int.MAX_VALUE).toLong(), prefs.getString("cfg_$family", if (family == "moss") "3.0" else "7.0").orEmpty().toFloat(),
                prefs.getString("loraScale", "1.0").orEmpty().toFloat(), if (input.isEmpty()) 1f else .65f, input,
                family, chat.imageAssets, chat.trigger)
            request.put("userPrompt", prompt)
            beginImageGeneration(request)
        }.onFailure { notice("Could not start image", it.message ?: "Check your selected model files.") }
    }

    private fun refreshBackgroundWork() {
        if (!::chats.isInitialized || destroyed) return
        val activated = runCatching { imageStore.activateReadySetup() }.getOrDefault(false)
        val job = imageStore.job()
        val token = "${job.optString("replyId")}:${job.optString("state")}:${imageStore.setup().optString("state") }"
        val terminal = job.optString("state") in listOf("complete", "error", "cancelled", "interrupted")
        if (activated || token != lastJobToken) saveDraft()
        var completed = false
        if (token != lastJobToken && terminal && job.optString("state") in listOf("complete", "error", "cancelled", "interrupted") && job.optString("chatId").isNotBlank()) {
            val success = job.optString("state") == "complete" && File(job.optString("output")).isFile
            completed = chats.finish(job.optString("chatId"), job.optString("replyId"),
                if (success) "Generated on this device." else job.optString("message", "Image generation stopped."),
                if (success) job.optString("output") else "", failed = !success)
        }
        if (activated || completed || token != lastJobToken) {
            lastJobToken = token
            reloadChat()
            if (activated) { autoSelectModel(); toast("Model ready. Select it at the top of Chat.") }
            render()
        }
        imagePane?.refreshJob()
        libraryStatus?.apply {
            text = modelSetupSummary(); visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun input(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); textSize = 14f; setTextColor(ink); setHintTextColor(muted)
        gravity = Gravity.TOP; minLines = 3; maxLines = 7
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(14), dp(14), dp(14), dp(14)); background = shape(paper, 14, line)
    }

    private fun generationStatus(pending: PendingGeneration): String {
        val elapsed = (SystemClock.elapsedRealtime() - pending.startedAt).coerceAtLeast(0).toDouble()
        return "${pending.progress.stage}  ·  ${seconds(elapsed)}" +
            (pending.progress.firstTextMs?.let { "  ·  First text ${seconds(it)}" } ?: "")
    }

    private fun refreshGenerationViews() {
        val pending = pendingGeneration ?: return
        val status = generationStatus(pending)
        busyMessage = "${pending.progress.stage} · ${seconds((SystemClock.elapsedRealtime() - pending.startedAt).coerceAtLeast(0).toDouble())}"
        statusView?.text = busyMessage
        if (screen == "Chat" && chat.id == pending.chatId) {
            val scroll = body.parent as? ScrollView
            val wasAtBottom = scroll != null && body.height - (scroll.scrollY + scroll.height) < dp(48)
            val content = pending.progress.text.ifEmpty { pending.progress.stage }
            streamingTextView?.let { if (it.text.toString() != content) it.text = content }
            streamingTimingView?.text = status
            if (wasAtBottom) scroll.post {
                if (screen == "Chat" && chat.id == pending.chatId && body.parent === scroll) scroll.smoothScrollTo(0, body.height)
            }
        }
    }

    private fun generate() {
        if (speechActive) { toast("Stop speech before starting text generation."); return }
        if (busy) return
        if (imageStore.running()) { toast("Wait for image generation to finish, or cancel it first."); return }
        val prompt = editor?.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) { toast("Write a message first."); return }
        if (prompt.length > 4000) { notice("A smaller thought", "Keep prompts under 4,000 characters for the mobile context window."); return }
        val model = store.models().find { "text:${it.id}" == chat.model } ?: run { navigate("Models"); toast("Import and select a text model first."); return }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor?.windowToken, 0)
        val processor = selectedProcessor()
        busy = true; busyMessage = "${processor.title} · loading ${model.name}…"
        val replyId = appendTurn(prompt)
        val chatId = chat.id
        val context = textConversationContext(history)
        pendingGeneration = PendingGeneration(chatId, replyId, SystemClock.elapsedRealtime(), GenerationProgress("Starting…"))
        render()
        uiHandler.removeCallbacks(generationHeartbeat); uiHandler.post(generationHeartbeat)
        worker.execute {
            var metrics: GenerationMetrics? = null
            var lastUiUpdate = 0L
            var lastStage = ""
            val result = runCatching { engine.generate(model, context, processor, chatId) { progress ->
                progress.metrics?.let { metrics = it }
                val now = SystemClock.elapsedRealtime()
                if (progress.stage != lastStage || now - lastUiUpdate >= 80 || progress.metrics != null) {
                    lastUiUpdate = now; lastStage = progress.stage
                    runOnUiThread {
                        val pending = pendingGeneration
                        if (!destroyed && pending?.replyId == replyId && pending.chatId == chatId) {
                            pending.progress = progress; refreshGenerationViews()
                        }
                    }
                }
            } }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                uiHandler.removeCallbacks(generationHeartbeat); pendingGeneration = null
                saveDraft()
                result.onSuccess { response ->
                    chats.finish(chatId, replyId, response, metrics = metrics)
                    reloadChat()
                    toast("Generated locally. Tap the reply’s timing for performance details.")
                }.onFailure { error ->
                    val failure = explainTextFailure(error, processor)
                    chats.finish(chatId, replyId, failure.message, failed = true)
                    reloadChat()
                    notice("Model could not run", "${failure.message}\n\nTechnical details:\n${failure.details}")
                }
                render()
            }
        }
    }

    private fun setupView(): ModelSetupView = ModelSetupView(this, imageStore, busy,
        importAsset = { role, family ->
            modelSheet?.dismiss()
            pendingModelRole = role.name; pendingImageFamily = family
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 200)
        }, remove = { removeImageAsset(it) }, openLink = { openUrl(it) },
        setupAction = { action, url -> startModelSetup(action, url) })

    private fun showAddModel() {
        showPanel("Add a model") { panel, dialog ->
            val setup = setupView()
            imagePane = setup; add(panel, setup)
            add(panel, label("Supported voice models"), top = 28)
            SpeechModelCatalog.models.forEach { spec ->
                add(panel, pickerRow(spec.title, "${spec.voices.joinToString { it.name }} · ${size(spec.downloadBytes)}", speechModels.installed(spec)) {
                    dialog.dismiss(); showSpeechModelDetails(spec)
                }, top = 10)
            }
            add(panel, label("From your phone"), top = 28)
            add(panel, pickerRow("Import model files", "Choose downloaded files") { dialog.dismiss(); chooseLocalModelFiles() }, top = 10)
            add(panel, pickerRow("Connect a model folder", "Find models in a shared folder") { dialog.dismiss(); chooseModelFolder() }, top = 8)
        }
    }

    private fun showSpeechModelDetails(spec: SpeechModelSpec) {
        showPanel(spec.title) { panel, dialog ->
            val installed = speechModels.installed(spec)
            add(panel, text("Text to speech · ${spec.voices.joinToString { it.name }} · English", 14f, muted), top = 8)
            add(panel, text("${size(spec.downloadBytes)} download · runs on this phone", 13f, muted), top = 8)
            add(panel, text(spec.licenseSummary, 13f, ink), top = 20)
            add(panel, text(spec.credit + "  ↗", 13f, green).apply {
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
                setOnClickListener { openUrl(spec.creditUrl) }
            }, top = 8)
            add(panel, button("Model & license  ↗", false) { openUrl(spec.modelUrl) }, top = 8)
            add(panel, button(if (installed) "Use in Chat" else if (speechDownloading) "Downloading…" else if (speechHasPartial(spec)) "Resume download" else "Download model") {
                dialog.dismiss()
                if (installed) chooseSpeechVoice(spec) else downloadSpeechModel(spec)
            }.apply { isEnabled = installed || !speechDownloading }, top = 20)
            if (installed) add(panel, button("Remove model", false) { dialog.dismiss(); removeSpeechModel(spec) }, top = 8)
            else add(panel, text("Downloads can use mobile data. Keep RunLocal open; interrupted downloads can be resumed.", 12f, muted), top = 12)
        }
    }

    private fun removeSpeechModel(spec: SpeechModelSpec) {
        if (speechDownloading) { toast("Pause the speech download before removing a model."); return }
        AlertDialog.Builder(this).setTitle("Remove ${spec.title}?")
            .setMessage("Free ${size(spec.downloadBytes)} by removing the app's copy. Chats and original files are kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                stopSpeech()
                runCatching { speechModels.remove(spec) }.onFailure { notice("Could not remove model", it.message.orEmpty()) }
                saveDraft(); render()
            }.show()
    }

    private fun manageLibraryEntry(entry: LibraryEntry) {
        val actions = if (entry.kind == LibraryKind.VOICE) arrayOf("Model & license", "Use in Chat", "Remove model")
            else arrayOf("Model details", "Use in Chat", if (entry.kind == LibraryKind.IMAGE) "Remove model files…" else "Remove model")
        AlertDialog.Builder(this).setTitle(entry.title).setItems(actions) { _, index ->
            when (index) {
                0 -> when (entry.kind) {
                    LibraryKind.VOICE -> showSpeechModelDetails(SpeechModelCatalog.find(entry.key.removePrefix("speech:")))
                    LibraryKind.TEXT -> notice(entry.title, "Text chat · ${size(entry.bytes)}\nStored privately in RunLocal. Choose CPU or GPU from Chat's message tools.")
                    LibraryKind.IMAGE -> showImageFiles(entry.key.removePrefix("image:"))
                }
                1 -> useLibraryEntry(entry)
                2 -> when (entry.kind) {
                    LibraryKind.TEXT -> store.models().firstOrNull { "text:${it.id}" == entry.key }?.let { deleteModel(it) }
                    LibraryKind.VOICE -> removeSpeechModel(SpeechModelCatalog.find(entry.key.removePrefix("speech:")))
                    LibraryKind.IMAGE -> showImageFiles(entry.key.removePrefix("image:"))
                }
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun showImageFiles(profile: String? = null) {
        val files = if (profile == null) imageStore.assets() else (imageStore.roles(profile) + ModelRole.LORA)
            .mapNotNull { imageStore.assigned(it, profile) }.distinctBy { it.id }
        if (files.isEmpty()) { toast("No image files to manage."); return }
        AlertDialog.Builder(this).setTitle("Image model files")
            .setItems(files.map { "${it.name} · ${size(it.file.length())}\n${it.role.label} · tap to remove" }.toTypedArray()) { _, index -> removeImageAsset(files[index]) }
            .setNeutralButton("Configure components") { _, _ -> showAddModel() }
            .setNegativeButton("Close", null).show()
    }

    private fun showPhoneLibrary() {
        showPanel("Files & folders") { panel, _ ->
            add(panel, PhoneModelLibraryView(this, store, imageStore, phoneLibrary, busy,
                chooseFolder = { modelSheet?.dismiss(); chooseModelFolder() },
                chooseFiles = { modelSheet?.dismiss(); chooseLocalModelFiles() }, refresh = { modelSheet?.dismiss(); scanPhoneModels() },
                importFound = { model -> modelSheet?.dismiss(); importPhoneModel(model) },
                forget = { source -> modelSheet?.dismiss(); disconnectPhoneLocation(source) }))
        }
    }

    private fun modelSetupSummary(): String {
        val setup = imageStore.setup()
        return when {
            speechDownloading -> speechStatus
            speechStatus.startsWith("Speech download") -> speechStatus
            SpeechModelCatalog.models.any { !speechModels.installed(it) && speechHasPartial(it) } -> "Voice download paused · tap to resume"
            setup.optString("state") in listOf("resolving", "downloading", "verifying", "review", "paused", "error") ->
                setup.optString("message").ifBlank { "Model setup · ${setup.optString("state")}" }
            else -> ""
        }
    }

    private fun speechHasPartial(spec: SpeechModelSpec) = spec.files.any {
        speechModels.file(spec, it).isFile || File(speechModels.file(spec, it).path + ".part").isFile
    }

    private fun models() {
        val heading = row()
        heading.addView(text("Models", 30f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("＋  Add model") { showAddModel() }, LinearLayout.LayoutParams(-2, dp(48)))
        add(body, heading)
        add(body, text("Your on-device library", 14f, muted), top = 8)
        val summary = modelSetupSummary()
        libraryStatus = text(summary, 13f, green).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(48)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
            clickable(this, pale, 16) { showAddModel() }
        }
        libraryStatus?.let { add(body, it, top = 16) }
        if (speechDownloading) add(body, button("Pause voice download", false) {
            speechDownloadCancelled.set(true); updateSpeechStatus("Pausing speech download…")
        }, top = 8)
        add(body, ModelLibraryView(this, libraryEntries(), chat.model, modelFilter,
            filterChanged = { modelFilter = it; render() }, use = { useLibraryEntry(it) }, more = { manageLibraryEntry(it) },
            addModel = { showAddModel() }), top = 20)
        add(body, pickerRow("Files & folders", "Imports, saved locations and discovered models") { showPhoneLibrary() }, top = 24)
        if (imageStore.assets().isNotEmpty()) add(body, pickerRow("Image components", "Manage bases, encoders and adapters") { showImageFiles() }, top = 8)
        add(body, pickerRow("Phone speech", "Offline dictation & Android voices") {
            AlertDialog.Builder(this).setTitle("Phone speech")
                .setMessage("Dictation uses your phone's installed on-device recognizer and offline language pack. Android offline voices can read chat replies. Downloaded voice models appear in the library above.")
                .setPositiveButton("Dictate in Chat") { _, _ -> navigate("Chat"); startListening() }
                .setNeutralButton("Read-aloud voice") { _, _ -> showSpeechOptions() }
                .setNegativeButton("Close", null).show()
        }, top = 8)
        val now = System.currentTimeMillis()
        if (!busy && phoneLibrary.index().sources.any { phoneLibrary.hasAccess(it.source) } && now - lastPhoneAutoScan > 30000) {
            lastPhoneAutoScan = now
            uiHandler.post { if (!destroyed && screen == "Models" && !busy) scanPhoneModels() }
        }
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 300) {
            data?.data?.let { scanPhoneModels(listOf(it to true)) }
            return
        }
        if (requestCode == 301) {
            val selected = mutableListOf<Uri>()
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount.coerceAtMost(100)) selected.add(clip.getItemAt(i).uri) }
            data?.data?.let { selected.add(it) }
            scanPhoneModels(selected.distinct().map { it to false }); return
        }
        val uri = data?.data ?: return
        if (requestCode in 200..202) { handleImageDocument(requestCode, uri); return }
    }

    private fun chooseModelFolder() {
        if (busy) return
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }, 300)
        }.onFailure { notice("Folder picker unavailable", "Use Choose model files to select your downloaded files instead.") }
    }
    private fun chooseLocalModelFiles() {
        if (busy) return
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 301)
        }.onFailure { notice("File picker unavailable", "No Android document picker is available on this phone.") }
    }
    private fun scanPhoneModels(additions: List<Pair<Uri, Boolean>> = emptyList()) {
        if (busy) return
        lastPhoneAutoScan = System.currentTimeMillis()
        busy = true; busyMessage = "Finding models in your selected locations…"; saveDraft(); render()
        worker.execute {
            var unavailable = 0
            val result = runCatching {
                phoneLibrary.adoptPersistedLocations()
                additions.forEach { (uri, tree) -> runCatching { phoneLibrary.remember(uri, tree) }.onFailure { unavailable++ } }
                phoneLibrary.refresh()
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                result.onSuccess { toast("${phoneLibrary.discovered().size} model files or bundles found.${if (unavailable > 0) " $unavailable selections could not be opened." else ""}") }
                    .onFailure { notice("Could not scan models", it.message ?: "Choose the folder again or select individual model files.") }
                saveDraft(); autoSelectModel(); render()
                if (!automaticImportAttempted && chat.model.isEmpty() && store.models().isEmpty()) {
                    val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    phoneLibrary.discovered().filter { (model, source) ->
                        phoneLibrary.hasAccess(source) && model.kind in listOf(PhoneModelKind.LITERT, PhoneModelKind.IMAGE_CHECKPOINT) && model.size > 0 && model.size < memory.availMem * .65
                    }.minByOrNull { it.first.size }?.first?.let { automaticImportAttempted = true; importPhoneModel(it) }
                }
            }
        }
    }
    private fun disconnectPhoneLocation(source: PhoneModelSource) {
        if (busy) return
        AlertDialog.Builder(this).setTitle("Disconnect ${source.name}?").setMessage("This removes the location from RunLocal’s library. The source files and any imported copies are kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Disconnect") { _, _ -> phoneLibrary.forget(source); saveDraft(); render() }.show()
    }
    private fun importPhoneModel(model: FoundPhoneModel) {
        if (busy || imageStore.running() || imageStore.setupRunning()) { toast("Wait for the current operation to finish."); return }
        if (!model.kind.canImport) return
        val known = phoneLibrary.imported(model)
        if (known?.first == "text") store.models().find { it.id == known.second }?.let {
            store.selectedId = it.id; mode = "Text"; bindSelectedModel(); navigate("Chat"); return
        }
        if (known?.first == "image") imageStore.assets().find { it.id == known.second }?.let {
            selectImportedImage(it, model.familyHint); return
        }
        busy = true; busyMessage = "Importing ${model.name}…"; saveDraft(); render()
        worker.execute {
            var last = 0L
            val progress: (Long) -> Unit = { bytes ->
                val now = SystemClock.elapsedRealtime()
                if (now - last > 500) { last = now; runOnUiThread { if (!destroyed) statusView?.text = "Importing · ${size(bytes)} copied" } }
            }
            var textModel: LocalModel? = null; var imageModel: ImageAsset? = null
            val result = runCatching {
                val uri = Uri.parse(model.uri)
                if (model.kind == PhoneModelKind.LITERT) {
                    textModel = store.import(uri, progress).also { phoneLibrary.rememberImported(model, "text", it.id) }
                } else {
                    val role = if (model.kind == PhoneModelKind.IMAGE_LORA) ModelRole.LORA else ModelRole.CHECKPOINT
                    imageModel = imageStore.import(uri, role, progress).also { phoneLibrary.rememberImported(model, "image", it.id) }
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                result.onSuccess {
                    val importedText = textModel
                    if (importedText != null) { store.selectedId = importedText.id; mode = "Text"; bindSelectedModel(); navigate("Chat") }
                    else imageModel?.let { selectImportedImage(it, model.familyHint) }
                }.onFailure { notice("Could not import model", it.message ?: "Refresh the folder and try again."); saveDraft(); render() }
            }
        }
    }
    private fun selectImportedImage(asset: ImageAsset, family: String) {
        if (family in listOf("moss", "sd15") && imageStore.family != family) imageStore.family = family
        imageStore.assign(asset.role, asset)
        if (imageStore.missing().isNotEmpty()) navigate("Models") else { mode = "Image"; bindSelectedModel(); navigate("Chat") }
        if (asset.role == ModelRole.LORA && imageStore.missing().isNotEmpty()) toast("Adapter added. Add its matching base model using a Hugging Face link, or choose an existing base in Advanced.")
    }

    private fun handleImageDocument(code: Int, uri: Uri) {
        if (busy || (imageStore.running() && code != 202)) { toast("Wait for the current operation to finish."); return }
        val role = pendingModelRole?.let { runCatching { ModelRole.valueOf(it) }.getOrNull() }
        val family = pendingImageFamily
        val exportPath = pendingExport
        if (code == 200 && role == null) { toast("Choose a component slot again."); return }
        busy = true; busyMessage = if (code == 202) "Saving image…" else "Importing image file…"; saveDraft(); render()
        worker.execute {
            val result = runCatching {
                when (code) {
                    200 -> {
                        var last = 0L
                        val asset = imageStore.import(uri, requireNotNull(role) { "Choose an image component first." }) { bytes ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - last > 500) { last = now; runOnUiThread { if (!destroyed) statusView?.text = "Importing · ${size(bytes)} copied" } }
                        }
                        imageStore.assign(role, asset, family)
                    }
                    201 -> {
                        val directory = File(filesDir, "image-inputs").apply { mkdirs() }
                        val file = File(directory, "${java.util.UUID.randomUUID()}.source")
                        try {
                            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024); var total = 0L
                                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 32L * 1024 * 1024) { "Choose an input image under 32 MB." }; output.write(buffer, 0, count) }
                            } } ?: error("Could not open this image.")
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This file could not be decoded as an image." }
                            val preferences = getSharedPreferences("image-ui", MODE_PRIVATE)
                            val previous = preferences.getString("input", "")
                            preferences.edit().putString("input", file.absolutePath).commit()
                            previous?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                        } catch (e: Exception) { file.delete(); throw e }
                    }
                    202 -> {
                        val source = File(exportPath ?: error("Choose the image to save again."))
                        require(source.canonicalFile.parentFile == imageStore.outputs.canonicalFile && source.isFile) { "This generated image is no longer available." }
                        contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Could not save to that location.")
                    }
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                result.onSuccess { toast(if (code == 202) "Image saved." else "Imported. Model compatibility is checked when loading.") }
                    .onFailure { notice("Could not finish", it.message ?: "This file could not be processed.") }
                saveDraft(); render()
            }
        }
    }

    private fun beginImageGeneration(request: JSONObject) {
        refreshBackgroundWork()
        if (busy || imageStore.running()) { toast("Wait for the current operation to finish."); return }
        if (imageStore.setupRunning()) { toast("Wait for model setup to finish, or pause it first."); return }
        if (isImageServiceRunning()) { toast("The image worker is finishing. Try again in a moment."); return }
        val targetChat = chat.id
        val weights = (imageStore.roles(request.getString("family")) + ModelRole.LORA).sumOf { role -> request.optString(role.name).takeIf { it.isNotEmpty() }?.let { File(it).length() } ?: 0L }
        val estimate = weights * 1.25 + 512L * 1024 * 1024
        busy = true; busyMessage = "Releasing text-model memory…"; saveDraft(); render()
        worker.execute {
            val release = runCatching { engine.close() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                release.onSuccess {
                    val memory = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
                    if (estimate > memory.availMem) {
                        notice("These models may not fit", "The selected files total ${size(weights)}. About ${size(memory.availMem)} of RAM is available after releasing the text model. This conservative check also reserves working memory. Try smaller quantized weights or close other apps. MOSS’s full pipeline can be too large for a phone.")
                        saveDraft(); render(); return@runOnUiThread
                    }
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                    runCatching {
                        request.put("chatId", targetChat).put("replyId", appendTurn(request.optString("userPrompt", request.getString("prompt")), targetChat)).put("modelLabel", chats.get(targetChat)?.modelLabel.orEmpty())
                        imageStore.writeJob(request.put("state", "queued").put("startedAt", System.currentTimeMillis()).put("message", "Starting local image generation…"))
                        startForegroundService(Intent(this, ImageGenerationService::class.java))
                    }.onFailure {
                        imageStore.writeJob(request.put("state", "error").put("message", it.message ?: "Android could not start the image worker."))
                    }
                }.onFailure { notice("Could not release text model", it.message.orEmpty()) }
                saveDraft(); render()
            }
        }
    }
    private fun removeImageAsset(asset: ImageAsset) {
        if (busy || imageStore.running() || imageStore.setupRunning()) return
        AlertDialog.Builder(this).setTitle("Remove ${asset.name}?").setMessage("Only the app's copy will be deleted. Your original download and chats are kept. Any image model using this file will need it added again.")
            .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                runCatching { imageStore.delete(asset) }.onFailure { notice("Could not remove file", it.message.orEmpty()) }
                saveDraft(); render()
            }.show()
    }
    @Suppress("DEPRECATION")
    private fun recoverImageJob() {
        val job = imageStore.job()
        if (job.optString("state") !in listOf("running", "queued") || System.currentTimeMillis() - job.optLong("startedAt") < 15000) return
        if (!isImageServiceRunning()) {
            job.optString("output").takeIf { it.isNotBlank() }?.let { File(it).delete() }
            imageStore.writeJob(job.put("state", "interrupted").put("message", "The image worker stopped before finishing. Android may have reclaimed memory, or the native runtime failed. Try smaller weights or resolution."))
        }
    }
    @Suppress("DEPRECATION")
    private fun isImageServiceRunning() = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ImageGenerationService::class.java.name }

    private fun startModelSetup(action: String, url: String?) {
        if (action == ModelDownloadService.RESOLVE) {
            SpeechModelCatalog.forLink(url.orEmpty())?.let { modelSheet?.dismiss(); downloadSpeechModel(it); return }
        }
        if (busy || imageStore.running()) { toast("Wait for the current operation to finish."); return }
        if (action != ModelDownloadService.PAUSE && imageStore.setupRunning()) return
        if (action == ModelDownloadService.RESOLVE) {
            runCatching { ModelLink.parse(url.orEmpty()) }.onFailure { notice("Check the model link", it.message.orEmpty()); return }
        }
        if (action in listOf(ModelDownloadService.DOWNLOAD, ModelDownloadService.RESOLVE) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        runCatching {
            val intent = Intent(this, ModelDownloadService::class.java).setAction(action)
            if (url != null) intent.putExtra("url", url)
            startForegroundService(intent)
        }.onFailure { notice("Could not start setup", it.message.orEmpty()) }
    }
    @Suppress("DEPRECATION")
    private fun recoverModelSetup() {
        val setup = imageStore.setup()
        if (!imageStore.setupRunning() || System.currentTimeMillis() - setup.optLong("startedAt") < 15000) return
        val running = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ModelDownloadService::class.java.name }
        if (!running) imageStore.writeSetup(setup.put("state", "paused").put("message", "Model setup was interrupted. Tap Resume to continue."))
    }

    private fun deleteModel(model: LocalModel) {
        if (busy) { toast("Wait for the current operation to finish."); return }
        AlertDialog.Builder(this).setTitle("Remove this model?")
            .setMessage("${model.name}\n\nThis deletes the app’s copy and frees ${size(model.size)}. Your original file is kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                busy = true; busyMessage = "Removing model…"; saveDraft(); render()
                worker.execute {
                    val result = runCatching { engine.close(); store.delete(model) }
                    runOnUiThread {
                        if (!destroyed) { busy = false; result.onFailure { notice("Could not remove model", it.message.orEmpty()) }; saveDraft(); render() }
                    }
                }
            }.show()
    }

    private fun saveChat() { runCatching { chats.save(chat) }.onFailure { toast("Could not save conversation. Check available storage.") } }
    private fun size(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun notice(title: String, message: String) { if (!destroyed) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Got it", null).show() }
    private fun openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser is available on this device.") } }

    private fun selectedSpeechModel(): SpeechModelSpec? = SpeechModelCatalog.models.firstOrNull { it.id == speechModels.selectedId }

    private fun updateSpeechStatus(message: String) {
        speechStatus = message
        speechStatusView?.text = message
        speechStatusView?.visibility = if (showSpeechStatus()) View.VISIBLE else View.GONE
        if (speechDownloading) libraryStatus?.text = message
    }

    private fun showSpeechStatus() = speechActive || (speechOwner == chat.id && speechState in listOf("complete", "error"))

    private fun stopSpeech() {
        ++speechLaunch
        tts?.stop(); speechClient.stop(); speechActive = false; speechState = "stopped"
        saveDraft()
        val changed = speechTurn.event("stopped", "Speech stopped before playback. Send the text again to retry.")
        if (changed) { reloadChat(); render() }
        if (!speechDownloading) updateSpeechStatus("Speech stopped.")
        refreshSendControl()
    }

    private fun showSpeechOptions() {
        val choices = SpeechModelCatalog.models.filter { speechModels.installed(it) }
            .flatMap { spec -> spec.voices.map { spec to it } }
        val titles = listOf("Android installed offline voices") + choices.map { (spec, voice) -> "${spec.title} · ${voice.name} (${voice.language})" } + "Manage speech models…"
        val selected = choices.indexOfFirst { (spec, voice) -> spec.id == speechModels.selectedId && speechModels.voice(spec) == voice.id }
        AlertDialog.Builder(this).setTitle("Read aloud voice")
            .setSingleChoiceItems(titles.toTypedArray(), if (speechModels.selectedId == "android") 0 else selected + 1) { dialog, index ->
                dialog.dismiss()
                if (index == titles.lastIndex) { navigate("Models"); return@setSingleChoiceItems }
                stopSpeech()
                if (index == 0) speechModels.selectedId = "android"
                else choices[index - 1].let { (spec, voice) -> speechModels.selectedId = spec.id; speechModels.selectVoice(spec, voice.id) }
                updateSpeechStatus("Read-aloud voice selected.")
                saveDraft(); render()
            }.setNegativeButton("Close", null).show()
    }

    private fun downloadSpeechModel(spec: SpeechModelSpec) {
        if (speechDownloading) { toast("A speech model is already downloading."); return }
        if (speechModels.installed(spec)) { chooseSpeechVoice(spec); return }
        stopSpeech(); speechDownloading = true; speechDownloadCancelled.set(false)
        updateSpeechStatus("Downloading ${spec.title}… Keep RunLocal open.")
        saveDraft(); render()
        speechSetupWorker.execute {
            var lastUpdate = 0L
            val result = runCatching {
                speechModels.install(spec, cancelled = { speechDownloadCancelled.get() || Thread.currentThread().isInterrupted }) { bytes, total, verifying ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 250 || bytes == total) {
                        lastUpdate = now
                        runOnUiThread {
                            if (!destroyed) updateSpeechStatus("${if (verifying) "Verifying" else "Downloading"} ${spec.title} · ${size(bytes)} of ${size(total)}")
                        }
                    }
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                speechDownloading = false
                result.onSuccess {
                    speechModels.selectedId = spec.id
                    updateSpeechStatus("${spec.title} is ready. Choose it in Chat to speak text.")
                }.onFailure { error ->
                    val paused = error is java.util.concurrent.CancellationException || speechDownloadCancelled.get()
                    updateSpeechStatus(if (paused) "Speech download paused. Tap Download to resume." else "Speech download failed: ${error.message}")
                    if (!paused) notice("Could not download speech model", error.message.orEmpty())
                }
                saveDraft(); render()
            }
        }
    }

    private fun speak(value: String) {
        if (value.isBlank()) { toast("Add some text first."); return }
        selectedSpeechModel()?.let { spec ->
            if (!canSpeak(spec, value)) return
            stopSpeech(); launchSpeech(spec, speechModels.voice(spec), value)
            return
        }
        if (speechModels.selectedId != "android") { notice("Speech model unavailable", "Choose a supported voice in Message tools → Read aloud voice."); return }
        stopSpeech()
        if (!speechReady) { toast("Speech is still starting, or no speech engine is installed."); return }
        val voice = tts?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
            ?.firstOrNull() ?: tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == "en" }
        if (voice == null) { notice("An offline voice is needed", "Install an offline voice in your phone’s text-to-speech settings, then try again."); return }
        tts?.voice = voice
        val limit = TextToSpeech.getMaxSpeechInputLength()
        if (value.length > limit) { notice("A shorter reading", "Select text under $limit characters to read aloud."); return }
        if (tts?.speak(value, TextToSpeech.QUEUE_FLUSH, null, "pocket") == TextToSpeech.ERROR) toast("The offline voice could not play this text.")
    }

    private fun canSpeak(spec: SpeechModelSpec, value: String): Boolean {
        if (busy || imageStore.running()) { toast("Wait for model generation to finish before starting speech."); return false }
        if (!speechModels.installed(spec)) {
            notice("Speech model needed", "Add ${spec.title} in Models, or choose another model. This chat keeps its original voice."); return false
        }
        if (value.isBlank() || value.length > SpeechService.MAX_TEXT) {
            notice("A shorter reading", "Enter between 1 and ${SpeechService.MAX_TEXT} characters to speak."); return false
        }
        return true
    }

    private fun sendSpeechPrompt() {
        val selection = speechChatSelection(chat.model) ?: run {
            notice("Voice unavailable", "Choose an installed voice model at the top of Chat."); return
        }
        val prompt = editor?.text?.toString()?.trim().orEmpty()
        if (!canSpeak(selection.first, prompt)) return
        stopSpeech()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor?.windowToken, 0)
        val replyId = appendTurn(prompt)
        speechTurn.begin(chat.id, replyId, prompt)
        launchSpeech(selection.first, selection.second.id, prompt)
        render()
    }

    private fun playSpeechReading(value: String) {
        val selection = speechChatSelection(chat.model) ?: run {
            notice("Voice unavailable", "This chat's voice model is no longer supported."); return
        }
        if (!canSpeak(selection.first, value)) return
        stopSpeech(); launchSpeech(selection.first, selection.second.id, value)
    }

    private fun launchSpeech(spec: SpeechModelSpec, voice: String, value: String) {
        val ticket = ++speechLaunch
        speechOwner = chat.id; speechState = "generating"
        speechActive = true; updateSpeechStatus("Preparing ${spec.title}…"); refreshSendControl()
        // A model selected in Chat may still own native text memory. Release it on
        // its serial worker before opening the voice runtime, with cancellation fencing.
        worker.execute {
            val release = runCatching { engine.close() }
            runOnUiThread {
                if (destroyed || ticket != speechLaunch) return@runOnUiThread
                release.onSuccess { speechClient.speak(spec, voice, value) }.onFailure { error ->
                    speechActive = false
                    val message = "Could not release the text model: ${error.message}"
                    speechState = "error"; updateSpeechStatus(message)
                    saveDraft(); speechTurn.event("error", message); reloadChat(); render()
                    notice("Could not start speech", message)
                }
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) { notice("On-device speech isn’t available", "Your phone has no available on-device speech recognizer. Install an offline recognition service or language pack. This app does not fall back to online recognition."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101); return }
        val targetChat = chat.id
        stopSpeech(); recognizer?.destroy()
        runCatching { recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; refreshListenControl(); toast("Listening on your device…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; refreshListenControl() }
                override fun onError(error: Int) {
                    listening = false
                    refreshListenControl()
                    notice("Could not transcribe", when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized. Try speaking a little closer to the microphone."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected. Try again when you’re ready."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The offline language pack is not available. Install it in your phone’s speech settings."
                        else -> "The on-device recognizer returned error $error. Check microphone permission and offline language availability."
                    })
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    refreshListenControl()
                    val value = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (value.isNotBlank()) {
                        if (chat.id == targetChat) {
                            draft = value; chat.draft = value; editor?.setText(value); saveChat()
                        } else chats.get(targetChat)?.let { target ->
                            target.draft = value
                            runCatching { chats.save(target) }.onFailure { toast("Could not save dictated text.") }
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } }.onFailure { listening = false; refreshListenControl(); notice("Could not start microphone", it.message ?: "The on-device speech service is unavailable.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else if (requestCode == 101) toast("Microphone permission is needed to transcribe speech.")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        saveDraft(); outState.putString("screen", screen); outState.putString("mode", mode)
        outState.putString("draft", draft)
        outState.putString("modelFilter", modelFilter)
        outState.putString("pendingModelRole", pendingModelRole); outState.putString("pendingImageFamily", pendingImageFamily); outState.putString("pendingExport", pendingExport)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); if (pendingGeneration != null) { uiHandler.removeCallbacks(generationHeartbeat); uiHandler.post(generationHeartbeat) }; checkAppUpdates(); uiHandler.removeCallbacks(imageHealthCheck); uiHandler.post(imageHealthCheck) }
    override fun onStop() { uiHandler.removeCallbacks(generationHeartbeat); saveDraft(); uiHandler.removeCallbacks(imageHealthCheck); super.onStop(); recognizer?.cancel(); listening = false; stopSpeech(); speechClient.close(); speechDownloadCancelled.set(true) }
    override fun onDestroy() {
        uiHandler.removeCallbacks(generationHeartbeat)
        destroyed = true; modelSheet?.dismiss(); updateWorker.shutdownNow(); recognizer?.destroy(); tts?.shutdown()
        speechDownloadCancelled.set(true); speechSetupWorker.shutdownNow(); speechClient.close()
        uiHandler.removeCallbacks(imageHealthCheck); unregisterReceiver(imageUpdates)
        worker.execute { engine.close() }; worker.shutdown()
        super.onDestroy()
    }

    private inner class Symbol(private var kind: String, private val tint: Int) : View(this@MainActivity) {
        fun showKind(value: String) { kind = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(c: Canvas) {
            c.save(); c.scale(width / 24f, height / 24f)
            fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); c.drawPath(p, paint) }
            when (kind) {
                "pocket" -> { val p = Path(); p.moveTo(4f, 4f); p.lineTo(20f, 4f); p.lineTo(20f, 13f); p.quadTo(20f, 19f, 12f, 22f); p.quadTo(4f, 19f, 4f, 13f); p.close(); c.drawPath(p, paint); path(8f, 10f, 12f, 14f, 16f, 10f) }
                "chat" -> { c.drawRoundRect(3f, 4f, 21f, 18f, 4f, 4f, paint); path(7f, 18f, 7f, 22f, 12f, 18f); path(8f, 9f, 16f, 9f); path(8f, 13f, 13f, 13f) }
                "voice" -> { c.drawRoundRect(9f, 2f, 15f, 15f, 3f, 3f, paint); val p = Path(); p.addArc(5f, 5f, 19f, 19f, 0f, 180f); c.drawPath(p, paint); path(12f, 19f, 12f, 23f); path(8f, 23f, 16f, 23f) }
                "image" -> { c.drawRoundRect(3f, 3f, 21f, 21f, 4f, 4f, paint); c.drawCircle(9f, 8f, 2f, paint); path(3f, 17f, 9f, 12f, 13f, 16f, 17f, 11f, 21f, 15f) }
                "video" -> { c.drawRoundRect(2f, 5f, 17f, 19f, 3f, 3f, paint); path(17f, 9f, 23f, 6f, 23f, 18f, 17f, 15f) }
                "models" -> { c.drawRoundRect(3f, 4f, 21f, 10f, 2f, 2f, paint); c.drawRoundRect(3f, 14f, 21f, 20f, 2f, 2f, paint); c.drawCircle(7f, 7f, .5f, paint); c.drawCircle(7f, 17f, .5f, paint); path(12f, 7f, 17f, 7f); path(12f, 17f, 17f, 17f) }
                "history" -> { c.drawCircle(12f, 12f, 9f, paint); path(12f, 6f, 12f, 12f, 16f, 14f) }
                "plus" -> { path(12f, 5f, 12f, 19f); path(5f, 12f, 19f, 12f) }
                "close" -> { path(6f, 6f, 18f, 18f); path(18f, 6f, 6f, 18f) }
                "check" -> path(5f, 12f, 10f, 17f, 19f, 7f)
                "chevron" -> path(9f, 5f, 16f, 12f, 9f, 19f)
                "send" -> { path(12f, 20f, 12f, 4f); path(5f, 11f, 12f, 4f, 19f, 11f) }
                "play" -> path(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f)
                "stop" -> c.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
                "speaker" -> { path(3f, 9f, 7f, 9f, 12f, 4f, 12f, 20f, 7f, 15f, 3f, 15f, 3f, 9f); c.drawArc(10f, 5f, 22f, 19f, -60f, 120f, false, paint) }
                "settings" -> { path(3f, 6f, 21f, 6f); path(3f, 12f, 21f, 12f); path(3f, 18f, 21f, 18f); c.drawCircle(8f, 6f, 2f, paint); c.drawCircle(16f, 12f, 2f, paint); c.drawCircle(9f, 18f, 2f, paint) }
                else -> { path(12f, 2f, 15f, 9f, 22f, 12f, 15f, 15f, 12f, 22f, 9f, 15f, 2f, 12f, 9f, 9f, 12f, 2f) }
            }
            c.restore()
        }
    }
}
