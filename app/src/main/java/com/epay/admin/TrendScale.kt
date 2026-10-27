package com.epay.admin

import java.math.BigDecimal
import java.math.RoundingMode

internal data class TrendScale(val low:BigDecimal,val high:BigDecimal) {
    fun ratio(value:BigDecimal):Float=(value-low).divide(high-low,8,RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)
}

internal fun trendScale(values:List<BigDecimal>):TrendScale {
    val min=values.minOrNull()?:BigDecimal.ZERO
    val max=values.maxOrNull()?:BigDecimal.ZERO
    val span=max-min
    if(span.signum()==0){
        val padding=(max.abs()*BigDecimal("0.08")).max(BigDecimal("0.01"))
        return TrendScale((min-padding).max(BigDecimal.ZERO),max+padding)
    }
    val padding=span*BigDecimal("0.04")
    return TrendScale((min-padding).max(BigDecimal.ZERO),max+padding)
}
