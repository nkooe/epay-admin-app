package com.epay.admin

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate

internal fun complaintStatus(value:Int)=when(value){0->"待处理";1->"处理中";2->"已结束";else->"未知状态"}
internal fun complaintActionTitle(action:String)=mapOf("reply" to "回复用户","complete" to "完成处理","supplement" to "补充凭证","feedback" to "处理反馈","refund-progress" to "退款审批")[action]?:action
internal fun readComplaintEvidence(stream:InputStream):ByteArray{
    val bytes=stream.readBytesLimited(696320)
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outMimeType in setOf("image/png","image/jpeg")&&bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=20000000){"仅支持有效的 JPG、PNG 图片"}
    return bytes
}
private fun InputStream.readBytesLimited(limit:Int):ByteArray{
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val count=read(buffer);if(count<0)break;require(output.size()+count<=limit){"图片请控制在 680 KB 以内"};output.write(buffer,0,count)}
    return output.toByteArray()
}

@Composable internal fun ComplaintsPage(model:AdminModel){
    val data=model.content;var filters by remember{mutableStateOf(false)};var sync by remember{mutableStateOf(false)}
    var search by remember{mutableStateOf(model.current.args.s("q"))}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{CompactSearch(search,{search=it},"搜索投诉单号、订单号、标题"){model.query(JSONObject(model.current.args.toString()).put("q",search).put("page",1))}}
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("${data.optInt("total")} 条投诉",fontSize=12.sp,color=muted(),modifier=Modifier.weight(1f));MoreActions(listOf(
            UiAction("筛选"){filters=true},UiAction("同步上游投诉"){sync=true},UiAction("接口配置"){model.go("complaint-accounts","投诉接口")}
        ),!model.busy&&!model.loading)}}
        if(data.rows().isEmpty()&&!model.loading)item{EmptyMessage("暂无投诉","可先配置投诉接口，再同步上游投诉记录。")}
        items(data.rows(),key={it.s("id")}){row->Group{
            ActionLine(row.s("title").ifBlank{"投诉 #${row.s("id")}"},"${if(row.s("provider")=="wechat")"微信"else"支付宝"} · ${complaintStatus(row.optInt("status"))}",enabled=!model.busy){model.go("complaint","投诉详情",obj("id" to row.s("id")))}
            ManagementLine("投诉单号",row.s("thirdid"));ManagementLine("商户 / 金额","${row.s("uid")} / ¥${row.s("money")}")
            ManagementLine("创建时间",row.s("created_at"));HintLine(row.s("account_name"))
        }}
        if(data.optBoolean("has_more"))item{TextButton(onClick={model.load(true)},enabled=!model.loading&&!model.busy,modifier=Modifier.fillMaxWidth()){Text("加载更多")}}
    }
    if(filters)ComplaintFilterDialog(model,{filters=false})
    if(sync)ComplaintSyncDialog(model,{sync=false})
}

@Composable private fun ComplaintFilterDialog(model:AdminModel,dismiss:()->Unit){
    val args=model.current.args
    var status by remember{mutableStateOf(args.s("status"))};var account by remember{mutableStateOf(args.s("account_id"))}
    var uid by remember{mutableStateOf(args.s("uid"))};var from by remember{mutableStateOf(args.s("from"))};var to by remember{mutableStateOf(args.s("to"))}
    CompactDialog(onDismissRequest=dismiss,title={Text("筛选投诉")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        ChoiceField("处理状态",status,obj("" to "全部","0" to "待处理","1" to "处理中","2" to "已结束")){status=it}
        ChoiceField("投诉接口",account,obj("" to "全部").apply{model.content.rows("accounts").forEach{put(it.s("id"),it.s("name"))}}){account=it}
        Input("商户 ID",uid,{uid=it},keyboard=KeyboardType.Number)
        Input("起始日期（YYYY-MM-DD）",from,{from=it});Input("结束日期（YYYY-MM-DD）",to,{to=it})
    }},confirmButton={TextButton(onClick={dismiss();model.query(obj("q" to args.s("q"),"status" to status,"account_id" to account,"uid" to uid,"from" to from,"to" to to,"page" to 1))}){Text("应用")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

@Composable private fun ComplaintSyncDialog(model:AdminModel,dismiss:()->Unit){
    var account by remember{mutableStateOf("")};var from by remember{mutableStateOf(LocalDate.now().toString())};var to by remember{mutableStateOf(LocalDate.now().toString())}
    var page by remember{mutableIntStateOf(1)};var message by remember{mutableStateOf("")};var complete by remember{mutableStateOf(false)}
    CompactDialog(onDismissRequest={if(!model.busy)dismiss()},title={Text("同步上游投诉")},text={Column{
        ChoiceField("投诉接口",account,obj().apply{model.content.rows("accounts").filter{it.optInt("enabled")==1}.forEach{put(it.s("id"),it.s("name"))}},!model.busy){account=it;page=1;complete=false;message=""}
        Input("起始日期",from,{from=it;page=1;complete=false},enabled=!model.busy);Input("结束日期",to,{to=it;page=1;complete=false},enabled=!model.busy)
        HintLine("一次同步上游一页，日期跨度最多 31 天。不会自动处理投诉。")
        if(message.isNotBlank())Text(message,fontSize=12.sp)
        model.error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={model.save("complaints/sync",obj("account_id" to account,"from" to from,"to" to to,"page" to page)){result->
        val next=result.optInt("next_page",0);complete=next<1;page=if(next>0)next else page
        message="本页同步 ${result.optInt("processed")} 条"+(if(complete)"，已到最后一页"else"，可继续第 $next 页");model.load()
    }},enabled=!model.busy&&account.isNotBlank()&&!complete){Text(if(page==1)"开始同步"else"同步下一页")}},dismissButton={TextButton(onClick=dismiss,enabled=!model.busy){Text("关闭")}})
}

@Composable internal fun ComplaintPage(model:AdminModel){
    val data=model.content;val record=data.optJSONObject("record")?:return;val detail=data.optJSONObject("detail")?:obj()
    val closed=record.optInt("status")==2;val enabled=data.optBoolean("account_enabled");val blocked=data.rows("actions").any{it.s("state") in setOf("sending","unknown")}
    var action by remember(record.s("id")){mutableStateOf("")};var resolve by remember{mutableStateOf<JSONObject?>(null)}
    var image by remember{mutableStateOf<JSONObject?>(null)}
    val onImage:(JSONObject)->Unit={image=it}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Group{
            Row(verticalAlignment=Alignment.CenterVertically){Text(complaintStatus(record.optInt("status")),fontSize=13.sp,color=primary(),modifier=Modifier.weight(1f));MoreActions(buildList{
                if(enabled)add(UiAction("刷新上游状态"){model.save("complaints/refresh",obj("id" to record.s("id"))){model.announce("上游状态已刷新");model.load()}})
                if(enabled&&!closed&&!blocked){
                    add(UiAction("回复用户"){action="reply"})
                    if(record.s("provider")=="wechat"){
                        add(UiAction("完成处理"){action="complete"})
                        if(detail.s("problem_type")=="REFUND")add(UiAction("退款审批"){action="refund-progress"})
                    }else{add(UiAction("补充凭证"){action="supplement"});add(UiAction("处理反馈"){action="feedback"})}
                }
            },!model.busy)}
            Text(record.s("title"),fontSize=13.sp,fontWeight=FontWeight.Medium)
            ManagementLine("投诉单号",record.s("thirdid"));ManagementLine("金额","¥${record.s("money")}")
            ManagementLine("创建时间",record.s("created_at"));ManagementLine("最近同步",record.s("snapshot_at"))
            SelectionContainer{Text(detail.s("content"),fontSize=13.sp,lineHeight=20.sp)}
            if(detail.s("phone").isNotBlank())ManagementLine("联系电话",detail.s("phone"))
            ComplaintImages(detail.rows("images"),onImage)
            if(!enabled)HintLine("该投诉接口已停用，启用后才可操作。")
            if(blocked)HintLine("有操作正在提交或结果待核实。请先刷新上游状态并核对协商记录。")
        }}
        if(detail.rows("local_orders").isNotEmpty())item{Group{CollapsibleSection("关联订单"){detail.rows("local_orders").forEach{order->ActionLine(order.s("trade_no"),"商户 ${order.s("uid")} · ¥${order.s("money")}",enabled=!model.busy){model.go("order","订单详情",obj("trade_no" to order.s("trade_no")))}}}}}
        item{Text("协商记录",fontSize=12.sp,color=muted())}
        if(detail.rows("history").isEmpty())item{Group{HintLine("暂无协商记录。")}}
        items(detail.rows("history").reversed()){entry->Group{
            Text(entry.s("actor"),fontSize=12.sp,fontWeight=FontWeight.Medium);Text(entry.s("time"),fontSize=11.sp,color=muted())
            SelectionContainer{Text(entry.s("content"),fontSize=13.sp,lineHeight=20.sp)};ComplaintImages(entry.rows("images"),onImage)
        }}
        item{Group{CollapsibleSection("本地操作记录"){data.rows("actions").forEach{entry->
            ManagementLine(complaintActionTitle(entry.s("action")),managementState(entry.s("state")))
            HintLine(entry.s("message"));Text(entry.s("created_at"),fontSize=11.sp,color=muted())
            if(entry.s("state") in setOf("sending","unknown"))TextButton(onClick={resolve=entry},enabled=!model.busy&&enabled){Text("已核实上游结果",fontSize=12.sp)}
            HorizontalDivider(thickness=.5.dp)
        }}}}
    }
    if(action.isNotEmpty())ComplaintActionDialog(model,action,{action=""})
    resolve?.let{entry->CompactDialog(onDismissRequest={resolve=null},title={Text("确认已核实上游结果？")},text={Text("请先核对上游状态及协商记录。此操作仅解除本地重试限制，不表示处理成功；正在提交的操作至少等待 120 秒。")},confirmButton={TextButton(onClick={resolve=null;model.save("complaints/resolve",obj("id" to record.s("id"),"action_id" to entry.s("id"))){model.announce("核实已记录");model.load()}},enabled=!model.busy){Text("已核实")}},dismissButton={TextButton(onClick={resolve=null}){Text("取消")}})}
    image?.let{ComplaintImageDialog(model,record.s("id"),it){image=null}}
}

@Composable private fun ComplaintImages(images:List<JSONObject>,open:(JSONObject)->Unit){
    if(images.isNotEmpty())CollapsibleSection("查看图片（${images.size}）"){images.forEachIndexed{index,image->ActionLine("图片 ${index+1}"){open(image)}}}
}

@Composable private fun ComplaintImageDialog(model:AdminModel,id:String,image:JSONObject,dismiss:()->Unit){
    var bitmap by remember{mutableStateOf<Bitmap?>(null)};var error by remember{mutableStateOf("")};val context=LocalContext.current
    LaunchedEffect(image.toString()){
        if(image.s("kind")=="wechat")try{
            val result=model.managementRead("complaints/image",obj("id" to id,"media" to image.s("id")))
            bitmap=withContext(Dispatchers.Default){val bytes=Base64.decode(result.s("base64"),Base64.DEFAULT);val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth>0&&options.outHeight>0&&options.outWidth.toLong()*options.outHeight<=20000000){"图片尺寸不正确"}
                var sample=1;while(options.outWidth/sample>1600||options.outHeight/sample>1600)sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("无法解码图片")}
        }catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"图片加载失败"}
    }
    CompactDialog(onDismissRequest=dismiss,title={Text("投诉图片")},text={
        if(image.s("kind")=="url")Column{Text("此图片由支付宝提供，点击后使用手机浏览器查看。");if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        else bitmap?.let{Image(it.asImageBitmap(),"投诉凭证",Modifier.fillMaxWidth().heightIn(max=420.dp))}?:Text(error.ifBlank{"正在加载图片…"})
    },confirmButton={if(image.s("kind")=="url")TextButton(onClick={
        val uri=Uri.parse(image.s("url"));val host=uri.host.orEmpty()
        if(uri.scheme=="https"&&listOf("alipay.com","alipayobjects.com","alicdn.com").any{host==it||host.endsWith(".$it")})runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,uri))}.onFailure{error="无法打开浏览器"}
        else error="图片地址不正确"
    }){Text("浏览器查看")}else TextButton(onClick=dismiss){Text("关闭")}})
}

@Composable private fun ComplaintActionDialog(model:AdminModel,action:String,dismiss:()->Unit){
    val record=model.content.optJSONObject("record")?:return
    var content by remember(action){mutableStateOf("")};var code by remember(action){mutableStateOf("04")};var decision by remember(action){mutableStateOf("REJECT")}
    val attachments=remember(action){mutableStateListOf<JSONObject>()};var reading by remember{mutableStateOf(false)};var validation by remember{mutableStateOf("")}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null){reading=true;validation="";scope.launch{
        try{val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use(::readComplaintEvidence)?:error("无法读取图片")}
            reading=false;model.save("complaints/upload",obj("id" to record.s("id"),"base64" to Base64.encodeToString(bytes,Base64.NO_WRAP))){result->attachments.add(result);model.announce("凭证已上传")}
        }catch(e:Exception){if(e is CancellationException)throw e;validation=e.message?:"图片读取失败"}finally{reading=false}
    }}}
    CompactDialog(onDismissRequest={if(!model.busy&&!reading)dismiss()},title={Text(complaintActionTitle(action))},text={Column(Modifier.verticalScroll(rememberScrollState())){
        if(action=="feedback")ChoiceField("处理结果",code,model.content.optJSONObject("feedback_codes")?:obj(),!model.busy){code=it}
        if(action=="refund-progress"){
            ChoiceField("审批结果",decision,obj("REJECT" to "拒绝申请","APPROVE" to "同意申请"),!model.busy){decision=it}
            HintLine("这里只提交投诉退款审批；实际退款仍需进入订单执行退款。")
        }
        Input(if(action=="complete")"处理说明（选填）"else"处理内容",content,{content=it},multi=true,enabled=!model.busy&&!reading)
        attachments.forEachIndexed{index,file->Row(verticalAlignment=Alignment.CenterVertically){Text("凭证 ${index+1}",fontSize=12.sp,modifier=Modifier.weight(1f));TextButton(onClick={attachments.remove(file)},enabled=!model.busy&&!reading){Text("移除",fontSize=12.sp)}}}
        TextButton(onClick={picker.launch("image/*")},enabled=!model.busy&&!reading&&attachments.size<4){Text(if(reading)"读取图片中…"else"添加图片（最多 4 张）",fontSize=12.sp)}
        HintLine("支持 JPG、PNG，单张不超过 680 KB。上传凭证仅供当前投诉使用。")
        if(validation.isNotBlank())Text(validation,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        model.error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={
        validation=if(content.length>1000)"处理内容最多 1000 字"else if(content.isBlank()&&action!="complete"&&!(action=="refund-progress"&&decision=="APPROVE"))"请填写处理内容"else""
        if(validation.isBlank())model.save("complaints/action",obj("id" to record.s("id"),"revision" to record.optInt("revision"),"action" to action,"content" to content,"images" to JSONArray(attachments.map{it.s("id")}),"code" to code,"decision" to decision)){result->dismiss();model.announce(result.s("message"));model.load()}
    },enabled=!model.busy&&!reading){Text("提交")}},dismissButton={TextButton(onClick=dismiss,enabled=!model.busy&&!reading){Text("取消")}})
}

@Composable internal fun ComplaintAccountsPage(model:AdminModel){
    val data=model.content;var edit by remember{mutableStateOf<JSONObject?>(null)}
    Page{
        Group(Modifier.padding(horizontal=12.dp)){ActionLine("新增投诉接口",enabled=!model.busy){edit=obj()};HintLine("选择已有支付宝或微信通道直接绑定，自动复用通道配置，无需重复填写应用、密钥和证书。");HintLine("首次绑定后在后台同步，之后每天北京时间 03:00 自动同步。");TextButton(onClick={model.load()},enabled=!model.busy&&!model.loading){Text("刷新状态")}}
        data.rows("accounts").forEach{account->Group(Modifier.padding(horizontal=12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(account.s("name"),fontSize=13.sp,modifier=Modifier.weight(1f));MoreActions(buildList{
                add(UiAction("编辑"){edit=account})
                add(UiAction("检测连接"){model.save("complaints/check",obj("id" to account.s("id"))){result->model.announce(result.s("message"));model.load()}})
                if(account.optInt("enabled")==1&&account.optJSONObject("sync")?.s("state")!="running")add(UiAction("后台同步"){model.save("complaints/queue",obj("id" to account.s("id"))){result->model.announce(result.s("message"));model.load()}})
                add(UiAction("通道配置"){model.go("channel","通道密钥配置",obj("id" to account.s("channel")))})
                if(account.s("provider")=="wechat"&&account.optInt("enabled")==1)add(UiAction("订阅投诉通知"){model.save("complaints/subscribe",obj("id" to account.s("id"))){model.announce("上游投诉通知订阅已设置");model.load()}})
            },!model.busy)}
            ManagementLine("支付方式",if(account.s("provider")=="wechat")"微信"else"支付宝")
            ManagementLine("通道",account.s("channel_name"));ManagementLine("状态",if(account.optInt("enabled")==1)"已启用"else"已停用")
            account.optJSONObject("sync")?.let{sync->
                ManagementLine("同步状态",sync.s("label","等待同步"))
                ManagementLine("最近成功",sync.s("last_success_at").ifBlank{"尚未同步成功"}+(if(sync.s("last_success_at").isNotBlank())" · ${sync.optInt("last_count")} 条"else""))
                if(sync.s("next_at").isNotBlank())ManagementLine("下次执行",sync.s("next_at"))
                if(sync.s("state") in setOf("running","pending","waiting")&&sync.optInt("processed")>0)HintLine("本轮已同步 ${sync.optInt("processed")} 条")
                if(sync.s("error").isNotBlank())Text("同步原因：${sync.s("error")}",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                if(sync.s("checked_at").isNotBlank())HintLine("连接检测：${sync.s("check_message")}（${sync.s("checked_at")}）")
            }
            if(account.s("credential_source")=="channel")model.content.rows("channels").find{it.s("id")==account.s("channel")}?.s("config_message")?.takeIf{it.isNotBlank()}?.let{HintLine(it)}
        }}
    }
    edit?.let{account->ComplaintAccountDialog(model,account){edit=null}}
}

@Composable private fun ComplaintAccountDialog(model:AdminModel,row:JSONObject,dismiss:()->Unit){
    val existing=row.optInt("id")>0
    var channel by remember{mutableStateOf(row.s("channel"))}
    var enabled by remember{mutableStateOf(!existing||row.optInt("enabled")==1)}
    var validation by remember{mutableStateOf("")}
    var connection by remember{mutableStateOf("")}
    val channels=model.content.rows("channels")
    val selected=channels.find{it.s("id")==channel}
    CompactDialog(onDismissRequest={if(!model.busy)dismiss()},title={Text(if(existing)"编辑投诉接口"else"新增投诉接口")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        if(existing)ManagementLine("支付通道",row.s("channel_name","通道已删除"))else ChoiceField("支付通道",channel,obj().apply{channels.filter{it.optBoolean("bindable")&&!model.content.rows("accounts").any{account->account.s("channel")==it.s("id")}}.forEach{put(it.s("id"),(if(it.s("type")=="wxpay")"微信 · "else"支付宝 · ")+it.s("name")+"（"+it.s("id")+"）")}},!model.busy){channel=it;connection=""}
        HintLine(if(row.s("credential_source")=="legacy")"此接口保留原有凭证配置。"else"自动复用通道配置，名称和支付方式自动识别；通道配置更新后同步生效。")
        if(row.s("credential_source")!="legacy")selected?.s("config_message")?.takeIf{it.isNotBlank()}?.let{HintLine(it)}
        Row{
            TextButton(onClick={model.save("complaints/check",if(existing)obj("id" to row.s("id"))else obj("channel" to channel)){result->connection=result.s("message")}},enabled=!model.busy&&channel.isNotBlank()){Text("检测连接")}
            TextButton(onClick={dismiss();model.go("channel","通道密钥配置",obj("id" to channel))},enabled=!model.busy&&channel.isNotBlank()){Text("通道配置")}
        }
        if(connection.isNotBlank())HintLine(connection)
        Row(verticalAlignment=Alignment.CenterVertically){Text("启用每日自动同步",Modifier.weight(1f),fontSize=13.sp);Switch(enabled,{enabled=it},enabled=!model.busy)}
        if(validation.isNotBlank())Text(validation,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        model.error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={
        validation=if(channel.isBlank())"请选择支付通道"else""
        if(validation.isBlank()){
            val input=obj("id" to row.optInt("id"),"revision" to row.optInt("revision"),"channel" to channel,"enabled" to if(enabled)1 else 0)
            model.save("complaints/account",input){dismiss();model.announce(if(enabled)"投诉接口已保存，后台同步已接管"else"投诉接口已停用");model.load()}
        }
    },enabled=!model.busy){Text("保存")}},dismissButton={TextButton(onClick=dismiss,enabled=!model.busy){Text("取消")}})
}
