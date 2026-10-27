package com.epay.admin

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun NativeListPage(model:AdminModel) {
    val route=model.current.route;val resource=if(route=="resource")model.current.args.s("resource")else route
    val rows=model.content.rows();val identity=route+resource
    var q by rememberSaveable(identity){mutableStateOf(model.current.args.s("q"))}
    var filter by remember(identity){mutableStateOf(false)};var detail by remember(identity){mutableStateOf<JSONObject?>(null)};var confirm by remember(identity){mutableStateOf<JSONObject?>(null)}
    var payment by remember(identity){mutableStateOf<JSONObject?>(null)}
    val editable=route=="channels"||model.content.optBoolean("editable");val create=route in listOf("merchants","channels")||model.content.optBoolean("can_create")
    val batchActions=when(resource){
        "orders"->listOf("order-notify" to "通知商户","order-mark-unpaid" to "改未完成","order-mark-paid" to "改已完成","manual-fill" to "手工补单","freeze" to "冻结","unfreeze" to "解冻","order-delete" to "删除订单")
        "transfers"->listOf("transfer-approve" to "审核并付款","transfer-query" to "刷新状态","transfer-complete" to "登记完成","transfer-fail" to "登记失败并退回")
        "settlements"->listOf("settle-pay" to "结算转账","settle-complete" to "登记完成","settle-fail" to "登记失败","settle-query" to "查询转账")
        "sharing"->listOf("sharing-submit" to "提交分账","sharing-query" to "刷新状态","sharing-cancel" to "取消分账")
        else->emptyList()
    }
    var selecting by remember(identity){mutableStateOf(false)};var selected by remember(identity,model.current.args.toString()){mutableStateOf(setOf<String>())}
    val targetKey=when(resource){"orders"->"trade_no";"transfers"->"biz_no";else->"id"}
    fun add(){if(route=="merchants")model.go("merchant-new","新增商户")else model.edit(resource,"新增${model.current.title}")}
    val pageActions=buildList<UiAction>{
        if(create)add(UiAction("新增",Icons.Outlined.Add){add()})
        add(UiAction("刷新",Icons.Outlined.Refresh){selected=emptySet();model.refresh()})
        if(route in listOf("orders","resource"))add(UiAction("导出 CSV",Icons.Outlined.FileDownload){model.requestExport()})
        if(batchActions.isNotEmpty())add(UiAction("批量操作",Icons.Outlined.Checklist){selecting=true;selected=emptySet()})
        when(resource){
            "batches"->add(UiAction("创建待结算批次"){model.operation("batch-create","创建结算批次")})
            "transfers"->{add(UiAction("新增付款"){model.operation("transfer","新增付款")});add(UiAction("创建红包"){model.operation("redpacket","创建红包")});add(UiAction("通道余额"){model.operation("transfer-balance","查询通道余额")})}
            "plugins"->add(UiAction("同步支付插件"){model.go("tool","同步支付插件",obj("tool" to "plugins"))})
        }
    }
    RefreshContainer(model.refreshing,!model.loading&&!model.busy&&!selecting,model::refresh) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(top=4.dp,bottom=16.dp)) {
        item{Column(Modifier.padding(horizontal=16.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text(if(route=="orders")"订单记录"else if(route=="merchants")"商户管理"else if(route=="channels")"支付通道"else model.current.title,fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium);Text("共 ${model.content.s("total",rows.size.toString())} 条",fontSize=11.sp,color=muted())}
                if(route in listOf("orders","merchants","resource"))IconButton(onClick={filter=true}){Icon(Icons.Outlined.Tune,if(listOf("uid","status","channel","from","to").any{model.current.args.s(it).isNotBlank()})"筛选中"else"筛选",Modifier.size(19.dp),tint=primary())}
                MoreActions(pageActions,!model.busy,label="页面操作",iconOnly=true)
            }
            if(route!="sessions")CompactSearch(q,{q=it}){model.query(JSONObject(model.current.args.toString()).put("q",q).put("page",1))}
            if(route=="orders"&&model.current.args.s("channel").isNotBlank())HintLine("当前通道：${model.current.args.s("channel_name").ifEmpty{"#"+model.current.args.s("channel")}}")
            if(selecting)Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={selecting=false;selected=emptySet()}){Text("取消")}
                TextButton(onClick={selected=if(selected.isEmpty())rows.take(50).map{it.s(targetKey)}.toSet()else emptySet()}){Text(if(selected.isEmpty())"全选本页"else"清空")}
                Spacer(Modifier.weight(1f))
                MoreActions(batchActions.map{(action,title)->UiAction(title){model.go("bulk","批量$title",obj("action" to action,"target_key" to targetKey,"targets" to org.json.JSONArray(selected.toList())))}},selected.isNotEmpty()&&!model.busy,label="操作 (${selected.size})")
            }
            Spacer(Modifier.height(8.dp))
        }}
        if(rows.isEmpty()&&!model.loading)item{EmptyMessage("暂无记录","调整筛选条件或刷新后查看")}
        items(rows){row->Column(Modifier.padding(horizontal=16.dp).background(MaterialTheme.colorScheme.surface)){
            if(selecting){val id=row.s(targetKey);Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).toggleable(value=id in selected,role=Role.Checkbox,onValueChange={checked->if(!checked)selected=selected-id else if(selected.size<50)selected=selected+id else model.announce("每次最多选择 50 条")}).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,null,modifier=Modifier.padding(horizontal=10.dp));Text(id,fontSize=11.sp,color=muted())}}
            when(route){
                "orders"->CompactOrderRow(row){model.go("order","订单详情",obj("trade_no" to row.s("trade_no")))}
                "merchants"->Row(Modifier.fillMaxWidth().clickable{model.go("merchant","商户资料",obj("uid" to row.s("uid")))}.heightIn(min=60.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(row.s("username").ifEmpty{"商户 ${row.s("uid")}"},fontSize=13.sp);Text("#${row.s("uid")} · ${row.s("group_name","默认组")} · 余额 ¥${money(row.s("money"))}",fontSize=11.sp,color=muted(),modifier=Modifier.padding(top=4.dp))};Text(if(row.optInt("status")==1)"正常"else"已停用",fontSize=11.sp,color=muted());Icon(Icons.Outlined.ChevronRight,null,Modifier.size(17.dp),tint=muted())}
                "channels"->Row(Modifier.fillMaxWidth().padding(start=12.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Column(Modifier.fillMaxWidth().clickable{model.edit("channels","通道基本设置",row.s("id"))}){Text(row.s("name"),fontSize=13.sp);Text("${row.s("type_name")} · #${row.s("id")} · ${row.s("plugin")}",fontSize=11.sp,color=muted(),modifier=Modifier.padding(top=3.dp));Text("结算 ${row.s("rate")}% · ${if(row.optInt("status")==1)"已启用"else"已停用"}",fontSize=11.sp,color=if(row.optInt("status")==1)primary()else muted())}
                        row.optJSONObject("monitor")?.let{state->
                            Text(state.s("reason"),Modifier.fillMaxWidth().clickable{model.go("plugin","监控诊断与配置",obj("id" to row.s("id")))},fontSize=11.sp,color=if(state.optBoolean("online"))primary()else muted())
                            if(state.optLong("last_seen")>0)Text("商户 ${state.s("merchant_id")} · 心跳 ${heartbeatTime(state.optLong("last_seen"))}",fontSize=11.sp,color=muted())
                        }
                    }
                    MoreActions(listOf(
                        UiAction("基本设置",Icons.Outlined.Edit){model.edit("channels","通道基本设置",row.s("id"))},
                        UiAction("密钥配置",Icons.Outlined.Key){model.go("channel","通道密钥配置",obj("id" to row.s("id")))},
                        UiAction(if(row.optBoolean("monitoring"))"监控诊断与配置"else"授权与插件工具",Icons.Outlined.Settings){model.go("plugin","插件管理",obj("id" to row.s("id")))},
                        UiAction("测试支付",Icons.Outlined.PlayArrow){payment=row},
                        UiAction("订单记录",Icons.Outlined.ReceiptLong){model.go("orders","通道订单记录",obj("channel" to row.s("id"),"channel_name" to row.s("name")))},
                        UiAction(if(row.optInt("status")==1)"停用通道"else"启用通道",Icons.Outlined.PowerSettingsNew){confirm=row}
                    ),!model.busy,label="通道操作",iconOnly=true)
                }
                "sessions"->Row(Modifier.fillMaxWidth().padding(start=12.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(row.s("device"),fontSize=13.sp);Text(row.s("ip"),fontSize=11.sp,color=muted());Text(runCatching{Instant.ofEpochSecond(row.optLong("last_seen")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}.getOrDefault("—"),fontSize=11.sp,color=muted())};MoreActions(listOf(UiAction("撤销登录",Icons.Outlined.Logout,true){confirm=row}),!model.busy,iconOnly=true)}
                else->{
                    val actions=buildList<UiAction>{
                        add(UiAction("详情",Icons.Outlined.Info){detail=row})
                        if(editable)add(UiAction("编辑",Icons.Outlined.Edit){model.edit(resource,"编辑${model.current.title}",row.s(model.content.s("pk","id")))})
                        if(resource=="subchannels")add(UiAction("支付参数",Icons.Outlined.Key){model.go("credentials","子通道参数",obj("kind" to "subchannel","id" to row.s("id")))})
                        addAll(recordActions(model,row,resource))
                        if(resource=="operations"&&row.s("state")=="pending"){
                            if(row.s("id").startsWith("order:"))add(UiAction("登记核实结果"){model.operation("order-reconcile","核实渠道结果",obj("trade_no" to row.s("id").substringAfter(':'),"reference" to row.s("reference")))})
                            if(row.s("id").startsWith("sharing:"))add(UiAction("查询分账"){model.operation("sharing-query","查询分账",obj("id" to row.s("id").substringAfter(':')))})
                            if(row.s("id").startsWith("sharing:")||row.s("id").startsWith("receiver:"))add(UiAction("登记核实结果"){model.operation("operation-reconcile","登记渠道核实结果",obj("object" to row.s("id"),"reference" to row.s("reference")))})
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).clickable{detail=row}){row.keys().asSequence().take(4).forEachIndexed{index,key->
                            if(index==0)Text("${labels[key]?:key} · ${resourceValue(resource,key,row)}",fontSize=13.sp,lineHeight=19.sp,maxLines=2)
                            else Text("${labels[key]?:key}  ${resourceValue(resource,key,row)}",fontSize=11.sp,lineHeight=17.sp,color=muted(),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }}
                        MoreActions(actions,!model.busy,label="记录操作",iconOnly=true)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal=12.dp),thickness=.5.dp)
        }}
        if(model.content.optBoolean("has_more"))item{TextButton(onClick={model.load(true)},enabled=!model.loading,modifier=Modifier.fillMaxWidth().padding(8.dp)){Text(if(model.loading)"正在加载…"else"加载更多",fontSize=12.sp)}}
        else if(rows.isNotEmpty())item{Text("已显示全部记录",Modifier.fillMaxWidth().padding(14.dp),color=muted(),fontSize=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
    }
    if(filter)ResourceFilter(model,resource){filter=false}
    payment?.let{row->ChannelTestDialog(model,row,dismiss={payment=null})}
    detail?.let{row->CompactDialog(onDismissRequest={detail=null},title={Text("记录详情")},text={Column(Modifier.verticalScroll(rememberScrollState())){row.keys().forEach{KeyValue(labels[it]?:it,resourceValue(resource,it,row))}}},confirmButton={TextButton(onClick={detail=null}){Text("关闭")}})}
    confirm?.let{row->ConfirmDialog(if(route=="channels")"${if(row.optInt("status")==1)"停用"else"启用"}通道？"else"撤销此设备登录？",if(route=="channels")row.s("name")else"该设备需要重新登录。",model.busy,{confirm=null},model.error){
        if(route=="channels")model.save("channels/status",obj("id" to row.s("id"),"status" to if(row.optInt("status")==1)0 else 1)){confirm=null;model.announce("通道状态已更新");model.load()}
        else model.save("sessions/revoke",obj("id" to row.s("id"))){confirm=null;if(it.optBoolean("current"))model.clearSession()else model.load()}
    }}
}

/** 常规字号最多显示两行；仅在大字号确有需要时额外增加空间。 */
@Composable internal fun CompactOrderRow(row:JSONObject,click:()->Unit){
    val merchant=row.s("merchant_name").ifBlank{row.s("uid").takeIf{it.isNotBlank()}?.let{"商户 #$it"}?:row.s("name").ifBlank{"订单"}}
    val status=when(row.optInt("status")){0->"未支付";1->"已支付";2->"已退款";3->"已冻结";4->"预授权";else->"未知状态"}
    BoxWithConstraints(Modifier.testTag("order-row-${row.s("trade_no")}").fillMaxWidth().clickable(onClick=click).padding(horizontal=12.dp,vertical=8.dp)){
        val stacked=maxWidth<280.dp||LocalDensity.current.fontScale>1.25f
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
            Row(Modifier.testTag("order-primary").fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                Text(merchant,Modifier.weight(1f),fontSize=13.sp,lineHeight=19.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(status,fontSize=11.sp,lineHeight=16.sp,color=if(row.optInt("status")==1)primary()else muted())
                if(!stacked)Text("¥${money(row.s("money"))}",Modifier.testTag("order-money"),fontSize=13.sp,lineHeight=19.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
            }
            if(stacked)Text("¥${money(row.s("money"))}",Modifier.testTag("order-money"),fontSize=13.sp,lineHeight=19.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
            if(stacked){
                Text(row.s("trade_no"),Modifier.testTag("order-number"),fontSize=11.sp,lineHeight=16.sp,color=muted())
                Text(row.s("addtime"),Modifier.testTag("order-time"),fontSize=11.sp,lineHeight=16.sp,color=muted())
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                Text(row.s("trade_no"),Modifier.testTag("order-number").weight(1f),fontSize=11.sp,lineHeight=16.sp,color=muted(),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(row.s("addtime"),Modifier.testTag("order-time"),fontSize=11.sp,lineHeight=16.sp,color=muted(),maxLines=1)
            }
        }
    }
}

@Composable private fun ResourceFilter(model:AdminModel,resource:String,dismiss:()->Unit){
    val keys=when(resource){
        "orders"->listOf("uid","status","channel","from","to")
        "merchants"->listOf("status")
        "refunds","settlements","risks"->listOf("uid","status","from","to")
        "transfers"->listOf("uid","status","channel","from","to")
        "receivers"->listOf("uid","status","channel","from","to")
        "domains"->listOf("uid","status","from","to")
        "records","logs"->listOf("uid","from","to")
        "sharing","blacklist","weixin","invites","announcements"->listOf("status","from","to")
        else->emptyList()
    }
    val values=remember{mutableStateMapOf<String,String>().apply{keys.forEach{put(it,model.current.args.s(it))}}};var error by remember{mutableStateOf("")}
    CompactDialog(onDismissRequest=dismiss,title={Text("筛选记录")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        keys.forEach{key->if(key=="status"){
            val options=when(resource){
                "orders"->obj("" to "全部状态","0" to "未支付","1" to "已支付","2" to "已退款","3" to "已冻结","4" to "预授权")
                "settlements"->obj("" to "全部状态","0" to "待结算","1" to "已完成","2" to "批次结算中","3" to "失败")
                "transfers"->obj("" to "全部状态","0" to "处理中","1" to "成功","2" to "失败","3" to "待审核","4" to "待领取")
                "sharing"->obj("" to "全部状态","0" to "待分账","1" to "处理中","2" to "成功","3" to "失败","4" to "已取消 / 回退")
                else->obj("" to "全部状态","0" to "0","1" to "1","2" to "2","3" to "3")
            };ChoiceField("状态",values[key]?:"",options){values[key]=it}
        }else Input(when(key){"from"->"开始日期";"to"->"结束日期";else->labels[key]?:key},values[key]?:"",{values[key]=it},if(key in listOf("from","to"))"YYYY-MM-DD"else"留空表示全部")}
        if(keys.isEmpty())Text("此记录可通过关键字搜索。",fontSize=13.sp)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    }},confirmButton={TextButton(onClick={try{val from=values["from"].orEmpty();val to=values["to"].orEmpty();if(from.isNotBlank())java.time.LocalDate.parse(from);if(to.isNotBlank())java.time.LocalDate.parse(to);require(from.isBlank()||to.isBlank()||from<=to);val args=JSONObject(model.current.args.toString()).put("page",1);values.forEach{(k,v)->args.put(k,v)};model.query(args);dismiss()}catch(_:Exception){error="日期无效或起止顺序错误"}}){Text("应用")}},dismissButton={TextButton(onClick={val args=JSONObject(model.current.args.toString());keys.forEach{args.remove(it)};args.put("page",1);model.query(args);dismiss()}){Text("重置")}})
}

internal fun resourceValue(resource:String,key:String,row:JSONObject):String {
    val value=row.s(key)
    if(key=="state")return when(value){"pending"->"待核实";"done"->"已完成";"rejected"->"已取消 / 未执行";else->value}
    if(key!="status")return value
    return when(resource){
        "transfers"->when(row.optInt(key)){0->"处理中";1->"成功";2->"失败";3->"待审核";4->"待领取";else->value}
        "settlements"->when(row.optInt(key)){0->"待结算";1->"已完成";2->"批次处理中";3->"失败";else->value}
        "sharing"->when(row.optInt(key)){0->"待分账";1->"处理中";2->"成功";3->"失败";4->"已取消 / 回退";else->value}
        "subchannels","receivers","types","blacklist","domains"->if(row.optInt(key)==1)"启用"else"停用"
        else->value
    }
}
