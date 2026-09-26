package me.rerere.rikkahub.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
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

/** A non-interactive window; lifecycle belongs to all active chat sessions, never a single turn. */
object AgentOverlay {
    private const val TAG = "AgentOverlay"
    private var view: AgentOverlayCard? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun canShow(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    internal fun update(context: Context, state: AgentOverlayState?, palette: AgentOverlayPalette) {
        val app = context.applicationContext
        mainHandler.post {
            if (state == null || !canShow(app)) {
                hideInternal(app)
                return@post
            }
            view?.let { it.bind(state, palette); return@post }
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val card = AgentOverlayCard(app).apply { bind(state, palette) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (64 * app.resources.displayMetrics.density).toInt()
                // Android 12+ only passes touches through untrusted overlays at opacity <= 0.8.
                alpha = .8f
            }
            try {
                wm.addView(card, params)
                view = card
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to show agent overlay", error)
            }
        }
    }

    fun hide(context: Context) {
        mainHandler.post { hideInternal(context.applicationContext) }
    }

    private fun hideInternal(app: Context) {
        val old = view ?: return
        view = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            wm.removeViewImmediate(old)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to remove agent overlay", error)
        }
    }
}

/** Separate from the overlay permission/window so rendering can also be exercised in UI tests. */
internal class AgentOverlayCard(context: Context) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val ring = ContextRingView(context)
    private val title = label(12f, bold = true)
    private val phase = label(11f)
    private val usage = label(11f)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(ring, LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(phase)
            addView(usage)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        minimumWidth = dp(240)
        elevation = dp(6).toFloat()
    }

    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(dp(304), resources.displayMetrics.widthPixels - dp(24))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    fun bind(state: AgentOverlayState, palette: AgentOverlayPalette) {
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
        title.setTextColor(palette.onSurface)
        phase.setTextColor(palette.onSurfaceVariant)
        usage.setTextColor(indicator)
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
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
