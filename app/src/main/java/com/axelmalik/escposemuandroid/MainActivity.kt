package com.axelmalik.escposemuandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var screenContent: View
    private lateinit var menuButton: TextView
    private lateinit var drawerCloseButton: TextView
    private lateinit var drawerScrim: View
    private lateinit var settingsDrawer: LinearLayout
    private lateinit var ipText: TextView
    private lateinit var listenerStatusText: TextView
    private lateinit var paperContainer: LinearLayout
    private lateinit var receiptScroll: ScrollView
    private lateinit var paperViewport: PaperViewport
    private lateinit var paperSizeButton: TextView
    private lateinit var paperScaleButton: TextView
    private lateinit var printerList: LinearLayout
    private lateinit var printJobsList: LinearLayout

    private var openReceiptRow: FrameLayout? = null
    private val openReceiptTextEvents = mutableListOf<ParserEvent.Text>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val profiles = LinkedHashMap<String, PrinterProfile>()
    private val statuses = LinkedHashMap<String, String>()
    private val printJobs = LinkedHashMap<String, MutableList<PrintJob>>()
    private val activePrintEvents = LinkedHashMap<String, MutableList<ParserEvent>>()
    private val servers = LinkedHashMap<String, PrinterServer>()
    private var selectedProfileId: String? = null
    private var selectedPrintJobId: String? = null
    private var currentIpAddress = "0.0.0.0"
    private var paperWidthPx = PAPER_58_WIDTH_PX
    private var serverGeneration = 0
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applySystemBarInsets()

        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        loadProfiles()
        currentIpAddress = detectIpv4Address()
        wireControls()
        renderAll()
        restartServers()
    }

    override fun onDestroy() {
        serverGeneration += 1
        stopAllServers()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions")
    override fun onBackPressed() {
        if (settingsDrawer.visibility == View.VISIBLE) {
            setDrawerOpen(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun bindViews() {
        screenContent = findViewById(R.id.screenContent)
        menuButton = findViewById(R.id.menuButton)
        drawerCloseButton = findViewById(R.id.drawerCloseButton)
        drawerScrim = findViewById(R.id.drawerScrim)
        settingsDrawer = findViewById(R.id.settingsDrawer)
        ipText = findViewById(R.id.ipText)
        listenerStatusText = findViewById(R.id.listenerStatusText)
        paperContainer = findViewById(R.id.paperContainer)
        receiptScroll = findViewById(R.id.receiptScroll)
        paperViewport = findViewById(R.id.paperViewport)
        paperSizeButton = findViewById(R.id.paperSizeButton)
        paperScaleButton = findViewById(R.id.paperScaleButton)
        printerList = findViewById(R.id.printerList)
        printJobsList = findViewById(R.id.printJobsList)
    }

    private fun applySystemBarInsets() {
        window.statusBarColor = Color.rgb(224, 224, 224)
        window.navigationBarColor = Color.rgb(38, 55, 70)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            screenContent.setPadding(
                screenContent.paddingLeft,
                bars.top,
                screenContent.paddingRight,
                bars.bottom,
            )
            settingsDrawer.setPadding(
                settingsDrawer.paddingLeft,
                bars.top,
                settingsDrawer.paddingRight,
                bars.bottom,
            )
            insets
        }
        window.decorView.requestApplyInsets()
    }

    private fun wireControls() {
        menuButton.setOnClickListener { setDrawerOpen(true) }
        drawerCloseButton.setOnClickListener { setDrawerOpen(false) }
        drawerScrim.setOnClickListener { setDrawerOpen(false) }
        findViewById<TextView>(R.id.clearButton).setOnClickListener {
            clearSelectedReceipt()
            setDrawerOpen(false)
        }
        paperSizeButton.setOnClickListener { togglePaperSize() }
        paperScaleButton.setOnClickListener { togglePaperScale() }
        findViewById<TextView>(R.id.addPrinterButton).setOnClickListener { showAddPrinterDialog() }
    }

    private fun setDrawerOpen(open: Boolean) {
        drawerScrim.visibility = if (open) View.VISIBLE else View.GONE
        settingsDrawer.visibility = if (open) View.VISIBLE else View.GONE
        if (open) settingsDrawer.bringToFront()
    }

    private fun loadProfiles() {
        val stored = PrinterProfileCodec.decode(preferences.getString(PROFILES_KEY, "").orEmpty())
            .distinctBy { it.port }
        val initialProfiles = if (stored.isEmpty()) {
            listOf(PrinterProfile("main", "Main Printer", PORT, true))
        } else {
            stored
        }
        profiles.clear()
        initialProfiles.forEach { profiles[it.id] = it }
        selectedProfileId = profiles.keys.firstOrNull()
        initialProfiles.forEach {
            printJobs[it.id] = mutableListOf()
            activePrintEvents[it.id] = mutableListOf()
        }
        persistProfiles()
    }

    private fun persistProfiles() {
        preferences.edit().putString(PROFILES_KEY, PrinterProfileCodec.encode(profiles.values.toList())).apply()
    }

    private fun renderAll() {
        renderPrinterCards()
        updateStatusCard()
        updatePaperSettings()
        renderPrintJobs()
        renderSelectedReceipt()
    }

    private fun renderPrinterCards() {
        printerList.removeAllViews()
        profiles.values.forEach { profile ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(7), dp(8), dp(7))
                setBackgroundResource(
                    if (profile.id == selectedProfileId) R.drawable.bg_profile_card_selected else R.drawable.bg_profile_card,
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also {
                    it.bottomMargin = dp(8)
                }
                setOnClickListener { selectProfile(profile.id) }
            }

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            details.addView(TextView(this).apply {
                text = profile.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.rgb(35, 52, 61))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            details.addView(TextView(this).apply {
                text = "TCP ${profile.port}  •  ${statusFor(profile).uppercase()}"
                maxLines = 1
                setTextColor(Color.rgb(91, 112, 122))
                textSize = 11f
                typeface = Typeface.MONOSPACE
            })
            card.addView(details)

            val enabledSwitch = Switch(this).apply {
                isChecked = profile.enabled
                text = ""
                contentDescription = "Enable ${profile.name}"
            }
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked != profile.enabled) {
                    profiles[profile.id] = profile.copy(enabled = checked)
                    persistProfiles()
                    restartServers()
                    renderPrinterCards()
                    updateStatusCard()
                }
            }
            card.addView(enabledSwitch)

            card.addView(TextView(this).apply {
                text = "×"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(91, 112, 122))
                contentDescription = "Remove ${profile.name}"
                setPadding(dp(6), 0, 0, 0)
                setOnClickListener { removeProfile(profile.id) }
            })
            printerList.addView(card)
        }
    }

    private fun selectProfile(profileId: String) {
        if (!profiles.containsKey(profileId)) return
        selectedProfileId = profileId
        selectedPrintJobId = printJobs[profileId]?.lastOrNull()?.id
        setDrawerOpen(false)
        renderPrinterCards()
        updateStatusCard()
        renderPrintJobs()
        renderSelectedReceipt()
    }

    private fun updateStatusCard() {
        ipText.text = currentIpAddress
        val selected = profiles[selectedProfileId]
        listenerStatusText.text = if (selected == null) {
            "NO PRINTER PROFILE SELECTED"
        } else {
            "${statusFor(selected)}  •  TCP ${selected.port}"
        }
    }

    private fun statusFor(profile: PrinterProfile): String {
        return if (profile.enabled) statuses[profile.id] ?: "STARTING" else "DISABLED"
    }

    private fun togglePaperSize() {
        paperWidthPx = if (paperWidthPx == PAPER_58_WIDTH_PX) PAPER_80_WIDTH_PX else PAPER_58_WIDTH_PX
        updatePaperSettings()
        scrollToBottom()
    }

    private fun togglePaperScale() {
        paperViewport.setFitToScreen(!paperViewport.isFitToScreen)
        updatePaperSettings()
        scrollToBottom()
    }

    private fun updatePaperSettings() {
        paperViewport.setLogicalWidth(paperWidthPx)
        paperViewport.setDisplayWidthFraction(
            if (paperWidthPx == PAPER_58_WIDTH_PX) PAPER_58_DISPLAY_FRACTION else PAPER_80_DISPLAY_FRACTION,
        )
        paperSizeButton.setText(if (paperWidthPx == PAPER_58_WIDTH_PX) R.string.paper_size_58 else R.string.paper_size_80)
        paperScaleButton.setText(if (paperViewport.isFitToScreen) R.string.fit_to_screen else R.string.scale_100)
    }

    private fun clearSelectedReceipt() {
        selectedProfileId?.let {
            printJobs[it]?.clear()
            activePrintEvents[it]?.clear()
        }
        selectedPrintJobId = null
        resetOpenReceiptRow()
        paperContainer.removeAllViews()
        renderPrintJobs()
    }

    private fun renderSelectedReceipt() {
        resetOpenReceiptRow()
        paperContainer.removeAllViews()
        selectedProfileId?.let { profileId ->
            val selectedJob = printJobs[profileId].orEmpty().firstOrNull { it.id == selectedPrintJobId }
            val events = selectedJob?.events ?: activePrintEvents[profileId].orEmpty()
            events.forEach(::appendEvent)
        }
        scrollToBottom()
    }

    private fun renderPrintJobs() {
        printJobsList.removeAllViews()
        val profileId = selectedProfileId
        if (profileId == null) {
            addJobPlaceholder("No printer selected")
            return
        }

        val jobs = printJobs[profileId].orEmpty()
        val activeEvents = activePrintEvents[profileId].orEmpty()
        if (jobs.isEmpty() && activeEvents.isEmpty()) {
            addJobPlaceholder("No prints yet")
            return
        }

        jobs.forEach { job ->
            addJobCard("PRINT ${job.number}", "READY", job.id == selectedPrintJobId) {
                selectedPrintJobId = job.id
                renderPrintJobs()
                renderSelectedReceipt()
            }
        }
        if (activeEvents.isNotEmpty()) {
            addJobCard("PRINT ${jobs.size + 1}", "RECEIVING", selectedPrintJobId == null) {
                selectedPrintJobId = null
                renderPrintJobs()
                renderSelectedReceipt()
            }
        }
    }

    private fun addJobPlaceholder(label: String) {
        printJobsList.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(91, 112, 122))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = label
        })
    }

    private fun addJobCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
        printJobsList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(104)
            setPadding(dp(10), dp(5), dp(12), dp(5))
            setBackgroundResource(if (selected) R.drawable.bg_job_card_selected else R.drawable.bg_job_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).also {
                it.marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
            addView(TextView(this@MainActivity).apply {
                text = "•"
                setTextColor(if (selected) Color.rgb(45, 135, 157) else Color.rgb(121, 140, 148))
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { details ->
                details.addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(Color.rgb(35, 52, 61))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                })
                details.addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    setTextColor(Color.rgb(91, 112, 122))
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                })
            })
        })
    }

    private fun handleEvents(profileId: String, events: List<ParserEvent>, generation: Int) {
        if (generation != serverGeneration || events.isEmpty()) return
        val active = activePrintEvents.getOrPut(profileId) { mutableListOf() }
        if (profileId == selectedProfileId) {
            if (selectedPrintJobId != null) {
                resetOpenReceiptRow()
                paperContainer.removeAllViews()
            }
            selectedPrintJobId = null
        }
        events.forEach { event ->
            active += event
            if (event is ParserEvent.Cut) finishActivePrint(profileId, generation)
        }
        if (profileId == selectedProfileId) {
            events.forEach(::appendEvent)
            renderPrintJobs()
            scrollToBottom()
        }
    }

    private fun finishActivePrint(profileId: String, generation: Int) {
        if (generation != serverGeneration) return
        val active = activePrintEvents[profileId] ?: return
        if (active.isEmpty()) return
        val jobs = printJobs.getOrPut(profileId) { mutableListOf() }
        val job = PrintJob(UUID.randomUUID().toString(), jobs.size + 1, active.toList())
        jobs += job
        if (jobs.size > MAX_PRINT_JOBS) jobs.removeAt(0)
        active.clear()
        if (profileId == selectedProfileId) selectedPrintJobId = job.id
    }

    private fun handleStatus(profileId: String, status: String, generation: Int) {
        if (generation != serverGeneration) return
        statuses[profileId] = status
        renderPrinterCards()
        updateStatusCard()
    }

    private fun appendEvent(event: ParserEvent) {
        when (event) {
            is ParserEvent.Text -> appendText(event)
            ParserEvent.LineFeed -> appendLineFeed()
            ParserEvent.Cut -> appendCut()
            is ParserEvent.RasterImage -> appendRasterImage(event)
        }
    }

    private fun appendText(event: ParserEvent.Text) {
        if (isSeparator(event.value)) {
            resetOpenReceiptRow()
            paperContainer.addView(DashedLineView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3).also {
                    it.setMargins(0, 5, 0, 5)
                }
                contentDescription = "Separator"
            })
            return
        }

        splitTwoColumnText(event.value)?.let { (label, value) ->
            appendTwoColumnText(label, value, event)
            return
        }

        val row = openReceiptRow ?: FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            paperContainer.addView(this)
            openReceiptRow = this
        }
        openReceiptTextEvents += event
        renderOpenReceiptRow(row)
    }

    private fun appendTwoColumnText(label: String, value: String, event: ParserEvent.Text) {
        resetOpenReceiptRow()
        paperContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

            addView(TextView(this@MainActivity).apply {
                text = label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_PX, RECEIPT_TEXT_SIZE_PX)
                setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.MONOSPACE, if (event.bold) Typeface.BOLD else Typeface.NORMAL)
                includeFontPadding = true
                textScaleX = receiptCharacterScaleX()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                maxLines = 1
                gravity = Gravity.END
                setTextSize(TypedValue.COMPLEX_UNIT_PX, RECEIPT_TEXT_SIZE_PX)
                setTextColor(Color.BLACK)
                typeface = Typeface.create(Typeface.MONOSPACE, if (event.bold) Typeface.BOLD else Typeface.NORMAL)
                includeFontPadding = true
                textScaleX = receiptCharacterScaleX()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        })
    }

    private fun appendLineFeed() {
        resetOpenReceiptRow()
        paperContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        })
    }

    private fun appendCut() {
        resetOpenReceiptRow()
        paperContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(0, 14, 0, 18)
            }
            addView(DashedLineView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
            })
            addView(TextView(this@MainActivity).apply {
                text = "AUTO CUT"
                setTextColor(Color.rgb(120, 130, 134))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 9f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.08f
            })
            contentDescription = "Auto cut"
        })
    }

    private fun appendRasterImage(event: ParserEvent.RasterImage) {
        resetOpenReceiptRow()
        val colors = IntArray(event.pixels.size) { index -> if (event.pixels[index]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(event.widthPx, event.heightPx, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(colors, 0, event.widthPx, 0, 0, event.widthPx, event.heightPx)
        val imageWidth = event.widthPx.coerceAtMost(paperWidthPx)
        val imageHeight = (event.heightPx.toFloat() * imageWidth / event.widthPx).roundToInt().coerceAtLeast(1)
        paperContainer.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                imageWidth,
                imageHeight,
            ).also {
                it.gravity = when (event.alignment) {
                    TextAlignment.LEFT -> Gravity.START
                    TextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
                    TextAlignment.RIGHT -> Gravity.END
                }
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            contentDescription = "Raster receipt image"
        })
    }

    private fun isSeparator(value: String): Boolean {
        val compact = value.filterNot(Char::isWhitespace)
        return compact.length >= MIN_SEPARATOR_LENGTH && compact.all { it == '-' || it == '=' || it == '_' }
    }

    private fun splitTwoColumnText(value: String): Pair<String, String>? {
        val separator = Regex("\\s{2,}").find(value) ?: return null
        val label = value.substring(0, separator.range.first).trimEnd()
        val rightValue = value.substring(separator.range.last + 1).trim()
        if (label.isEmpty() || rightValue.isEmpty() || !rightValue.any(Char::isDigit)) return null
        return label to rightValue
    }

    private fun resetOpenReceiptRow() {
        openReceiptRow = null
        openReceiptTextEvents.clear()
    }

    private fun renderOpenReceiptRow(row: FrameLayout) {
        row.removeAllViews()
        val hasMultiplePositionedSegments = openReceiptTextEvents.count { it.positionPx != null && it.positionPx > 0 } > 0
        if (hasMultiplePositionedSegments) {
            val contentWidth = (paperWidthPx - paperContainer.paddingLeft - paperContainer.paddingRight).coerceAtLeast(1)
            val baseColumnWidth = contentWidth / THREE_COLUMN_COUNT
            openReceiptTextEvents.forEachIndexed { index, event ->
                val textView = createReceiptTextView(event).apply {
                    gravity = alignmentGravity(event.alignment)
                    layoutParams = FrameLayout.LayoutParams(
                        if (index == THREE_COLUMN_COUNT - 1) contentWidth - baseColumnWidth * 2 else baseColumnWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also {
                        it.leftMargin = baseColumnWidth * index
                    }
                }
                row.addView(textView)
            }
        } else {
            openReceiptTextEvents.forEach { event ->
                row.addView(createReceiptTextView(event).apply {
                    gravity = alignmentGravity(event.alignment)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                })
            }
        }
    }

    private fun createReceiptTextView(event: ParserEvent.Text): TextView {
        return TextView(this).apply {
            text = event.value
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_PX, RECEIPT_TEXT_SIZE_PX)
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.MONOSPACE, if (event.bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
            setLineSpacing(0f, 1.15f)
            textScaleX = receiptCharacterScaleX()
        }
    }

    private fun alignmentGravity(alignment: TextAlignment): Int {
        return when (alignment) {
            TextAlignment.LEFT -> Gravity.START
            TextAlignment.CENTER -> Gravity.CENTER
            TextAlignment.RIGHT -> Gravity.END
        }
    }

    private fun receiptCharacterScaleX(): Float {
        val contentWidth = (paperWidthPx - paperContainer.paddingLeft - paperContainer.paddingRight).coerceAtLeast(1)
        val charactersPerLine = if (paperWidthPx == PAPER_58_WIDTH_PX) PAPER_58_CHARACTERS else PAPER_80_CHARACTERS
        val targetCharacterWidth = contentWidth.toFloat() / charactersPerLine
        val measuredCharacterWidth = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, RECEIPT_TEXT_SIZE_PX)
            typeface = Typeface.MONOSPACE
        }.paint.measureText("0")
        return (targetCharacterWidth / measuredCharacterWidth).coerceIn(0.85f, 1.2f)
    }

    private fun showAddPrinterDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Printer name"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val portInput = EditText(this).apply {
            hint = "TCP port"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(nextAvailablePort().toString())
        }
        form.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(portInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(8)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add printer")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: -1
                val error = PrinterProfileValidator.validate(name, port, profiles.values.toList())
                if (error != null) {
                    if (name.isEmpty()) nameInput.error = error else portInput.error = error
                    return@setOnClickListener
                }
                val profile = PrinterProfile("printer-${UUID.randomUUID()}", name, port, true)
                profiles[profile.id] = profile
                printJobs[profile.id] = mutableListOf()
                activePrintEvents[profile.id] = mutableListOf()
                selectedProfileId = profile.id
                selectedPrintJobId = null
                persistProfiles()
                renderAll()
                restartServers()
                setDrawerOpen(false)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun removeProfile(profileId: String) {
        if (profiles.size == 1) {
            Toast.makeText(this, "Keep at least one printer profile", Toast.LENGTH_SHORT).show()
            return
        }
        servers.remove(profileId)?.stop()
        profiles.remove(profileId)
        statuses.remove(profileId)
        printJobs.remove(profileId)
        activePrintEvents.remove(profileId)
        if (selectedProfileId == profileId) selectedProfileId = profiles.keys.firstOrNull()
        selectedPrintJobId = printJobs[selectedProfileId]?.lastOrNull()?.id
        setDrawerOpen(false)
        persistProfiles()
        renderAll()
        restartServers()
    }

    private fun nextAvailablePort(): Int {
        var port = PORT
        while (profiles.values.any { it.port == port } && port < 65_535) port += 1
        return port
    }

    private fun restartServers() {
        serverGeneration += 1
        val generation = serverGeneration
        stopAllServers()
        servers.clear()
        profiles.values.forEach { profile ->
            if (!profile.enabled) {
                statuses[profile.id] = "DISABLED"
                return@forEach
            }
            statuses[profile.id] = "STARTING"
            val server = PrinterServer(
                profile = profile,
                onEvents = { id, events -> mainHandler.post { handleEvents(id, events, generation) } },
                onJobFinished = { id ->
                    mainHandler.post {
                        finishActivePrint(id, generation)
                        if (id == selectedProfileId) {
                            renderPrintJobs()
                            renderSelectedReceipt()
                        }
                    }
                },
                onStatus = { id, status -> mainHandler.post { handleStatus(id, status, generation) } },
            )
            servers[profile.id] = server
            server.start()
        }
        renderPrinterCards()
        updateStatusCard()
    }

    private fun stopAllServers() {
        servers.values.forEach { it.stop() }
    }

    private fun detectIpv4Address(): String {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiIpAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (wifiIpAddress != 0) {
            return listOf(
                wifiIpAddress and 0xFF,
                (wifiIpAddress shr 8) and 0xFF,
                (wifiIpAddress shr 16) and 0xFF,
                (wifiIpAddress shr 24) and 0xFF,
            ).joinToString(".")
        }
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .map(InetAddress::getHostAddress)
                .firstOrNull()
                ?.let { return it }
        }
        return "0.0.0.0"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun scrollToBottom() {
        receiptScroll.post { receiptScroll.smoothScrollTo(0, paperViewport.bottom) }
    }

    private class PrinterServer(
        private val profile: PrinterProfile,
        private val onEvents: (String, List<ParserEvent>) -> Unit,
        private val onJobFinished: (String) -> Unit,
        private val onStatus: (String, String) -> Unit,
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val clients = CopyOnWriteArrayList<Socket>()
        private val clientThreads = CopyOnWriteArrayList<Thread>()

        fun start() {
            acceptThread = Thread({ acceptLoop() }, "escpos-accept-${profile.id}").also { it.start() }
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            clients.forEach { runCatching { it.close() } }
            clientThreads.forEach { it.interrupt() }
            acceptThread?.interrupt()
        }

        private fun acceptLoop() {
            try {
                ServerSocket(profile.port, 50, InetAddress.getByName("0.0.0.0")).use { socket ->
                    serverSocket = socket
                    onStatus(profile.id, "LISTENING")
                    while (!stopped) {
                        val client = socket.accept()
                        clients += client
                        Thread({ readClient(client) }, "escpos-client-${profile.port}").also {
                            clientThreads += it
                            it.start()
                        }
                    }
                }
            } catch (_: IOException) {
                if (!stopped) onStatus(profile.id, "ERROR")
            } finally {
                serverSocket = null
                if (stopped) onStatus(profile.id, "STOPPED")
            }
        }

        private fun readClient(client: Socket) {
            val parser = EscPosParser()
            try {
                client.getInputStream().use { input ->
                    val buffer = ByteArray(4 * 1024)
                    while (!stopped) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) onEvents(profile.id, parser.feed(buffer.copyOf(count)))
                    }
                }
                onEvents(profile.id, parser.finish())
                onJobFinished(profile.id)
            } catch (_: IOException) {
                if (!stopped) {
                    onEvents(profile.id, parser.finish())
                    onJobFinished(profile.id)
                }
            } finally {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private companion object {
        const val PORT = 9100
        const val PAPER_58_WIDTH_PX = 384
        const val PAPER_80_WIDTH_PX = 576
        const val PAPER_58_DISPLAY_FRACTION = 58f / 80f
        const val PAPER_80_DISPLAY_FRACTION = 1f
        const val PAPER_58_CHARACTERS = 32
        const val PAPER_80_CHARACTERS = 48
        const val THREE_COLUMN_COUNT = 3
        const val RECEIPT_TEXT_SIZE_PX = 18f
        const val MIN_SEPARATOR_LENGTH = 8
        const val MAX_PRINT_JOBS = 50
        const val PREFERENCES_NAME = "escpos_emulator"
        const val PROFILES_KEY = "printer_profiles"
    }
}
