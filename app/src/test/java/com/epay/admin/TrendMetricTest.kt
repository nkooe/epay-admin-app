package com.epay.admin

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class TrendMetricTest {
    @Test fun countUsesAllOrdersAndDoesNotSubstitutePaidOrders(){
        val point=obj("amount" to "12.34","orders" to 8,"paid" to 2)
        assertEquals(BigDecimal("12.34"),TrendMetric.AMOUNT.value(point))
        assertEquals(BigDecimal(8),TrendMetric.ORDERS.value(point))
        assertEquals("8 笔",TrendMetric.ORDERS.format(TrendMetric.ORDERS.value(point)))
    }

    @Test fun wholeNumberAxesContainCountsAndHandleEmptyOrConstantSeries(){
        for(values in listOf(emptyList(),listOf(0,0),listOf(3,3),listOf(10000,10010,10005))){
            val numbers=values.map{BigDecimal(it)}
            val scale=TrendMetric.ORDERS.scale(numbers)
            assertTrue(scale.high>scale.low)
            assertTrue(scale.low>=BigDecimal.ZERO)
            assertEquals(0,scale.low.scale());assertEquals(0,scale.high.scale())
            numbers.forEach{assertTrue(it>=scale.low&&it<=scale.high);assertTrue(scale.ratio(it).isFinite())}
        }
    }
}
