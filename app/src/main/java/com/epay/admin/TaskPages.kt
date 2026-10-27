package com.epay.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

internal fun managementState(value:String):String=mapOf(
    "active" to "运行中","running" to "运行中","inactive" to "已停止","stopped" to "已停止",
    "failed" to "失败","success" to "成功","completed" to "已完成","queued" to "已排队",
    "pending" to "等待处理","starting" to "启动中","stopping" to "停止中","draining" to "等待任务结束",
    "attention" to "需要核查","timed_out" to "心跳超时","interrupted" to "异常中断",
    "not_installed" to "未安装","invalid_config" to "配置无效","idle" to "空闲",
    "unknown" to "待核实","sending" to "提交中","verified" to "已核实"
)[value]?:value.ifBlank{"暂无记录"}

@Composable internal fun ManagementLine(title:String,value:String){
    Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Text(title,fontSize=12.sp,lineHeight=18.sp,color=muted(),modifier=Modifier.widthIn(min=60.dp))
        Text(value.ifBlank{"—"},fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.weight(1f))
    }
}

@Composable private fun LiveTaskStatus(model:AdminModel){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.current.route,lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
        while(isActive){delay(5000);if(!model.loading&&!model.busy)model.load()}
    }}
}

@Composable internal fun TasksPage(model:AdminModel){
    val data=model.content;if(data.length()==0)return
    LiveTaskStatus(model)
    val service=data.optJSONObject("service")?:obj();val worker=data.optJSONObject("worker")?:obj()
    var confirm by remember{mutableStateOf<Pair<String,()->Unit>?>(null)}
    var syncReport by remember{mutableStateOf<JSONObject?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Group{
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("整体服务",Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium)
                Text(managementState(service.s("active_state")),fontSize=12.sp,color=if(worker.optBoolean("healthy"))primary()else muted())
                MoreActions(buildList{
                    if(service.optBoolean("available"))listOf("start" to "启动","stop" to "停止","restart" to "重启","enable" to "开机启动","disable" to "取消开机启动").forEach{(action,title)->
                        add(UiAction(title,destructive=action=="stop"){confirm=title+"整体服务？" to {model.save("tasks/service",obj("action" to action)){model.announce("控制请求已受理，正在核对实际状态");model.load()}}})
                    }
                    add(UiAction("服务日志"){model.go("task-logs","服务日志")})
                    add(UiAction("同步插件任务"){model.save("tasks/sync",obj()){result->syncReport=result;model.announce("已读取同步结果");model.load()}})
                    add(UiAction("新增通道任务"){model.go("task-edit","新增通道任务")})
                },!model.busy)
            }
            ManagementLine("当前 PID",service.s("pid","0"))
            ManagementLine("调度心跳",managementState(worker.s("display_state",worker.s("state"))))
            HintLine(worker.s("reason"));HintLine(service.s("message"))
            HintLine("支付通道启用后自动接管，默认每 3 秒处理；投诉接口启用后默认每天 03:00 同步，无需另外创建任务。")
        }}
        syncReport?.let{report->item{Group{
            val services=report.optJSONObject("services")?:obj();val errors=report.rows("errors")+services.rows("errors")
            Text("新增 ${report.optInt("created")} 个任务、${services.optInt("created")} 个服务",fontSize=12.sp)
            if(errors.isNotEmpty())CollapsibleSection("${errors.size} 项同步异常"){errors.forEach{HintLine(it.s("channel_name",it.s("plugin"))+"："+it.s("error",it.s("message")))}}
        }}}
        if(data.rows("requests").isNotEmpty())item{Group{HintLine("${data.rows("requests").size} 个任务请求等待或正在执行；提交后以执行记录为准。")}}
        item{Text("计划任务",fontSize=12.sp,color=muted(),modifier=Modifier.padding(horizontal=4.dp))}
        items(data.rows(),key={"task:${it.s("id")}"}){row->Group{
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(row.s("name"),fontSize=13.sp,fontWeight=FontWeight.Medium)
                    Text((if(row.optInt("enabled")==1)"已启用"else"已停用")+" · "+managementState(row.s("last_status")),fontSize=11.sp,color=muted())
                }
                MoreActions(buildList{
                    add(UiAction("配置"){model.go("task-edit",row.s("name"),obj("id" to row.s("id")))})
                    if(worker.optBoolean("healthy")&&row.s("unavailable").isBlank()&&!row.optBoolean("locked"))add(UiAction("立即执行"){model.save("tasks/run",obj("id" to row.s("id"),"revision" to row.optInt("revision"))){model.announce("任务已排队，等待调度执行");model.load()}})
                    add(UiAction("执行记录"){model.go("task-history","执行记录",obj("id" to row.s("id")))})
                    if(row.optBoolean("rebind_available"))add(UiAction("重新接入插件"){model.save("tasks/rebind",obj("id" to row.s("id"),"revision" to row.optInt("revision"))){model.announce("已重新接入");model.load()}})
                },!model.busy)
            }
            if(row.s("channel_name").isNotBlank())ManagementLine("通道",row.s("channel_name"))
            ManagementLine("执行周期",if(row.s("schedule_type")=="daily")"每日 ${row.s("daily_at")}"else"每 ${row.s("interval_seconds")} 秒")
            if(row.optBoolean("automatic"))HintLine(if(row.s("action")=="complain")"随投诉接口启停自动管理"else"随支付通道启停自动管理")
            ManagementLine("下次执行",row.s("next_run_at"));HintLine(row.s("unavailable"));HintLine(row.s("last_error"))
        }}
        item{Text("插件常驻服务",fontSize=12.sp,color=muted(),modifier=Modifier.padding(horizontal=4.dp))}
        if(data.rows("plugin_services").isEmpty())item{Group{HintLine("暂无已接入的插件常驻服务。")}}
        items(data.rows("plugin_services"),key={"plugin:${it.s("id")}"}){row->Group{
            ActionLine(row.s("name",row.s("service_key")),"${row.s("plugin")} · ${managementState(row.s("observed"))} · 查看进程",enabled=!model.busy){model.go("task-plugin","插件服务",obj("id" to row.s("id")))}
            row.optJSONObject("operation")?.let{HintLine(managementState(it.s("state"))+"："+it.s("message"))}
            if(row.optBoolean("automatic"))HintLine("随通道启停自动接管 · 默认每 3 秒处理")
            if(row.optBoolean("changed"))HintLine("插件或通道配置已变化，调度器将排空旧实例后自动应用。")
        }}
    }
    confirm?.let{(title,action)->CompactDialog(onDismissRequest={confirm=null},title={Text(title)},text={Text("将影响任务调度及插件服务，操作后会重新读取实际状态。")},confirmButton={TextButton(onClick={confirm=null;action()},enabled=!model.busy){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}

@Composable internal fun TaskEditorPage(model:AdminModel){
    val data=model.content;if(data.length()==0)return
    val row=data.optJSONObject("record")?:obj();val existing=row.optInt("id")>0
    val identity=model.current.args.s("id")
    var action by remember(identity){mutableStateOf(row.s("action","complain"))}
    var channel by remember(identity){mutableStateOf(row.s("channel_id"))}
    var job by remember(identity){mutableStateOf(row.s("job_key"))}
    var schedule by remember(identity){mutableStateOf(row.s("schedule_type",if(action=="complain")"daily"else"interval"))}
    var interval by remember(identity){mutableStateOf(row.s("interval_seconds",if(action=="plugin")"3"else"60"))}
    var daily by remember(identity){mutableStateOf(row.s("daily_at","03:00"))}
    var successDays by remember(identity){mutableStateOf(row.s("retention_success_days","7"))}
    var failureDays by remember(identity){mutableStateOf(row.s("retention_failure_days","30"))}
    var enabled by remember(identity){mutableStateOf(row.optInt("enabled")==1)}
    var acknowledged by remember(identity){mutableStateOf(false)}
    var validation by remember(identity){mutableStateOf("")}
    val minimumInterval=if(action=="plugin")3 else 10
    Page{Group(Modifier.padding(horizontal=12.dp)){
        if(existing)ManagementLine("任务",row.s("name"))else ChoiceField("任务类型",action,obj("complain" to "通道投诉同步","plugin" to "通道插件任务"),!model.busy){action=it;job="";schedule=if(it=="complain")"daily"else"interval";interval=if(it=="plugin")"3"else"60"}
        if(action in setOf("complain","plugin")){
            if(existing)ManagementLine("通道",row.s("channel_name",channel))else ChoiceField("通道",channel,obj().apply{data.rows("channels").forEach{put(it.s("id"),it.s("name"))}},!model.busy){channel=it;job=""}
            if(action=="plugin"){
                if(existing)ManagementLine("插件任务",job)else ChoiceField("插件任务",job,obj().apply{data.rows("plugin_jobs").filter{it.s("channel_id")==channel&&!it.has("error")}.forEach{put(it.s("key"),it.s("name"))}},!model.busy){job=it}
            }
        }
        ChoiceField("执行周期",schedule,obj("interval" to "固定间隔","daily" to "每日定时"),!model.busy){schedule=it}
        if(schedule=="interval")Input("间隔秒数（$minimumInterval–86400）",interval,{interval=it},keyboard=KeyboardType.Number,enabled=!model.busy)
        else Input("每日时间（HH:mm）",daily,{daily=it},enabled=!model.busy)
        if(action=="cleanup_logs"){
            Input("成功记录保留天数",successDays,{successDays=it},keyboard=KeyboardType.Number,enabled=!model.busy)
            Input("失败记录保留天数",failureDays,{failureDays=it},keyboard=KeyboardType.Number,enabled=!model.busy)
        }
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用任务",Modifier.weight(1f),fontSize=13.sp);Switch(enabled,{enabled=it},enabled=!model.busy&&!row.optBoolean("automatic"))}
        if(row.optBoolean("automatic"))HintLine(if(action=="complain")"启停由投诉接口管理；可在此调整同步时间。"else"启停由支付通道管理；默认每 3 秒处理。")
        if(row.optBoolean("locked")||row.s("last_status")=="failed"){
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(acknowledged,{acknowledged=it},enabled=!model.busy);Text("已核查上次业务结果，允许恢复",fontSize=12.sp,modifier=Modifier.weight(1f))}
        }
        HintLine(row.s("unavailable"));if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        PrimaryAction("保存配置",!model.busy){
            validation=when{
                action in setOf("complain","plugin")&&(channel.toIntOrNull()?:0)<1->"请选择通道"
                action=="plugin"&&job.isBlank()->"请选择插件任务"
                schedule=="interval"&&(interval.toIntOrNull()?:0) !in minimumInterval..86400->"间隔必须为 $minimumInterval–86400 秒"
                schedule=="daily"&&!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(daily)->"时间格式应为 HH:mm"
                else->""
            }
            if(validation.isBlank()){
                val params=obj();if(action in setOf("complain","plugin"))params.put("channel",channel)
                if(action=="complain")params.put("source",1)
                if(action=="plugin")params.put("job",job)
                if(action=="cleanup_logs"){params.put("success_days",successDays);params.put("failure_days",failureDays)}
                model.save("tasks/save",obj("id" to row.optInt("id"),"revision" to row.optInt("revision"),"action" to action,"params" to params,"enabled" to if(enabled)1 else 0,"schedule_type" to schedule,"interval_seconds" to if(schedule=="interval")interval else JSONObject.NULL,"daily_at" to if(schedule=="daily")daily else JSONObject.NULL,"acknowledge_failure" to if(acknowledged)1 else 0)){model.announce("任务配置已保存");model.back()}
            }
        }
    }}
}

@Composable internal fun TaskPluginPage(model:AdminModel){
    val data=model.content;val row=data.optJSONObject("service")?:return;val unit=data.optJSONObject("unit")?:obj()
    LiveTaskStatus(model)
    var confirm by remember{mutableStateOf<Pair<String,String>?>(null)}
    Page{Group(Modifier.padding(horizontal=12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(row.s("name",row.s("service_key")),Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium)
            MoreActions((if(row.optBoolean("automatic"))listOf("restart" to "重启","refresh" to "核查恢复")else listOf("start" to "启动","stop" to "停止","restart" to "重启","refresh" to "应用配置")).map{(action,title)->UiAction(title,destructive=action=="stop"){confirm=action to title}},!model.busy&&data.optJSONObject("operation")?.s("state") !in setOf("pending","starting"))
        }
        ManagementLine("进程状态",managementState(unit.s("active_state",row.s("observed"))))
        ManagementLine("当前 PID",unit.s("pid",row.s("pid","0")))
        ManagementLine("期望运行",if(row.optInt("desired")==1)"是"else"否")
        ManagementLine("最近心跳",row.s("heartbeat_at"));HintLine(row.s("last_error"))
        data.optJSONObject("operation")?.let{HintLine("${managementState(it.s("state"))}：${it.s("message")}")}
        HintLine(if(row.optBoolean("automatic"))"服务随通道启停自动管理，默认每 3 秒处理；进程运行状态不代表上游账号已在线。"else"进程运行状态不代表上游账号已在线；启停不改变支付通道开关。")
        SelectionContainer{Text(data.s("text","暂无日志"),fontSize=11.sp,lineHeight=17.sp,fontFamily=FontFamily.Monospace)}
    }}
    confirm?.let{(action,title)->CompactDialog(onDismissRequest={confirm=null},title={Text("$title${row.s("name","插件服务")}？")},text={Text((if(action=="refresh")"应用配置会重新启动该服务。"else"")+"请求由现有服务调度器执行，请以随后显示的 PID、状态及操作记录为准。")},confirmButton={TextButton(onClick={confirm=null;model.save("tasks/plugin",obj("id" to row.s("id"),"revision" to row.optInt("revision"),"action" to action)){model.announce("服务操作已排队");model.load()}},enabled=!model.busy){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}

@Composable internal fun TaskLogsPage(model:AdminModel){
    val data=model.content;if(data.length()==0)return
    val dates=data.optJSONArray("days");val selected=data.s("date");var deleting by remember{mutableStateOf(false)}
    val days=buildList{add(data.s("today"));if(dates!=null)for(i in 0 until dates.length())add(dates.getString(i))}.distinct().sortedDescending()
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("按日期查看",fontSize=12.sp,modifier=Modifier.weight(1f),color=muted());TextButton(onClick={deleting=true},enabled=!model.busy&&dates!=null&&(0 until dates.length()).any{dates.optString(it)==selected}){Text("删除当日日志",fontSize=12.sp)}}
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            LazyColumn(Modifier.width(96.dp)){items(days,key={it}){day->Text(day,fontSize=11.sp,color=if(day==selected)primary()else muted(),modifier=Modifier.fillMaxWidth().background(if(day==selected)MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background).clickable(enabled=!model.busy&&!model.loading){model.query(obj("date" to day))}.padding(horizontal=5.dp,vertical=12.dp))}}
            SelectionContainer(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())){Text(data.s("text").ifBlank{"当日暂无日志"},fontSize=11.sp,lineHeight=17.sp,fontFamily=FontFamily.Monospace)}
        }
    }
    if(deleting)CompactDialog(onDismissRequest={deleting=false},title={Text("删除 $selected 日志？")},text={Text("将实际删除这一天的日志文件，无法恢复。服务继续运行时会重新生成新的日志。")},confirmButton={TextButton(onClick={deleting=false;model.save("tasks/log-delete",obj("date" to selected)){model.announce("日志文件已删除");model.query(obj())}},enabled=!model.busy){Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

@Composable internal fun TaskHistoryPage(model:AdminModel){
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(model.content.rows().isEmpty()&&!model.loading)item{EmptyMessage("暂无执行记录","手动和定时执行均在此显示实际结果。")}
        items(model.content.rows(),key={it.s("id")}){row->Group{
            ManagementLine("执行结果",managementState(row.s("status")))
            ManagementLine("开始",row.s("started_at"));ManagementLine("结束",row.s("finished_at"))
            HintLine(row.s("error"));SelectionContainer{Text(row.s("summary"),fontSize=12.sp,lineHeight=18.sp)}
        }}
        if(model.content.optBoolean("has_more"))item{TextButton(onClick={model.load(true)},enabled=!model.loading&&!model.busy,modifier=Modifier.fillMaxWidth()){Text("加载更早记录")}}
    }
}
