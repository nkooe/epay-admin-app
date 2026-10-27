package com.epay.admin

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One request per record; an uncertain result prevents any later submission. */
internal suspend fun executeBatch(source:Source,action:String,targetKey:String,targets:List<String>,values:JSONObject):JSONObject {
    require(targets.size in 1..50 && targets.distinct().size==targets.size)
    require(targetKey in listOf("trade_no","biz_no","id"))
    val results=JSONArray();var stopped=false;var expired=false
    targets.forEach { id ->
        if(stopped)results.put(obj("id" to id,"message" to "未执行","status" to "skipped"))
        else try {
            val notify=action=="order-notify"
            val data=if(notify)obj("trade_no" to id)else obj("action" to action,"values" to JSONObject(values.toString()).put(targetKey,id))
            val output=source.call(if(notify)"orders/notify"else"actions/run",data,true,UUID.randomUUID().toString())
            val delivered=!notify||output.optBoolean("delivered")
            results.put(obj("id" to id,"message" to output.s("message","已完成"),"status" to if(delivered)"done"else"failed"))
        }catch(e:Exception){
            if(e is CancellationException)throw e
            results.put(obj("id" to id,"message" to (e.message?:"结果待核实"),"status" to "failed"))
            stopped=true;expired=e is ApiFailure&&e.status==401
        }
    }
    return obj("message" to if(stopped)"已停止，请核对异常记录后继续"else"批量操作已完成","results" to results,"stopped" to stopped,"auth_expired" to expired)
}
