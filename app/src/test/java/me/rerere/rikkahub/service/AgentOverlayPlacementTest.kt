package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class AgentOverlayPlacementTest {
    @Test fun `drag is clamped inside cutout and system bar bounds`() {
        val bounds = OverlayBounds(40, 80, 1080, 2200)
        assertEquals(OverlayPoint(40, 80), bounds.clamp(-200, -40, 320, 64))
        assertEquals(OverlayPoint(760, 2136), bounds.clamp(2000, 3000, 320, 64))
    }

    @Test fun `bottom right anchor survives rotation and collapsing`() {
        val portrait = OverlayBounds(8, 40, 392, 840)
        val saved = portrait.placement(OverlayPoint(72, 776), 320, 64, AgentOverlayPlacement())
        assertEquals(1f, saved.horizontal, .0001f)
        assertEquals(1f, saved.vertical, .0001f)
        val landscape = OverlayBounds(48, 8, 832, 376)
        assertEquals(OverlayPoint(768, 312), landscape.position(saved.copy(collapsed = true), 64, 64))
        assertEquals(OverlayPoint(512, 312), landscape.position(saved, 320, 64))
    }

    @Test fun `fractional position round trips within a pixel`() {
        val bounds = OverlayBounds(20, 40, 1000, 2000)
        val placement = AgentOverlayPlacement(.37f, .63f, true)
        val position = bounds.position(placement, 64, 64)
        assertEquals(position, bounds.position(bounds.placement(position, 64, 64, placement), 64, 64))
        assertTrue(bounds.placement(position, 64, 64, placement).collapsed)
    }

    @Test fun `oversized card and invalid saved values cannot throw or lose anchor`() {
        val bounds = OverlayBounds(30, 40, 80, 90)
        val previous = AgentOverlayPlacement(.9f, .8f, true)
        assertEquals(OverlayPoint(30, 40), bounds.position(previous, 320, 64))
        assertEquals(previous, bounds.placement(OverlayPoint(-30, 200), 320, 64, previous))
        val invalid = AgentOverlayPlacement(Float.NaN, Float.POSITIVE_INFINITY).normalized()
        assertEquals(.5f, invalid.horizontal, .0001f)
        assertEquals(.08f, invalid.vertical, .0001f)
        assertEquals(AgentOverlayPlacement(0f, 1f), AgentOverlayPlacement(-2f, 3f).normalized())
    }

    @Test fun `drag returning to origin never toggles collapse`() {
        val gesture = OverlayDragGesture(8f)
        gesture.start(100f, 200f)
        assertNull(gesture.move(104f, 203f))
        assertEquals(OverlayPoint(20, 0), gesture.move(120f, 200f))
        assertEquals(OverlayPoint(0, 0), gesture.move(100f, 200f))
        assertFalse(gesture.finish())
        assertNull(gesture.move(500f, 500f))
    }

    @Test fun `small movement clicks while cancelled or multitouch gesture does not`() {
        val gesture = OverlayDragGesture(8f)
        gesture.start(0f, 0f)
        assertNull(gesture.move(3f, 4f))
        assertTrue(gesture.finish())
        gesture.start(0f, 0f)
        assertFalse(gesture.finish(cancelled = true))
        assertFalse(gesture.finish())
        gesture.start(0f, 0f)
        assertTrue(gesture.finish())
    }
}
