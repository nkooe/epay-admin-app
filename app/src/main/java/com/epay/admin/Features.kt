package com.epay.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@Composable internal fun ChoiceField(label:String,value:String,options:JSONObject,enabled:Boolean=true,change:(String)->Unit) {
    var open by remember { mutableStateOf(false) };var search by remember { mutableStateOf("") }
    SelectionRow(label,options.s(value,value.ifEmpty{"请选择"}),enabled){search="";open=true}
    if(open)CompactDialog(onDismissRequest={open=false},title={Text(label)},text={Column{
        if(options.length()>6)CompactSearch(search,{search=it},"搜索选项"){}
        LazyColumn(Modifier.heightIn(max=360.dp)){items(options.keys().asSequence().filter{search.isBlank()||options.s(it).contains(search,true)||it.contains(search,true)}.toList()){key->
            Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).selectable(selected=value==key,role=Role.RadioButton){change(key);open=false}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(value==key,null,modifier=Modifier.padding(horizontal=10.dp));Text(options.s(key),fontSize=13.sp,lineHeight=19.sp)}
        }}
    }},confirmButton={TextButton(onClick={open=false}){Text("取消")}})
}

@Composable private fun ReceiverField(value:String,enabled:Boolean,change:(String)->Unit) {
    val entries=runCatching{JSONArray(value).let{a->(0 until a.length()).map{a.getJSONObject(it)}}}.getOrDefault(emptyList())
    fun update(index:Int,key:String,text:String){val a=JSONArray(value);a.getJSONObject(index).put(key,text);change(a.toString())}
    Text("分账接收人",fontSize=13.sp)
    entries.forEachIndexed{index,item->Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("接收人 ${index+1}",Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={change(JSONArray(entries.filterIndexed{i,_->i!=index}).toString())},enabled=enabled){Text("移除")}}
        Input("接收账号",item.s("account"),{update(index,"account",it)},enabled=enabled)
        Input("接收人姓名",item.s("name"),{update(index,"name",it)},enabled=enabled)
        Input("分账比例（%）",item.s("rate"),{update(index,"rate",it)},keyboard=KeyboardType.Decimal,enabled=enabled)
    }}
    TextButton(onClick={change(JSONArray(entries+obj("account" to "","name" to "","rate" to "")).toString())},enabled=enabled&&entries.size<20){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Text("添加接收人")}
}

@Composable internal fun FieldEditor(field:JSONObject,value:String,enabled:Boolean,change:(String)->Unit) {
    val label=field.s("label")+if(field.optBoolean("required"))" *" else ""
    when(field.s("type")) {
        "select"->ChoiceField(label,value,field.fieldOptions(),enabled,change)
        "checkbox"->{Text(label,fontSize=12.sp,color=muted(),modifier=Modifier.padding(top=5.dp));val selected=runCatching{JSONArray(value).let{a->(0 until a.length()).map{a.optString(it)}}}.getOrDefault(emptyList());val options=field.fieldOptions();options.keys().forEach{key->Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).toggleable(value=key in selected,enabled=enabled,role=Role.Checkbox,onValueChange={checked->change(JSONArray(if(checked)selected+key else selected-key).toString())}).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(key in selected,null,enabled=enabled,modifier=Modifier.padding(horizontal=10.dp));Text(options.s(key),fontSize=13.sp,lineHeight=19.sp)}}}
        "receivers"->ReceiverField(value,enabled,change)
        else->Input(label,value,change,hint=if(field.optBoolean("secret")&&field.optBoolean("configured"))"已配置，留空保留"else"",keyboard=when(field.s("type")){"integer"->KeyboardType.Number;"decimal"->KeyboardType.Decimal;else->KeyboardType.Text},enabled=enabled,secret=field.optBoolean("secret"),multi=field.s("type")=="textarea")
    }
    if(field.s("hint").isNotBlank())HintLine(field.s("hint"))
}

@Composable internal fun NativeForm(model:AdminModel) {
    if(model.content.optBoolean("read_only")){
        Page { Group(Modifier.padding(horizontal=16.dp)){SectionTitle("任务与服务");HintLine(model.content.s("note"))} }
        return
    }
    if(model.current.route=="management"&&model.current.args.s("resource")=="merchant-api"){
        MerchantApiPage(model)
        return
    }
    val schema=model.content;val bulk=model.current.route=="bulk";val targetKey=model.current.args.s("target_key");val specs=schema.rows("fields").filter{!bulk||it.s("key")!=targetKey};if(schema.length()==0)return
    val identity=model.current.route+model.current.args.toString()+schema.toString()
    // 密码、凭据和 API 密钥只保存在内存中，不写入保存状态。
    val values=remember(identity){mutableStateMapOf<String,String>().apply{specs.forEach{put(it.s("key"),if(schema.s("id").isEmpty()&&model.current.route=="management"&&model.current.args.optJSONObject("defaults")?.has(it.s("key"))==true)model.current.args.getJSONObject("defaults").s(it.s("key"))else it.s("value"))}}}
    var search by remember(identity){mutableStateOf("")};var expanded by remember(identity){mutableStateOf(0)};var confirm by remember(identity){mutableStateOf("")};var validation by remember(identity){mutableStateOf("")};var explanation by remember(identity){mutableStateOf(false)}
    val operation=model.current.route in listOf("operation","bulk");val management=model.current.route=="management"
    val sections=remember(identity){
        if(specs.size<=10)listOf("基本配置" to specs) else specs.groupBy{field->
            val key=field.s("key");val label=field.s("label")
            when {
                model.current.args.s("group")=="site"->when{
                    key in listOf("sitename","title","keywords","description","orgname")->"基本配置"
                    key in listOf("email","kfqq","qqqun")->"联系方式"
                    key.startsWith("reg_")||key in listOf("user_review","user_settings_edit","verifytype")->"注册与商户"
                    key.startsWith("captcha_")||key.startsWith("test_")||key=="close_keylogin"->"验证与测试"
                    else->"页面与链接"
                }
                key.contains("rate")||key.contains("fee")||key.contains("money")||label.contains("金额")||label.contains("比例")->"金额与费率"
                key.contains("cert")||key.contains("secret")||key.contains("key")||label.contains("认证")||field.optBoolean("secret")->"认证与密钥"
                key.contains("limit")||key.contains("max")||key.contains("min")||key.contains("time")||key.contains("risk")||label.contains("限制")->"限制与规则"
                key.contains("notice")||key.contains("msg")||key.contains("mail")||label.contains("通知")||label.contains("提醒")->"消息与通知"
                else->"基本配置"
            }
        }.entries.sortedBy{if(it.key=="基本配置")0 else 1}.flatMap{entry->
            val chunks=entry.value.chunked(8)
            chunks.mapIndexed{index,fields->(entry.key+if(chunks.size>1)" · ${index+1}"else"") to fields}
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)){Page {
            if(model.current.route=="channel"||(model.current.route=="credentials"&&model.current.args.s("kind")=="subchannel"))Group(Modifier.padding(horizontal=16.dp)){
                ActionLine("监控、授权与插件工具",icon=Icons.Outlined.VerifiedUser,enabled=!model.busy){
                    val subchannel=model.current.args.s("kind")=="subchannel"
                    model.go("plugin",if(subchannel)"子通道插件管理"else"插件管理",obj("kind" to if(subchannel)"subchannel"else"channel","id" to model.current.args.s("id",schema.s("id"))))
                }
            }
            if(bulk)HintLine("已选 ${model.current.args.optJSONArray("targets")?.length()?:0} 条 · 逐条执行，异常停止",Modifier.padding(horizontal=16.dp))
            schema.optJSONObject("context")?.takeIf{it.length()>0}?.let{context->Group(Modifier.padding(horizontal=16.dp)){var show by remember(identity){mutableStateOf(false)};Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable{show=!show},verticalAlignment=Alignment.CenterVertically){Text("当前业务资料",Modifier.weight(1f),fontSize=12.sp,color=muted());Text(context.s("trade_no",context.s("username")),fontSize=11.sp);Icon(if(show)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,Modifier.size(16.dp))};if(show)context.keys().forEach{KeyValue(labels[it]?:it,context.s(it))}}}
            if(specs.size>10||schema.s("note").isNotEmpty())Column(Modifier.padding(horizontal=16.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    if(specs.size>10)Box(Modifier.weight(1f)){CompactSearch(search,{search=it},"搜索配置项") {}}
                    else Text("请核对以下信息",Modifier.weight(1f),fontSize=11.sp,color=muted())
                    if(schema.s("note").isNotEmpty())IconButton(onClick={explanation=!explanation}){Icon(Icons.Outlined.Info,"操作说明",Modifier.size(18.dp),tint=muted())}
                }
                if(explanation)HintLine(schema.s("note"))
            }
            if(specs.isEmpty()&&!bulk)HintLine("此插件未声明可编辑参数",Modifier.padding(horizontal=16.dp))
            sections.forEachIndexed{index,(title,section)->
                val shown=section.filter{fieldVisible(it,values)&&(search.isBlank()||it.s("label").contains(search,true)||it.s("key").contains(search,true))}
                if(shown.isNotEmpty())Group(Modifier.padding(horizontal=16.dp)){
                    if(sections.size>1)Row(Modifier.fillMaxWidth().clickable{expanded=if(expanded==index)-1 else index}.heightIn(min=CompactRowHeight).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=13.sp);Text("${section.size} 项",fontSize=11.sp,color=muted());Icon(if(expanded==index)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,Modifier.padding(start=7.dp).size(17.dp))}
                    if(sections.size==1||expanded==index||search.isNotBlank())shown.forEach{field->
                        FieldEditor(field,values[field.s("key")]?:"",!model.busy){values[field.s("key")]=it}
                    }
                }
            }
        }}
        Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=0.dp){Column{
            HorizontalDivider(thickness=.5.dp)
            if(validation.isNotEmpty())Text(validation,Modifier.padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.weight(1f)){PrimaryAction(if(operation)schema.s("name","确认操作")else"保存修改",!model.busy&&(specs.isNotEmpty()||bulk)){
                    validation=specs.firstOrNull{fieldVisible(it,values)&&it.optBoolean("required")&&values[it.s("key")].isNullOrBlank()}?.let{"请填写${it.s("label")}"}?:""
                    if(validation.isEmpty()){
                        val action=model.current.args.s("action")
                        val direct=operation&&action in listOf("order-mark-unpaid","order-mark-paid","manual-fill")
                        if(direct){
                            val data=obj("values" to obj().apply{specs.forEach{f->put(f.s("key"),values[f.s("key")]?:"")}}).put("action",action)
                            if(bulk){model.runBatch(data.getJSONObject("values"));values.keys.filter{key->specs.any{it.s("key")==key&&it.optBoolean("secret")}}.forEach{values[it]=""}}
                            else model.save("actions/run",data){result->values.keys.filter{key->specs.any{it.s("key")==key&&it.optBoolean("secret")}}.forEach{values[it]=""};model.showResult(result)}
                        }else confirm="save"
                    }
                }}
                if(management&&schema.optBoolean("can_delete")){Spacer(Modifier.width(8.dp));MoreActions(listOf(UiAction("删除此记录",Icons.Outlined.DeleteOutline,true){confirm="delete"}),!model.busy,iconOnly=true)}
            }
        }}
    }
    if(confirm.isNotEmpty()){
        val summary=(if(operation)schema.s("note")+"\n\n"else "")+(if(bulk)"共 ${model.current.args.optJSONArray("targets")?.length()?:0} 条记录，逐条执行。\n"+model.current.args.getJSONArray("targets").let{a->(0 until a.length()).joinToString("\n"){a.getString(it)}}+"\n"else "")+if(confirm=="delete")"删除后无法恢复。" else if(operation)specs.filter{!it.optBoolean("secret")}.joinToString("\n"){f->val value=values[f.s("key")]?:"";"${f.s("label")}：${f.fieldOptions().s(value,value)}"}else"保存后立即生效。"
        ConfirmDialog(if(confirm=="delete")"删除记录？"else if(operation)schema.s("name")else"保存修改？",summary,model.busy,{confirm=""},model.error){
            val data=obj("values" to obj().apply{specs.filter{fieldVisible(it,values)}.forEach{f->val key=f.s("key");put(key,if(f.s("type")=="checkbox")runCatching{JSONArray(values[key])}.getOrDefault(JSONArray())else values[key]?:"")}})
            val endpoint=when {
                operation->{data.put("action",model.current.args.s("action"));"actions/run"}
                management->{data.put("resource",schema.s("resource")).put("id",schema.s("id")).put("revision",schema.s("revision"));if(confirm=="delete")"management/delete"else"management/save"}
                model.current.route=="credentials"->{data.put("kind",schema.s("kind")).put("id",schema.s("id")).put("revision",schema.s("revision"));"credentials/save"}
                model.current.route=="channel"->{data.put("id",schema.s("id")).put("revision",schema.s("revision"));"channels/config/save"}
                else->{data.put("group",model.current.args.s("group"));"settings/save"}
            }
            if(bulk){model.runBatch(data.getJSONObject("values"));values.keys.filter{key->specs.any{it.s("key")==key&&it.optBoolean("secret")}}.forEach{values[it]=""};confirm=""}
            else model.save(endpoint,data){result->values.keys.filter{key->specs.any{it.s("key")==key&&it.optBoolean("secret")}}.forEach{values[it]=""};confirm="";if(operation)model.showResult(result)else{model.announce("已保存");if(management)model.back()else model.load()}}
        }
    }
}

internal fun recordActions(model:AdminModel,row:JSONObject,resource:String):List<UiAction> {
    val actions=when(resource){
        "settlements"->listOf("settle-query" to "查询转账","settle-pay" to "结算转账","settle-complete" to "登记完成","settle-fail" to "登记失败","settle-return" to "失败退回")
        "batches"->listOf("batch-complete" to "登记批次完成")
        "transfers"->buildList{add("transfer-query" to "刷新状态");add("transfer-proof" to "转账凭证");if(row.optInt("status")==3)add("transfer-approve" to "审核并付款");if(row.optInt("status") in listOf(0,3)){add("transfer-complete" to "登记付款完成");add("transfer-fail" to "登记失败并退回")};if(row.optInt("status") in listOf(0,3,4))add("transfer-cancel" to "撤销付款")}
        "sharing"->listOf("sharing-query" to "刷新分账","sharing-submit" to "提交分账","sharing-cancel" to "取消分账","sharing-return" to "回退分账","sharing-amount" to "可分账金额","sharing-edit-money" to "修改金额")
        "receivers"->listOf(if(row.optInt("status")==1)"receiver-disable" to "停用规则"else"receiver-enable" to "启用规则")
        else->emptyList()
    }
    return actions.map{(action,title)->UiAction(title){model.operation(action,title,obj("id" to row.s("id"),"biz_no" to row.s("biz_no"),"batch" to row.s("batch")))}}
}

@Composable internal fun ResultPage(model:AdminModel) {
    val clipboard=LocalClipboardManager.current;val context=LocalContext.current;val result=model.content
    var download by remember{mutableStateOf("")}
    fun openLink(url:String){runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure{model.announce("设备没有可用浏览器")}}
    Page{Group(Modifier.padding(horizontal=16.dp)){
        Icon(if(result.optBoolean("stopped"))Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,null,tint=if(result.optBoolean("stopped"))MaterialTheme.colorScheme.error else primary(),modifier=Modifier.size(36.dp));Text(result.s("message","操作完成"),fontSize=13.sp)
        result.keys().forEach{key->if(key !in listOf("message","results","stopped","auth_expired")){KeyValue(labels[key]?:when(key){"secret"->"新密钥 · 仅显示一次";"url"->"链接";else->key},result.s(key));if(key in listOf("secret","url","jumpurl","proof_url","download_url"))TextButton(onClick={clipboard.setText(AnnotatedString(result.s(key)));model.announce("已复制")}){Text("复制")}}}
        result.rows("results").forEach{item->KeyValue(item.s("id"),item.s("message"));HorizontalDivider()}
        listOf("url","jumpurl","proof_url","download_url").map{result.s(it)}.filter{it.startsWith("https://")||it.startsWith("http://")}.distinct().forEach{url->
            val file=url==result.s("download_url")||url==result.s("proof_url")
            ActionLine(if(file)"下载或查看文件"else"打开链接",icon=Icons.Outlined.OpenInNew){if(file)download=url else openLink(url)}
            NativeQrCode(url)
        }
        if(result.has("secret"))Text("离开页面后不再显示。",fontSize=12.sp,color=muted())
        PrimaryAction("返回"){model.back()}
    }}
    if(download.isNotEmpty())ConfirmDialog("确认下载或打开文件？","确认后将在浏览器中打开文件链接，浏览器可能开始下载。",false,{download=""}){val url=download;download="";openLink(url)}
}

@Composable internal fun StatsPage(model:AdminModel) {
    val data=model.content;var from by remember{mutableStateOf(model.current.args.s("from",java.time.LocalDate.now().minusDays(6).toString()))};var to by remember{mutableStateOf(model.current.args.s("to",java.time.LocalDate.now().toString()))};var uid by remember{mutableStateOf(model.current.args.s("uid"))};var channel by remember{mutableStateOf(model.current.args.s("channel"))}
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            var filters by remember{mutableStateOf(false)}
            Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable{filters=!filters},verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.DateRange,null,Modifier.size(16.dp),tint=muted());Text("$from 至 $to",Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp);Icon(if(filters)Icons.Outlined.ExpandLess else Icons.Outlined.Tune,null,Modifier.size(18.dp),tint=primary())}
            if(filters){Input("开始日期",from,{from=it},"YYYY-MM-DD");Input("结束日期",to,{to=it},"YYYY-MM-DD");Input("商户编号",uid,{uid=it},"全部",KeyboardType.Number);Input("通道编号",channel,{channel=it},"全部",KeyboardType.Number);PrimaryAction("查询统计",!model.loading){model.query(obj("kind" to model.current.args.s("kind"),"from" to from,"to" to to,"uid" to uid,"channel" to channel));filters=false}}
        }
        data.optJSONObject("total")?.let{total->Group(Modifier.padding(horizontal=16.dp)){KeyValue("总记录数",total.s("total"));KeyValue("成功笔数",total.s("paid"));KeyValue("成交金额","¥${money(total.s("amount"))}")}}
        if(data.rows("trend").isNotEmpty())Group(Modifier.padding(horizontal=16.dp)){SectionTitle("每日趋势");TrendChart(data.rows("trend").map{JSONObject(it.toString()).put("orders",it.optInt("total"))},amountNote=data.s("note"),ordersTitle="记录笔数",ordersNote="按创建日期统计全部记录，含未成功记录")}
        data.optJSONObject("breakdown")?.let{breakdown->listOf("type" to "按支付方式","channel" to "按通道","uid" to "按商户").forEach{(key,title)->Group(Modifier.padding(horizontal=16.dp)){SectionTitle(title);breakdown.rows(key).forEach{row->KeyValue("#${row.s("name")} · ${row.s("paid")} 笔","¥${money(row.s("amount"))}")}}}}
    }
}

@Composable internal fun ToolPage(model:AdminModel) {
    val tool=model.current.args.s("tool");val specs=model.content.rows("fields");val context=LocalContext.current;val scope=rememberCoroutineScope()
    val values=remember(tool,model.content.toString()){mutableStateMapOf<String,String>().apply{specs.forEach{put(it.s("key"),it.s("value"))}}}
    var image by remember{mutableStateOf("")};var imageName by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var preview by remember{mutableStateOf<JSONObject?>(null)};var paypwd by remember{mutableStateOf("")};var phrase by remember{mutableStateOf("")};var confirm by remember{mutableStateOf(false)};var converting by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null){converting=true;scope.launch{try{val encoded=withContext(Dispatchers.IO){
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)};require(bounds.outWidth>0&&bounds.outHeight>0){"图片格式无效"};val options=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>1024||bounds.outHeight/inSampleSize>1024)inSampleSize*=2}
        val bitmap=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}?:throw IllegalArgumentException("读取失败");val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle();require(bytes.size<=524288){"PNG 图片超过 512 KB，请选择更小图片"};Base64.encodeToString(bytes,Base64.NO_WRAP)
    };image=encoded;imageName="图片已选，可上传";error=""}catch(e:Exception){error=e.message?:"读取图片失败"}finally{converting=false}}}}
    Page{Group(Modifier.padding(horizontal=16.dp)){
        Text(model.content.s("note"),fontSize=12.sp,lineHeight=19.sp,color=muted())
        specs.forEach{field->FieldEditor(field,values[field.s("key")]?:"",!model.busy){values[field.s("key")]=it;preview=null}}
        if(tool=="logo"){ActionLine(if(converting)"处理图片…"else"选择 Logo 图片",icon=Icons.Outlined.Image,enabled=!converting&&!model.busy){picker.launch("image/*")};if(image.isNotEmpty()){val bitmap=remember(image){val bytes=Base64.decode(image,Base64.NO_WRAP);BitmapFactory.decodeByteArray(bytes,0,bytes.size)};DisposableEffect(bitmap){onDispose{bitmap?.recycle()}};if(bitmap!=null)Image(bitmap.asImageBitmap(),"Logo 预览",Modifier.fillMaxWidth().height(100.dp));Text(imageName,fontSize=12.sp,color=muted())}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        if(tool=="cleanup"&&preview==null)PrimaryAction("预览清理数量",!model.loading){model.query(obj("tool" to tool,"preview" to "1","resource" to values["resource"],"days" to values["days"]))}
        else PrimaryAction(when(tool){"logo"->"上传 Logo";"identity"->"生成授权链接";"plugins"->"同步插件";else->"确认操作"},!model.busy&&!converting&&(tool!="logo"||image.isNotEmpty())){confirm=true}
    }}
    // 预览响应返回的是校验凭据而非表单；所选参数从当前页面参数中保留。
    LaunchedEffect(model.content.toString()){if(model.content.has("proof"))preview=model.content}
    if(preview!=null&&tool=="cleanup")CompactDialog(onDismissRequest={if(!model.busy){preview=null;model.query(obj("tool" to tool));paypwd="";phrase=""}},title={Text("清理预览")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("将删除 ${preview!!.s("count")} 条，截止 ${preview!!.s("cutoff")}",fontSize=13.sp);Input("输入“确认清理”",phrase,{phrase=it});Input("支付密码",paypwd,{paypwd=it},secret=true);model.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={val v=JSONObject(model.current.args.toString()).put("proof",preview!!.s("proof")).put("cutoff",preview!!.s("cutoff")).put("confirm",phrase).put("paypwd",paypwd);model.save("tools/run",obj("tool" to tool,"values" to v)){paypwd="";phrase="";preview=null;model.showResult(it)}},enabled=!model.busy&&phrase=="确认清理"&&paypwd.isNotEmpty()){Text("永久删除")}},dismissButton={TextButton(onClick={preview=null;paypwd="";phrase="";model.query(obj("tool" to tool))},enabled=!model.busy){Text("取消")}})
    if(confirm)ConfirmDialog("确认${model.current.title}？",if(tool=="logo")"将替换平台 Logo。"else"请核对以上参数。",model.busy,{confirm=false},model.error){val v=JSONObject(values.toMap());if(tool=="logo")v.put("image",image);model.save("tools/run",obj("tool" to tool,"values" to v)){confirm=false;image="";model.showResult(it)}}
}

@Composable internal fun NativeQrCode(value:String){
    var bitmap by remember(value){mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(value){bitmap=withContext(Dispatchers.Default){runCatching{
        val bits=com.google.zxing.qrcode.QRCodeWriter().encode(value,com.google.zxing.BarcodeFormat.QR_CODE,480,480,mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",com.google.zxing.EncodeHintType.MARGIN to 2))
        Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888).apply{setPixels(IntArray(480*480){i->if(bits[i%480,i/480])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,480,0,0,480,480)}
    }.getOrNull()}}
    DisposableEffect(bitmap){val current=bitmap;onDispose{current?.recycle()}}
    bitmap?.let{Image(it.asImageBitmap(),"链接二维码",Modifier.fillMaxWidth().heightIn(max=240.dp).aspectRatio(1f))}
}
