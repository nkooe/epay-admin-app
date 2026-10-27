package com.epay.admin

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class TrendScaleTest {
    @Test fun smallAmountsUseMostOfTheChartHeight() {
        val scale=trendScale(listOf("0","0.21","0.61","0.40").map(::BigDecimal))
        assertEquals(0f,scale.ratio(BigDecimal.ZERO),0f)
        assertTrue(scale.ratio(BigDecimal("0.61"))>.95f)
    }
    @Test fun narrowRangeAtHighVolumeShowsActualChangesWithinLabelledBounds() {
        val scale=trendScale(listOf("10000","10010","10005").map(::BigDecimal))
        assertTrue(scale.low<BigDecimal("10000"));assertTrue(scale.high>BigDecimal("10010"))
        assertTrue(scale.ratio(BigDecimal("10010"))-scale.ratio(BigDecimal("10000"))>.9f)
    }
    @Test fun constantAndEmptySeriesStayFiniteAndFlat() {
        for(values in listOf(emptyList(),listOf(BigDecimal.ZERO,BigDecimal.ZERO),listOf(BigDecimal("7.25"),BigDecimal("7.25")))){
            val scale=trendScale(values);assertTrue(scale.high>scale.low)
            values.forEach{assertTrue(scale.ratio(it).isFinite());assertEquals(scale.ratio(values.first()),scale.ratio(it),0f)}
        }
    }
}
