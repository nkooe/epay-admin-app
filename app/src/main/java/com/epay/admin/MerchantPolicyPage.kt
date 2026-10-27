package com.epay.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

@Composable internal fun MerchantPolicyPage(model:AdminModel){
    val row=model.content;if(row.length()==0)return
    var mode by remember(row.toString()){mutableStateOf(row.s("mode","group"))}
    val fields=remember(row.toString()){mutableStateMapOf<String,String>().apply{
        row.rows("types").forEach{type->
            val id=type.s("id");val rule=row.rows("rules").firstOrNull{it.s("type_id")==id}
            put("$id.enabled",if(rule!=null)"1"else"0")
            put("$id.channel",rule?.let{"${it.s("channel_id")}:${it.s("subchannel_id")}"}?:"")
            put("$id.min",rule?.s("min")?:"0.00");put("$id.max",rule?.s("max")?:"0.00")
        }
    }}
    var validation by remember{mutableStateOf("")}
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("商户 #${row.s("uid")}",row.s("username"))
            ChoiceField("选路模式",mode,obj("group" to "跟随用户组","custom" to "独立配置"),!model.busy){mode=it;validation=""}
            HintLine(if(mode=="group")"按所属用户组的通道、轮询和费率配置处理。"else"仅启用勾选的支付方式。指定通道不可用时直接报错，不切换通道。")
        }
        if(mode=="custom")row.rows("types").forEach{type->
            val id=type.s("id");val enabled=fields["$id.enabled"]=="1"
            Group(Modifier.padding(horizontal=16.dp)){
                Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).toggleable(value=enabled,enabled=!model.busy,role=Role.Switch,onValueChange={fields["$id.enabled"]=if(it)"1"else"0"}),verticalAlignment=Alignment.CenterVertically){
                    Text(type.s("showname",type.s("name"))+(if(type.optInt("status")!=1)"（已停用）"else""),Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium)
                    Switch(enabled,onCheckedChange=null,enabled=!model.busy,modifier=Modifier.scale(.8f))
                }
                if(enabled){
                    val options=obj("" to "请选择通道").apply{row.rows("channels").filter{it.s("type_id")==id}.forEach{channel->
                        put("${channel.s("channel_id")}:${channel.s("subchannel_id")}",channel.s("name")+" #${channel.s("channel_id")}"+(if(channel.optInt("subchannel_id")>0)" / 子通道 #${channel.s("subchannel_id")}"else"")+(if(!channel.optBoolean("enabled"))"（不可用）"else""))
                    }}
                    val selected=fields["$id.channel"]?:"";if(selected.isNotEmpty()&&!options.has(selected))options.put(selected,"已删除的指定通道 #${selected.substringBefore(':')}")
                    ChoiceField("指定通道",selected,options,!model.busy){fields["$id.channel"]=it}
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        Box(Modifier.weight(1f)){Input("单笔最低（元）",fields["$id.min"]?:"0",{fields["$id.min"]=it},keyboard=KeyboardType.Decimal,enabled=!model.busy)}
                        Box(Modifier.weight(1f)){Input("单笔最高（元）",fields["$id.max"]?:"0",{fields["$id.max"]=it},keyboard=KeyboardType.Decimal,enabled=!model.busy)}
                    }
                    HintLine("0 表示不加限制，通道及系统限额仍生效。")
                }
            }
        }
        Group(Modifier.padding(horizontal=16.dp)){
            if(validation.isNotEmpty())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            PrimaryAction("保存策略",!model.busy){
                val rules=JSONArray();validation=""
                if(mode=="custom")row.rows("types").forEach{type->val id=type.s("id");if(fields["$id.enabled"]=="1"){
                    val route=(fields["$id.channel"]?:"").split(':')
                    if(route.size!=2){validation="请为已启用的支付方式指定通道"}else rules.put(obj("type_id" to id,"channel_id" to route[0],"subchannel_id" to route[1],"min" to (fields["$id.min"].orEmpty().ifBlank{"0"}),"max" to (fields["$id.max"].orEmpty().ifBlank{"0"})))
                }}
                if(validation.isEmpty())model.save("merchants/policy/save",obj("uid" to row.s("uid"),"revision" to row.optInt("revision"),"mode" to mode,"rules" to rules)){result->model.announce(result.s("message"));model.back()}
            }
        }
    }
}

