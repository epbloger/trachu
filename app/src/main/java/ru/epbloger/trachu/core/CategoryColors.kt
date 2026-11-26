package ru.epbloger.trachu.core

/** The accent belongs to the app; categories have their own data palette. */
object CategoryColors {
    val palette = listOf(
        0xFF16845B, 0xFF2868B8, 0xFFCF7330, 0xFFBD4059,
        0xFF7856BE, 0xFF14818D, 0xFFAA7B16, 0xFFB34F99,
        0xFF536B8F, 0xFF8A563D, 0xFF527C32, 0xFFAF4D25
    )

    private fun unused(used:Set<Long>):Long = palette.firstOrNull{it !in used}
        ?: generateSequence(0){it+1}.map{index->
            // Golden-angle hue rotation, alternating saturation/value for
            // large user-defined category collections; always opaque RGB.
            val hue=(index*137.507764)%360/60
            val value=if(index%2==0)0.76 else 0.60
            val c=value*(if(index%3==0)0.62 else 0.82)
            val x=c*(1-kotlin.math.abs(hue%2-1))
            val m=value-c
            val (r,g,b)=when(hue.toInt()){
                0->Triple(c,x,0.0);1->Triple(x,c,0.0);2->Triple(0.0,c,x)
                3->Triple(0.0,x,c);4->Triple(x,0.0,c);else->Triple(c,0.0,x)
            }
            0xFF000000L or (((r+m)*255).toLong() shl 16) or
                (((g+m)*255).toLong() shl 8) or ((b+m)*255).toLong()
        }.first{it !in used}

    // Reserve chart-only fallback colors too, so a new automatically colored
    // category cannot recolor existing legacy duplicates in either report.
    fun next(categories: List<Category>): Long = unused(
        categories.map{it.color}.toSet()+categories.groupBy{it.income}.values.flatMap{chart(it).values}
    )

    /** Give duplicate stored colors distinct chart colors without altering old
     * categories or overriding their explicit saved settings. */
    fun chart(categories: List<Category>): Map<String, Long> {
        val reserved = categories.map { it.color }.toSet()
        val assigned = mutableSetOf<Long>()
        return categories.sortedBy { it.id }.associate { category ->
            val color = if (category.color !in assigned) category.color
                else unused(reserved + assigned)
            assigned.add(color)
            category.id to color
        }
    }
}
