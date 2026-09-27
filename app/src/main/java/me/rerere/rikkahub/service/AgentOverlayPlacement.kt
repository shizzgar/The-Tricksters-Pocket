package me.rerere.rikkahub.service

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Fractions of available travel survive rotation, display resizing and switching card size. */
internal data class AgentOverlayPlacement(
    val horizontal: Float = .5f,
    val vertical: Float = .08f,
    val collapsed: Boolean = false,
) {
    fun normalized() = copy(
        horizontal = horizontal.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f,
        vertical = vertical.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .08f,
    )
}

internal data class OverlayPoint(val x: Int, val y: Int)

/** Physical screen coordinates, already inset away from system bars and display cutouts. */
internal data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(1)
    val height get() = (bottom - top).coerceAtLeast(1)

    fun position(placement: AgentOverlayPlacement, width: Int, height: Int): OverlayPoint {
        val safe = placement.normalized()
        return OverlayPoint(
            left + ((this.width - width).coerceAtLeast(0) * safe.horizontal).roundToInt(),
            top + ((this.height - height).coerceAtLeast(0) * safe.vertical).roundToInt(),
        )
    }

    fun clamp(x: Int, y: Int, width: Int, height: Int) = OverlayPoint(
        x.coerceIn(left, (right - width).coerceAtLeast(left)),
        y.coerceIn(top, (bottom - height).coerceAtLeast(top)),
    )

    fun placement(point: OverlayPoint, width: Int, height: Int, previous: AgentOverlayPlacement): AgentOverlayPlacement {
        val clamped = clamp(point.x, point.y, width, height)
        // A display temporarily smaller than the card must not destroy its remembered anchor.
        return previous.copy(
            horizontal = (this.width - width).takeIf { it > 0 }?.let { (clamped.x - left).toFloat() / it } ?: previous.horizontal,
            vertical = (this.height - height).takeIf { it > 0 }?.let { (clamped.y - top).toFloat() / it } ?: previous.vertical,
        ).normalized()
    }
}

/** Once a gesture becomes a drag it cannot become a tap again, even if the finger returns. */
internal class OverlayDragGesture(private val touchSlop: Float) {
    private var startX = 0f
    private var startY = 0f
    private var active = false
    var dragging = false
        private set

    fun start(x: Float, y: Float) {
        startX = x
        startY = y
        active = true
        dragging = false
    }

    fun move(x: Float, y: Float): OverlayPoint? {
        if (!active) return null
        val dx = x - startX
        val dy = y - startY
        if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
        return if (dragging) OverlayPoint(dx.roundToInt(), dy.roundToInt()) else null
    }

    fun finish(cancelled: Boolean = false): Boolean {
        val clicked = active && !dragging && !cancelled
        active = false
        dragging = false
        return clicked
    }
}
