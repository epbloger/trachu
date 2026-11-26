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

    @Test fun addingDefaultCategoryPreservesExistingReportColors() {
        val categories=listOf(false,true).flatMap{income->
            (0..2).map{Category(id="$income-$it",name="Category $it",income=income,color=0xFF6143CC)}
        }
        val next=CategoryColors.next(categories)
        for(income in listOf(false,true)){
            val group=categories.filter{it.income==income}
            val before=CategoryColors.chart(group)
            val after=CategoryColors.chart(group+Category(id="new-$income",name="New",income=income,color=next))
            assertFalse(next in before.values)
            assertEquals(before,after.filterKeys{it in before})
            assertEquals(next,after["new-$income"])
        }
    }
}
