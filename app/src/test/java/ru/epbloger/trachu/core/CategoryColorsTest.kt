package ru.epbloger.trachu.core

import org.junit.Assert.*
import org.junit.Test

class CategoryColorsTest {
    @Test fun newCategoriesReceiveDistinctColorsAndExplicitColorsAreNotChanged() {
        var categories=Ledger.initial().categories
        repeat(25) { i ->
            val next=CategoryColors.next(categories)
            assertFalse(categories.any{it.color==next})
            categories=categories+Category(id="custom-$i",name="Category $i",color=next)
        }
    }

    @Test fun legacyDuplicateColorsHaveStableDistinctChartColors() {
        val categories=(0..24).map{Category(id="category-$it",name="Category $it",color=0xFF6143CC)}
        val colors=CategoryColors.chart(categories)
        assertEquals(categories.size,colors.values.toSet().size)
        assertEquals(colors,CategoryColors.chart(categories.reversed()))
        assertTrue(categories.all{it.color==0xFF6143CC})
        assertEquals(0xFF6143CCL,colors["category-0"])
    }
}
