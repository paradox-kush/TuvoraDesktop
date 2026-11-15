package com.nuvio.app.features.radar

/**
 * Desktop keyboard order for the game screen's header controls: the primary Play button first, then the section
 * chips left to right. Pure so it tests without Compose; Mobile's chip row plugs in by passing its chip count.
 *
 * Tab / Shift+Tab walk the whole order and stop at the ends (focus then leaves to the page's own traversal);
 * Left/Right move across chips only; Down from a chip goes to PRIMARY, Up from PRIMARY goes to the selected chip.
 */
object DesktopFocusOrderPolicy {
    sealed interface Target {
        data object Primary : Target
        data class Chip(val index: Int) : Target
    }

    enum class Key { TAB, SHIFT_TAB, LEFT, RIGHT, UP, DOWN }

    /** The next focus target for [key] from [current], or null when the key leaves the group (caller lets Compose handle it). */
    fun next(current: Target, key: Key, chipCount: Int, selectedChip: Int = 0): Target? {
        val last = chipCount - 1
        val sel = selectedChip.coerceIn(0, maxOf(last, 0))
        return when (current) {
            Target.Primary -> when (key) {
                Key.TAB, Key.RIGHT, Key.UP -> if (chipCount > 0) Target.Chip(if (key == Key.UP) sel else 0) else null
                else -> null
            }
            is Target.Chip -> {
                val i = current.index.coerceIn(0, maxOf(last, 0))
                when (key) {
                    Key.TAB, Key.RIGHT -> if (i < last) Target.Chip(i + 1) else null
                    Key.LEFT -> if (i > 0) Target.Chip(i - 1) else null
                    Key.SHIFT_TAB -> if (i > 0) Target.Chip(i - 1) else Target.Primary
                    Key.DOWN -> Target.Primary
                    Key.UP -> null
                }
            }
        }
    }

    /** Left/Right within a chip row of [chipCount]: the chip index to focus, or null at the ends / for other keys. */
    fun chipArrowTarget(index: Int, key: Key, chipCount: Int): Int? {
        if (key != Key.LEFT && key != Key.RIGHT) return null
        return (next(Target.Chip(index), key, chipCount) as? Target.Chip)?.index
    }

    /** After the chip list shrinks, keep focus on a chip that still exists, else on PRIMARY. */
    fun clamp(current: Target, chipCount: Int): Target = when {
        current is Target.Chip && chipCount <= 0 -> Target.Primary
        current is Target.Chip -> Target.Chip(current.index.coerceIn(0, chipCount - 1))
        else -> current
    }
}
