package com.epay.admin

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/** The API's orders field counts all created orders, including unpaid orders. */
internal enum class TrendMetric(val title:String,private val field:String) {
    AMOUNT("成交金额","amount"),
    ORDERS("订单笔数","orders");

    fun value(point:JSONObject):BigDecimal=point.s(field).toBigDecimalOrNull()?:BigDecimal.ZERO

    fun format(value:BigDecimal):String=if(this==AMOUNT)"¥${money(value.toPlainString())}"else"${value.setScale(0,RoundingMode.HALF_UP).toPlainString()} 笔"

    fun scale(values:List<BigDecimal>):TrendScale {
        val scale=trendScale(values)
        // Count axes use whole numbers; zero and constant series still need a nonzero span.
        return if(this==AMOUNT)scale else TrendScale(scale.low.setScale(0,RoundingMode.FLOOR),scale.high.setScale(0,RoundingMode.CEILING))
    }
}
