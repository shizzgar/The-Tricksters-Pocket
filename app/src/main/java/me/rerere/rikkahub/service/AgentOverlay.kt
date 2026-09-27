package me.rerere.rikkahub.service

import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextUsageLevel
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.findThemeById
import java.text.NumberFormat

internal data class AgentOverlayPalette(
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val tertiary: Int,
    val error: Int,
    val outline: Int,
    val track: Int,
)

/** Same color source as the app, without installing an Activity-dependent Compose theme. */
internal fun agentOverlayPalette(context: Context, settings: Settings): AgentOverlayPalette {
    val dark = when (context.readStringPreference("colorMode", ColorMode.SYSTEM.name)) {
        ColorMode.DARK.name -> true
        ColorMode.LIGHT.name -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId)).getColorScheme(dark)
    return AgentOverlayPalette(
        surface = if (dark && context.readBooleanPreference("amoledDark")) android.graphics.Color.BLACK else colors.surfaceContainer.toArgb(),
        onSurface = colors.onSurface.toArgb(), onSurfaceVariant = colors.onSurfaceVariant.toArgb(),
        primary = colors.primary.toArgb(), tertiary = colors.tertiary.toArgb(), error = colors.error.toArgb(),
        outline = colors.outline.toArgb(), track = colors.surfaceVariant.toArgb(),
    )
}

/** A bounded, touchable window; touches outside its actual card continue to the app underneath. */
object AgentOverlay {
    private const val TAG = "AgentOverlay"
    private const val PREFERENCES = "pocket.agent_overlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var window: OverlayWindow? = null

    fun canShow(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    internal fun update(context: Context, state: AgentOverlayState?, palette: AgentOverlayPalette) {
        val app = context.applicationContext
        mainHandler.post {
            if (state == null || !canShow(app)) {
                hideInternal()
                return@post
            }
            window?.let { it.card.bind(state, palette); return@post }
            try {
                // A visual context tracks the display configuration independently of the Activity.
                val visual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else app
                val created = OverlayWindow(app, visual)
                created.card.bind(state, palette)
                created.show()
                window = created
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to show agent overlay", error)
            }
        }
    }

    fun hide(context: Context) {
        mainHandler.post { hideInternal() }
    }

    private fun hideInternal() {
        val old = window ?: return
        window = null
        old.close()
    }

    private class OverlayWindow(app: Context, private val context: Context) {
        private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val preferences = AgentOverlayPlacementPreferences(app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
        private var placement = preferences.read()
        val card = AgentOverlayCard(context)
        private var attached = false
        private var dragOrigin = OverlayPoint(0, 0)
        private var bounds = OverlayBounds(0, 0, 1, 1)
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT // Physical coordinates also work in RTL.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Own touches are consumed. Outside this small window there is no overlay surface.
            alpha = 1f
        }
        private val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                // Wait for WindowManager to publish the new display geometry.
                card.post { if (attached) arrange() }
            }
            override fun onLowMemory() = Unit
        }

        init {
            card.setCollapsed(placement.collapsed)
            card.onToggle = {
                placement = placement.copy(collapsed = !placement.collapsed)
                card.setCollapsed(placement.collapsed)
                arrange()
                save()
            }
            card.onDragStart = { dragOrigin = OverlayPoint(params.x, params.y) }
            card.onDrag = { delta -> moveTo(dragOrigin.x + delta.x, dragOrigin.y + delta.y) }
            card.onDragEnd = { save() }
            card.onMove = { dx, dy ->
                val step = (48 * context.resources.displayMetrics.density).toInt()
                moveTo(params.x + dx * step, params.y + dy * step)
                save()
            }
            card.setOnApplyWindowInsetsListener { _, insets ->
                card.post { if (attached) arrange() }
                insets
            }
        }

        fun show() {
            arrange()
            wm.addView(card, params)
            attached = true
            context.registerComponentCallbacks(callbacks)
            card.requestApplyInsets()
        }

        fun close() {
            if (!attached) return
            attached = false
            context.unregisterComponentCallbacks(callbacks)
            try {
                wm.removeViewImmediate(card)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to remove agent overlay", error)
            }
        }

        private fun save() {
            preferences.write(placement)
        }

        @Suppress("DEPRECATION")
        private fun safeBounds(): OverlayBounds {
            val margin = (8 * context.resources.displayMetrics.density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = wm.currentWindowMetrics
                val screen = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return OverlayBounds(insets.left + margin, insets.top + margin,
                    screen.width() - insets.right - margin, screen.height() - insets.bottom - margin)
            }
            val size = Point().also { wm.defaultDisplay.getRealSize(it) }
            val insets = card.rootWindowInsets
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets?.displayCutout else null
            return OverlayBounds(
                maxOf(insets?.stableInsetLeft ?: 0, cutout?.safeInsetLeft ?: 0) + margin,
                maxOf(insets?.stableInsetTop ?: 0, cutout?.safeInsetTop ?: 0) + margin,
                size.x - maxOf(insets?.stableInsetRight ?: 0, cutout?.safeInsetRight ?: 0) - margin,
                size.y - maxOf(insets?.stableInsetBottom ?: 0, cutout?.safeInsetBottom ?: 0) - margin,
            )
        }

        private fun arrange() {
            bounds = safeBounds()
            card.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.AT_MOST))
            val point = bounds.position(placement, card.measuredWidth, card.measuredHeight)
            val changed = params.width != card.measuredWidth || params.height != card.measuredHeight ||
                params.x != point.x || params.y != point.y
            params.width = card.measuredWidth
            params.height = card.measuredHeight
            params.x = point.x
            params.y = point.y
            if (attached && changed) updateLayout()
        }

        private fun moveTo(x: Int, y: Int) {
            val point = bounds.clamp(x, y, params.width, params.height)
            placement = bounds.placement(point, params.width, params.height, placement)
            params.x = point.x
            params.y = point.y
            if (attached) updateLayout()
        }

        private fun updateLayout() {
            try {
                wm.updateViewLayout(card, params)
            } catch (error: RuntimeException) {
                // Permission can be revoked while the user is moving the overlay.
                Log.w(TAG, "Unable to move agent overlay", error)
                close()
                if (window === this) window = null
            }
        }
    }
}

internal class AgentOverlayPlacementPreferences(private val preferences: SharedPreferences) {
    fun read() = AgentOverlayPlacement(preferences.getFloat("horizontal", .5f),
        preferences.getFloat("vertical", .08f), preferences.getBoolean("collapsed", false)).normalized()

    fun write(placement: AgentOverlayPlacement) {
        val safe = placement.normalized()
        preferences.edit().putFloat("horizontal", safe.horizontal).putFloat("vertical", safe.vertical)
            .putBoolean("collapsed", safe.collapsed).apply()
    }
}

/** Separate from the overlay permission/window so rendering can also be exercised in UI tests. */
internal class AgentOverlayCard(context: Context) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val ring = ContextRingView(context)
    private val title = label(12f, bold = true)
    private val phase = label(11f)
    private val usage = label(11f)
    private val compactUsage = label(10f, bold = true).apply { gravity = Gravity.CENTER }
    private val ringColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ring, LayoutParams(dp(42), dp(42)))
        addView(compactUsage, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val details = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title)
        addView(phase)
        addView(usage)
    }
    private val collapse = label(26f).apply {
        text = "−"
        gravity = Gravity.CENTER
        tooltipText = context.getString(R.string.pocket_overlay_collapse)
    }
    private val gesture = OverlayDragGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var lastState: AgentOverlayState? = null
    private var lastPalette: AgentOverlayPalette? = null
    var collapsed: Boolean = false
        private set
    var onToggle: () -> Unit = {}
    var onDragStart: () -> Unit = {}
    var onDrag: (OverlayPoint) -> Unit = {}
    var onDragEnd: () -> Unit = {}
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        isFocusable = true
        addView(ringColumn)
        addView(details, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        addView(collapse, LayoutParams(dp(30), dp(48)))
        elevation = dp(6).toFloat()
        setOnClickListener { onToggle() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_CLICK,
                    context.getString(if (collapsed) R.string.pocket_overlay_expand else R.string.pocket_overlay_collapse),
                ))
                moveActions.forEach { (id, label) ->
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(id, context.getString(label)))
                }
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val delta = when (action) {
                    R.id.pocket_overlay_move_left -> -1 to 0
                    R.id.pocket_overlay_move_right -> 1 to 0
                    R.id.pocket_overlay_move_up -> 0 to -1
                    R.id.pocket_overlay_move_down -> 0 to 1
                    else -> null
                }
                if (delta != null) { onMove(delta.first, delta.second); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        })
        updateShape()
    }

    fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        updateShape()
        lastState?.let { state -> lastPalette?.let { bind(state, it) } }
        requestLayout()
    }

    private fun updateShape() {
        setPadding(dp(if (collapsed) 5 else 12), dp(6), dp(if (collapsed) 5 else 6), dp(6))
        gravity = if (collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL
        details.visibility = if (collapsed) GONE else VISIBLE
        collapse.visibility = if (collapsed) GONE else VISIBLE
        compactUsage.visibility = if (collapsed) VISIBLE else GONE
        ring.layoutParams = LayoutParams(dp(if (collapsed) 36 else 42), dp(if (collapsed) 36 else 42))
        minimumHeight = dp(64)
        ViewCompat.setStateDescription(this, context.getString(
            if (collapsed) R.string.pocket_overlay_collapsed else R.string.pocket_overlay_expanded))
    }

    // The complete gesture belongs to this window, including CANCEL/multi-touch. A drag never
    // clicks the button at its end and never leaks an UP event to the application underneath.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.start(event.rawX, event.rawY)
                onDragStart()
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> gesture.move(event.rawX, event.rawY)?.let {
                isPressed = false
                onDrag(it)
            }
            MotionEvent.ACTION_UP -> {
                gesture.move(event.rawX, event.rawY)?.let(onDrag)
                val moved = gesture.dragging
                val clicked = gesture.finish()
                isPressed = false
                if (moved) onDragEnd()
                if (clicked) performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val moved = gesture.dragging
                gesture.finish(cancelled = true)
                isPressed = false
                if (moved) onDragEnd()
            }
        }
        return true
    }

    private val moveActions get() = listOf(
        R.id.pocket_overlay_move_left to R.string.pocket_overlay_move_left,
        R.id.pocket_overlay_move_right to R.string.pocket_overlay_move_right,
        R.id.pocket_overlay_move_up to R.string.pocket_overlay_move_up,
        R.id.pocket_overlay_move_down to R.string.pocket_overlay_move_down,
    )

    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = if (collapsed) (dp(64) * resources.configuration.fontScale.coerceIn(1f, 1.5f)).toInt() else dp(320)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) desired
            else MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) desired
            else MeasureSpec.getSize(heightMeasureSpec)
        val width = minOf(desired, available, if (collapsed) availableHeight else Int.MAX_VALUE).coerceAtLeast(1)
        val height = if (collapsed) MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY) else heightMeasureSpec
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), height)
    }

    fun bind(state: AgentOverlayState, palette: AgentOverlayPalette) {
        lastState = state
        lastPalette = palette
        val session = state.session
        val snapshot = session.context
        val format = NumberFormat.getIntegerInstance()
        val assistant = session.assistantName.ifBlank { context.getString(R.string.app_name) }
        title.text = if (state.activeCount > 1) context.getString(R.string.pocket_overlay_parallel, assistant, state.activeCount) else assistant
        phase.text = session.processingStatus?.takeIf { it.isNotBlank() } ?: context.getString(when (session.phase) {
            AgentOverlayPhase.PREPARING -> R.string.generation_progress_preparing
            AgentOverlayPhase.QUEUED -> R.string.generation_progress_queued
            AgentOverlayPhase.WAITING -> R.string.generation_progress_waiting
            AgentOverlayPhase.RECEIVING -> R.string.generation_progress_receiving
            AgentOverlayPhase.TOOL -> R.string.pocket_overlay_tool
            AgentOverlayPhase.APPROVAL -> R.string.pocket_overlay_approval
            AgentOverlayPhase.CONTINUING -> R.string.pocket_overlay_continuing
        })
        usage.text = if (snapshot.contextLimit != null) context.getString(R.string.pocket_overlay_context,
            format.format(snapshot.usedTokens), format.format(snapshot.contextLimit), snapshot.percent ?: 0)
        else context.getString(R.string.pocket_overlay_context_unknown, format.format(snapshot.usedTokens))
        val indicator = when (snapshot.level) {
            ContextUsageLevel.FULL, ContextUsageLevel.THRESHOLD_REACHED -> palette.error
            ContextUsageLevel.NEAR_THRESHOLD -> palette.tertiary
            ContextUsageLevel.UNKNOWN -> palette.outline
            ContextUsageLevel.NORMAL -> palette.primary
        }
        compactUsage.text = snapshot.percent?.let { context.getString(R.string.pocket_overlay_percent, it) } ?: "?"
        compactUsage.setTextColor(indicator)
        collapse.setTextColor(palette.onSurfaceVariant)
        title.setTextColor(palette.onSurface)
        phase.setTextColor(palette.onSurfaceVariant)
        usage.setTextColor(indicator)
        background = GradientDrawable().apply {
            cornerRadius = if (collapsed) dp(100).toFloat() else dp(22).toFloat()
            setColor(palette.surface)
            setStroke(dp(1).coerceAtLeast(1), palette.track)
        }
        ring.bind(snapshot, indicator, palette.track, palette.primary)
        contentDescription = listOfNotNull(
            title.text, phase.text,
            if (snapshot.contextLimit != null) context.getString(R.string.context_usage_accessible,
                format.format(snapshot.usedTokens), format.format(snapshot.contextLimit), snapshot.percent ?: 0)
            else context.getString(R.string.context_usage_unknown_accessible, format.format(snapshot.usedTokens)),
            context.getString(R.string.context_usage_streaming),
            context.getString(R.string.pocket_overlay_drag_hint),
            when (snapshot.level) {
                ContextUsageLevel.FULL -> context.getString(R.string.context_usage_full)
                ContextUsageLevel.THRESHOLD_REACHED -> context.getString(R.string.context_usage_threshold)
                ContextUsageLevel.NEAR_THRESHOLD -> context.getString(R.string.context_usage_near)
                else -> null
            },
            if (state.activeCount > 1) context.getString(R.string.pocket_overlay_parallel_description) else null,
        ).joinToString(". ")
    }
}

private class ContextRingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()
    private val icon = ContextCompat.getDrawable(context, R.drawable.small_icon)?.mutate()
    private var snapshot: ContextUsageSnapshot? = null
    private var indicator = 0
    private var track = 0

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bind(usage: ContextUsageSnapshot, indicatorColor: Int, trackColor: Int, iconColor: Int) {
        snapshot = usage
        indicator = indicatorColor
        track = trackColor
        icon?.setTint(iconColor)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = resources.displayMetrics.density * 2.5f
        paint.strokeWidth = stroke
        bounds.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.color = track
        canvas.drawArc(bounds, -90f, 360f, false, paint)
        paint.color = indicator
        snapshot?.fraction?.let { fraction ->
            if (fraction > 0) canvas.drawArc(bounds, -90f, 360 * fraction, false, paint)
        } ?: repeat(8) { canvas.drawArc(bounds, it * 45f, 16f, false, paint) }
        // A tick marks the configured compaction boundary without confusing it with usage.
        snapshot?.let { usage ->
            if (usage.contextLimit != null && usage.compactionTrigger != null) {
                val angle = Math.toRadians((usage.compactionTrigger.toDouble() / usage.contextLimit * 360) - 90)
                val outer = width / 2f
                val inner = outer - stroke * 2
                canvas.drawLine(width / 2f + kotlin.math.cos(angle).toFloat() * inner,
                    height / 2f + kotlin.math.sin(angle).toFloat() * inner,
                    width / 2f + kotlin.math.cos(angle).toFloat() * (outer - stroke / 2),
                    height / 2f + kotlin.math.sin(angle).toFloat() * (outer - stroke / 2), paint)
            }
        }
        icon?.apply {
            val inset = (width * .24f).toInt()
            setBounds(inset, inset, width - inset, height - inset)
            draw(canvas)
        }
    }
}
