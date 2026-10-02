package io.github.yuriyurin.fit3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardLayoutTest {
    @Test fun savesOrderAndSizes() {
        val changed = HomeDashboardLayout.resize(
            HomeDashboardLayout.swap(HomeDashboardLayout.default,
                HomeTileKind.STRESS, HomeTileKind.STEPS),
            HomeTileKind.STRESS, HomeTileSize.LARGE)
        assertEquals(changed, HomeDashboardLayout.decode(HomeDashboardLayout.encode(changed)))
    }

    @Test fun badEntriesCannotDuplicateOrHideTiles() {
        val restored = HomeDashboardLayout.decode("STRESS:WIDE,STRESS:LARGE,BOGUS:SMALL")
        assertEquals(HomeTileSize.WIDE, restored.first().size)
        assertEquals(HomeTileKind.values().size, restored.size)
        assertEquals(restored.size, restored.map { it.kind }.distinct().size)
    }

    @Test fun placementsNeverOverlap() {
        val tiles = HomeDashboardLayout.resize(HomeDashboardLayout.default,
            HomeTileKind.WEATHER, HomeTileSize.LARGE)
        val positions = HomeDashboardLayout.place(tiles)
        val cells = mutableSetOf<Pair<Int, Int>>()
        for (position in positions) {
            assertTrue(position.column in 0..1)
            for (row in position.row until position.row + position.rows)
                for (column in position.column until position.column + position.columns)
                    assertFalse(!cells.add(column to row))
        }
    }
}
