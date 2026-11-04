package com.example.overlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.example.ui.theme.AppThemes
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.domain.model.AppSettings
import com.example.domain.model.CaptureWarning
import com.example.domain.model.NormalizedRect
import com.example.domain.model.ReaderState
import com.example.domain.model.ServiceStatus
import kotlin.math.abs

/** Estilo visual del panel flotante y del marco de la zona de subtítulos. */
data class OverlayStyle(
    val panelBackgroundColorArgb: Int,
    val panelBorderColorArgb: Int,
    val primaryAccentArgb: Int,
    val secondaryAccentArgb: Int,
    val textPrimaryArgb: Int,
    val textSecondaryArgb: Int,
    val buttonPrimaryBgArgb: Int,
    val buttonSecondaryBgArgb: Int,
    val dangerBgArgb: Int,
    val cornerRadiusDp: Float,
    val strokeWidthDp: Float,
    val dashedRoiGuide: Boolean = true,
    val cutCornersInRoi: Boolean = false,
    /** Color al que "respira" el borde del panel cuando el tema es animado. */
    val pulseColorArgb: Int = 0
)

val SubVoxOverlayStyle = OverlayStyle(
    panelBackgroundColorArgb = Color.argb(238, 14, 16, 32),
    panelBorderColorArgb = Color.argb(150, 157, 140, 255),
    primaryAccentArgb = Color.rgb(157, 140, 255),
    secondaryAccentArgb = Color.rgb(85, 224, 194),
    textPrimaryArgb = Color.rgb(236, 237, 247),
    textSecondaryArgb = Color.rgb(169, 173, 200),
    buttonPrimaryBgArgb = Color.rgb(51, 42, 143),
    buttonSecondaryBgArgb = Color.rgb(30, 35, 66),
    dangerBgArgb = Color.rgb(123, 37, 64),
    cornerRadiusDp = 20f,
    strokeWidthDp = 1.5f
)

/**
 * Controla las ventanas de superposición (TYPE_APPLICATION_OVERLAY) sobre cualquier navegador o app:
 * 1. Panel flotante arrastrable, minimizable a burbuja compacta y ocultable sin detener el servicio,
 *    con botones directos para: Pausar/Reanudar, Repetir (🔁) y Anterior (⏮), Región ROI, Ajustes,
 *    Volumen (🔉 − / 🔊 +), Sincronía rápida (⏱ − / ⏱ +) y Modo Silenciar Opening/Ending (🎵 OP/ED).
 * 2. Editor interactivo de Región ROI a pantalla completa cuando el usuario pulsa "ROI".
 * 3. Usa la paleta de SubVox. Las ventanas llevan FLAG_SECURE: así NO salen en la captura de pantalla
 *    y el OCR nunca lee los textos del propio panel (antes podía leerse a sí mismo).
 */
class OverlayController(
    private val context: Context,
    private val onTogglePauseResume: () -> Unit,
    private val onSaveManualRoi: (NormalizedRect) -> Unit,
    private val onStopServiceRequested: () -> Unit,
    private val onAdjustVolume: (Boolean) -> Unit = {},
    private val onRepeatLastSubtitle: () -> Unit = {},
    private val onRepeatPreviousSubtitle: () -> Unit = {},
    private val onAdjustSyncOffset: (Int) -> Unit = {},
    private val onToggleMuteOpeningEnding: () -> Unit = {},
    private val onToggleExpressiveVoicePrimary: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var controlWindowView: View? = null
    private var controlLayoutParams: WindowManager.LayoutParams? = null

    private var statusBadgeText: TextView? = null
    private var subtitlePreviewText: TextView? = null
    private var pauseResumeButton: TextView? = null
    private var repeatLastButton: TextView? = null
    private var repeatPrevButton: TextView? = null
    private var roiButton: TextView? = null
    private var settingsButton: TextView? = null
    private var volDownButton: TextView? = null
    private var volUpButton: TextView? = null
    private var syncMinusButton: TextView? = null
    private var syncPlusButton: TextView? = null
    private var muteOpEdButton: TextView? = null
    private var expressivePrimaryButton: TextView? = null
    private var minButton: TextView? = null
    private var hideButton: TextView? = null
    private var closeButton: TextView? = null
    private var expandedActionsRow: LinearLayout? = null
    private var secondaryToolsRow: LinearLayout? = null
    private var minimizedBubbleView: TextView? = null
    private var expandedContainerView: LinearLayout? = null

    private var roiOverlayView: RoiSelectionOverlayView? = null
    private var isRoiOverlayInteractive: Boolean = false
    private var overlayStyleApplied = false
    private var appliedThemeId: String? = null
    private var styleAnimator: ValueAnimator? = null

    private var savedWindowX = (16 * density).toInt()
    private var savedWindowY = (96 * density).toInt()

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun syncWithState(state: ReaderState, settings: AppSettings) {
        if (!canDrawOverlays() || state.serviceStatus == ServiceStatus.INACTIVE) {
            removeAllOverlays()
            return
        }

        val themeId = settings.selectedThemeId
        val themeStyle = AppThemes.overlayStyleFor(themeId)

        if (state.isOverlayHidden || !settings.showOverlayControls || state.isEditingRoiOnOverlay) {
            removeControlOverlay()
        } else {
            ensureControlOverlayShown()
            if (!overlayStyleApplied || appliedThemeId != themeId) {
                applyOverlayThemeStyle(themeStyle, themeId)
                overlayStyleApplied = true
                appliedThemeId = themeId
            }
            bindControlOverlayState(state, settings, themeStyle)
        }

        if (state.isEditingRoiOnOverlay) {
            showRoiOverlay(roi = state.activeRoi, interactive = true, style = themeStyle)
        } else if (settings.showRoiBoxOnOverlay) {
            showRoiOverlay(roi = state.activeRoi, interactive = false, style = themeStyle)
        } else {
            removeRoiOverlay()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureControlOverlayShown() {
        if (controlWindowView != null) return
        overlayStyleApplied = false

        val rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bubble = TextView(context).apply {
            text = "SubVox"
            setTextColor(Color.rgb(157, 140, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(
                fillColor = Color.argb(232, 15, 23, 42),
                strokeColor = Color.rgb(157, 140, 255),
                radiusDp = 22f
            )
            visibility = View.GONE
            setOnClickListener {
                com.example.service.ReaderStateHolder.setOverlayMinimized(false)
            }
        }
        minimizedBubbleView = bubble

        val expandedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = roundedBg(
                fillColor = Color.argb(236, 15, 23, 42),
                strokeColor = Color.argb(180, 157, 140, 255),
                radiusDp = 16f
            )
        }
        expandedContainerView = expandedCard

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusTv = TextView(context).apply {
            text = "● ESCUCHANDO"
            setTextColor(Color.rgb(52, 211, 153))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadgeText = statusTv

        val minBtn = createChipButton("—", Color.rgb(30, 41, 59), Color.WHITE) {
            com.example.service.ReaderStateHolder.setOverlayMinimized(true)
        }
        minButton = minBtn

        val hideBtn = createChipButton("Ocultar", Color.rgb(30, 41, 59), Color.rgb(148, 163, 184)) {
            com.example.service.ReaderStateHolder.setOverlayHidden(true)
        }
        hideButton = hideBtn

        val closeBtn = createChipButton("✕", Color.rgb(136, 19, 55), Color.WHITE) {
            onStopServiceRequested()
        }
        closeButton = closeBtn

        headerRow.addView(statusTv)
        headerRow.addView(minBtn)
        headerRow.addView(spacer(4))
        headerRow.addView(hideBtn)
        headerRow.addView(spacer(4))
        headerRow.addView(closeBtn)

        val subPreview = TextView(context).apply {
            text = "Esperando subtítulos…"
            setTextColor(Color.rgb(241, 245, 249))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(6))
        }
        subtitlePreviewText = subPreview

        // Fila 1 de acciones principales: Pausar | 🔁 | ⏮ | ROI | Ajustes | 🔉− | 🔊+
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        expandedActionsRow = actionsRow

        val pauseBtn = createChipButton("⏸", Color.rgb(12, 74, 110), Color.rgb(224, 242, 254)) {
            onTogglePauseResume()
        }
        pauseResumeButton = pauseBtn

        val repLastBtn = createChipButton("🔁", Color.rgb(12, 74, 110), Color.rgb(157, 140, 255)) {
            onRepeatLastSubtitle()
        }
        repeatLastButton = repLastBtn

        val repPrevBtn = createChipButton("⏮", Color.rgb(30, 41, 59), Color.rgb(224, 242, 254)) {
            onRepeatPreviousSubtitle()
        }
        repeatPrevButton = repPrevBtn

        val roiBtn = createChipButton("📐 ROI", Color.rgb(30, 41, 59), Color.rgb(157, 140, 255)) {
            com.example.service.ReaderStateHolder.setEditingRoiOnOverlay(true)
        }
        roiButton = roiBtn

        val settingsBtn = createChipButton("⚙", Color.rgb(30, 41, 59), Color.rgb(241, 245, 249)) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            runCatching { context.startActivity(openIntent) }
        }
        settingsButton = settingsBtn

        val volDownBtn = createChipButton("🔉−", Color.rgb(30, 41, 59), Color.rgb(224, 242, 254)) {
            onAdjustVolume(false)
        }
        volDownButton = volDownBtn

        val volUpBtn = createChipButton("🔊+", Color.rgb(12, 74, 110), Color.rgb(157, 140, 255)) {
            onAdjustVolume(true)
        }
        volUpButton = volUpBtn

        actionsRow.addView(pauseBtn)
        actionsRow.addView(spacer(4))
        actionsRow.addView(repLastBtn)
        actionsRow.addView(spacer(4))
        actionsRow.addView(repPrevBtn)
        actionsRow.addView(spacer(4))
        actionsRow.addView(roiBtn)
        actionsRow.addView(spacer(4))
        actionsRow.addView(settingsBtn)
        actionsRow.addView(spacer(4))
        actionsRow.addView(volDownBtn)
        actionsRow.addView(spacer(3))
        actionsRow.addView(volUpBtn)

        // Fila 2 (Fase 2): Ajuste rápido de Sincronía (-150ms / +150ms) y Silenciar Opening/Ending
        val toolsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        secondaryToolsRow = toolsRow

        val syncMinusBtn = createChipButton("⏱−150ms", Color.rgb(30, 41, 59), Color.rgb(224, 242, 254)) {
            onAdjustSyncOffset(-150)
        }
        syncMinusButton = syncMinusBtn

        val syncPlusBtn = createChipButton("⏱+150ms", Color.rgb(30, 41, 59), Color.rgb(157, 140, 255)) {
            onAdjustSyncOffset(+150)
        }
        syncPlusButton = syncPlusBtn

        val muteOpBtn = createChipButton("🎵 OP/ED", Color.rgb(30, 41, 59), Color.rgb(241, 245, 249)) {
            onToggleMuteOpeningEnding()
        }
        muteOpEdButton = muteOpBtn

        val expressiveBtn = createChipButton("✨ Voz IA", Color.rgb(12, 74, 110), Color.rgb(157, 140, 255)) {
            onToggleExpressiveVoicePrimary()
        }
        expressivePrimaryButton = expressiveBtn

        toolsRow.addView(syncMinusBtn)
        toolsRow.addView(spacer(4))
        toolsRow.addView(syncPlusBtn)
        toolsRow.addView(spacer(6))
        toolsRow.addView(muteOpBtn)
        toolsRow.addView(spacer(6))
        toolsRow.addView(expressiveBtn)

        expandedCard.addView(headerRow)
        expandedCard.addView(subPreview)
        expandedCard.addView(actionsRow)
        expandedCard.addView(toolsRow)

        rootContainer.addView(bubble)
        rootContainer.addView(expandedCard)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedWindowX
            y = savedWindowY
        }

        attachDragGesture(rootContainer, params)
        attachDragGesture(bubble, params)
        attachDragGesture(headerRow, params)

        runCatching {
            windowManager.addView(rootContainer, params)
            controlWindowView = rootContainer
            controlLayoutParams = params
        }
    }

    private fun applyOverlayThemeStyle(style: OverlayStyle, themeId: String) {
        val chipRadius = style.cornerRadiusDp.coerceIn(0f, 12f)
        val bubbleRadius = if (style.cornerRadiusDp <= 1f) 0f else 22f

        minimizedBubbleView?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(
                fillColor = style.panelBackgroundColorArgb,
                strokeColor = style.primaryAccentArgb,
                radiusDp = bubbleRadius,
                strokeWidthDp = style.strokeWidthDp * 0.6f
            )
        }

        expandedContainerView?.background = roundedBg(
            fillColor = style.panelBackgroundColorArgb,
            strokeColor = style.panelBorderColorArgb,
            radiusDp = style.cornerRadiusDp,
            strokeWidthDp = style.strokeWidthDp * 0.6f,
            cutDp = 12f
        )

        subtitlePreviewText?.setTextColor(style.textPrimaryArgb)

        pauseResumeButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonPrimaryBgArgb, style.primaryAccentArgb, chipRadius)
        }
        repeatLastButton?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(style.buttonPrimaryBgArgb, style.primaryAccentArgb, chipRadius)
        }
        repeatPrevButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        roiButton?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        settingsButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        volDownButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        volUpButton?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(style.buttonPrimaryBgArgb, style.primaryAccentArgb, chipRadius)
        }
        syncMinusButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        syncPlusButton?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        muteOpEdButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        expressivePrimaryButton?.apply {
            setTextColor(style.primaryAccentArgb)
            background = roundedBg(style.buttonPrimaryBgArgb, style.primaryAccentArgb, chipRadius)
        }
        minButton?.apply {
            setTextColor(style.textPrimaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        hideButton?.apply {
            setTextColor(style.textSecondaryArgb)
            background = roundedBg(style.buttonSecondaryBgArgb, style.panelBorderColorArgb, chipRadius)
        }
        closeButton?.apply {
            setTextColor(Color.WHITE)
            background = roundedBg(style.dangerBgArgb, style.secondaryAccentArgb, chipRadius)
        }

        // Tipografía del tema
        val face = if (AppThemes.overlayMonospace(themeId)) {
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        } else {
            Typeface.DEFAULT_BOLD
        }
        listOf(
            statusBadgeText, subtitlePreviewText, pauseResumeButton, repeatLastButton, repeatPrevButton,
            roiButton, settingsButton, volDownButton, volUpButton, syncMinusButton, syncPlusButton,
            muteOpEdButton, expressivePrimaryButton, minButton, hideButton, closeButton, minimizedBubbleView
        ).forEach { it?.typeface = face }

        restartOverlayAnimation(style, AppThemes.overlayAnimated(themeId))
    }

    /** El borde del panel y de la burbuja "respira" entre dos colores del tema. */
    private fun restartOverlayAnimation(style: OverlayStyle, animated: Boolean) {
        styleAnimator?.cancel()
        styleAnimator = null
        if (!animated || style.pulseColorArgb == 0) return

        val strokePx = (style.strokeWidthDp * 0.9f * density).toInt().coerceAtLeast(1)
        styleAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            style.panelBorderColorArgb,
            style.pulseColorArgb
        ).apply {
            duration = 1600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                listOf(expandedContainerView?.background, minimizedBubbleView?.background).forEach { bg ->
                    when (bg) {
                        is ChamferDrawable -> bg.setStrokeColor(color)
                        is GradientDrawable -> bg.setStroke(strokePx, color)
                        else -> Unit
                    }
                }
            }
            start()
        }
    }

    private fun bindControlOverlayState(
        state: ReaderState,
        settings: AppSettings,
        style: OverlayStyle
    ) {
        val bubble = minimizedBubbleView ?: return
        val expanded = expandedContainerView ?: return

        if (state.isOverlayMinimized) {
            bubble.visibility = View.VISIBLE
            expanded.visibility = View.GONE
            bubble.text = when {
                state.serviceStatus == ServiceStatus.PAUSED -> "⏸ SubVox"
                state.isTtsSpeaking -> "🔊 SubVox"
                else -> "● SubVox"
            }
            return
        }

        bubble.visibility = View.GONE
        expanded.visibility = View.VISIBLE

        val volLabel = if (settings.volumeBoostDb > 0) {
            "+${settings.volumeBoostDb}dB"
        } else {
            "${(settings.volume * 100).toInt()}%"
        }

        val syncSuffix = if (settings.syncOffsetMs != 0) {
            val sign = if (settings.syncOffsetMs > 0) "+" else ""
            " • ⏱${sign}${settings.syncOffsetMs}ms"
        } else {
            ""
        }

        val profileSuffix = if (settings.appProfilesEnabled && state.activeProfileName != "Por defecto") {
            " • ${state.activeProfileName}"
        } else {
            ""
        }

        when {
            state.captureWarning == CaptureWarning.BLACK_SCREEN_OR_DRM -> {
                statusBadgeText?.text = "⚠ PANTALLA PROTEGIDA (DRM)"
                statusBadgeText?.setTextColor(Color.rgb(251, 191, 36))
            }
            settings.muteOpeningEndingActive -> {
                statusBadgeText?.text = "🔇 OP/ED SILENCIADO$syncSuffix"
                statusBadgeText?.setTextColor(style.secondaryAccentArgb)
            }
            state.isAutoPausedByStaticVideo -> {
                statusBadgeText?.text = "💤 AUTO-PAUSA (VÍDEO QUIETO)$profileSuffix"
                statusBadgeText?.setTextColor(style.secondaryAccentArgb)
            }
            state.serviceStatus == ServiceStatus.PAUSED -> {
                statusBadgeText?.text = "⏸ PAUSA • Vol $volLabel$syncSuffix$profileSuffix"
                statusBadgeText?.setTextColor(style.secondaryAccentArgb)
            }
            state.isTtsSpeaking -> {
                statusBadgeText?.text = "🔊 LEYENDO ($volLabel)$syncSuffix$profileSuffix"
                statusBadgeText?.setTextColor(style.primaryAccentArgb)
            }
            else -> {
                statusBadgeText?.text = "● Escuchando (${state.subtitlesReadCount}) • $volLabel$syncSuffix$profileSuffix"
                statusBadgeText?.setTextColor(style.primaryAccentArgb)
            }
        }

        val rawSub = state.lastDetectedSubtitle
        when {
            state.captureWarning == CaptureWarning.BLACK_SCREEN_OR_DRM ->
                subtitlePreviewText?.text = "Vídeo con FLAG_SECURE/DRM (imagen negra)"
            settings.muteOpeningEndingActive ->
                subtitlePreviewText?.text = "Silenciado temporal por Opening/Ending activo (toca 🎵 OP/ED para reanudar)"
            rawSub.isNotBlank() -> {
                if (settings.karaokeHighlightEnabled &&
                    state.isTtsSpeaking &&
                    state.karaokeWordStartChar >= 0 &&
                    state.karaokeWordEndChar > state.karaokeWordStartChar &&
                    state.karaokeWordEndChar <= rawSub.length
                ) {
                    val displayStr = "“$rawSub”"
                    val spannable = android.text.SpannableString(displayStr)
                    val spanStart = (state.karaokeWordStartChar + 1).coerceIn(0, displayStr.length)
                    val spanEnd = (state.karaokeWordEndChar + 1).coerceIn(spanStart, displayStr.length)
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(style.primaryAccentArgb),
                        spanStart,
                        spanEnd,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.StyleSpan(Typeface.BOLD),
                        spanStart,
                        spanEnd,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    subtitlePreviewText?.text = spannable
                } else {
                    subtitlePreviewText?.text = "“$rawSub”"
                }
            }
            else ->
                subtitlePreviewText?.text = "Buscando subtítulos…"
        }

        val isPaused = state.serviceStatus == ServiceStatus.PAUSED
        pauseResumeButton?.text = if (isPaused) "▶" else "⏸"
        muteOpEdButton?.text = if (settings.muteOpeningEndingActive) "🔇 OP/ED: ON" else "🎵 OP/ED"
        expressivePrimaryButton?.text = if (settings.naturalVoiceEnabled) {
            "✨ Voz IA: ON"
        } else {
            "✨ Voz IA"
        }
    }

    private fun showRoiOverlay(
        roi: NormalizedRect,
        interactive: Boolean,
        style: OverlayStyle
    ) {
        val existing = roiOverlayView
        if (existing != null && isRoiOverlayInteractive == interactive) {
            existing.applyOverlayStyle(style)
            existing.updateNormalizedRoi(roi)
            return
        }

        removeRoiOverlay()

        val view = RoiSelectionOverlayView(
            context = context,
            initialRoi = roi,
            isInteractiveEditMode = interactive,
            onRoiSaved = { newRect ->
                onSaveManualRoi(newRect)
                com.example.service.ReaderStateHolder.setEditingRoiOnOverlay(false)
            },
            onCancelEdit = {
                com.example.service.ReaderStateHolder.setEditingRoiOnOverlay(false)
            }
        ).apply {
            applyOverlayStyle(style)
        }

        val flags = if (interactive) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        runCatching {
            windowManager.addView(view, params)
            roiOverlayView = view
            isRoiOverlayInteractive = interactive
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragGesture(targetView: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        targetView.setOnTouchListener { v, event ->
            val root = controlWindowView ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                        params.x = (initialX + dx).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceAtLeast(0)
                        savedWindowX = params.x
                        savedWindowY = params.y
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                    }
                    moved
                }
                else -> false
            }
        }
    }

    private fun createChipButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = roundedBg(bgColor, Color.argb(90, 148, 163, 184), 10f)
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBg(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Float,
        strokeWidthDp: Float = 1.2f,
        cutDp: Float = 6f
    ): Drawable {
        val strokePx = (strokeWidthDp * density).coerceAtLeast(1f)
        // Radio 0 = tema "HUD": esquinas cortadas en diagonal en vez de redondeadas
        if (radiusDp <= 0.5f) {
            return ChamferDrawable(fillColor, strokeColor, strokePx, cutDp * density)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
            setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun spacer(widthDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(1))
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun removeControlOverlay() {
        controlWindowView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        controlWindowView = null
        controlLayoutParams = null
        statusBadgeText = null
        subtitlePreviewText = null
        pauseResumeButton = null
        repeatLastButton = null
        repeatPrevButton = null
        roiButton = null
        settingsButton = null
        volDownButton = null
        volUpButton = null
        syncMinusButton = null
        syncPlusButton = null
        muteOpEdButton = null
        expressivePrimaryButton = null
        minButton = null
        hideButton = null
        closeButton = null
        expandedActionsRow = null
        secondaryToolsRow = null
        minimizedBubbleView = null
        expandedContainerView = null
        overlayStyleApplied = false
        appliedThemeId = null
        styleAnimator?.cancel()
        styleAnimator = null
    }

    private fun removeRoiOverlay() {
        roiOverlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        roiOverlayView = null
        isRoiOverlayInteractive = false
    }

    fun removeAllOverlays() {
        removeControlOverlay()
        removeRoiOverlay()
    }
}
