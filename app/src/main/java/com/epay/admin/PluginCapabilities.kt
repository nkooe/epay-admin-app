package com.epay.admin

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun heartbeatTime(seconds:Long):String = if(seconds<=0)"尚无记录" else runCatching{
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}.getOrDefault("时间无效")

internal fun monitorPollDelayMs(failures:Int):Long = (5000L shl (failures-1).coerceIn(0,3)).coerceAtMost(30000L)

@Composable internal fun NativePluginCapabilities(model:AdminModel,schema:JSONObject) {
    if(schema.optBoolean("monitor"))MonitorDiagnostics(model,schema)
    if(schema.optBoolean("config_generator"))MonitorConfigGenerator(model,schema)
    if(schema.optBoolean("receipt_shops"))ReceiptShopPicker(model,schema)
    if(schema.optBoolean("automatic_config"))AutomaticApplicationConfig(model,schema)
}

@Composable private fun MonitorDiagnostics(model:AdminModel,schema:JSONObject) {
    val identity=model.current.args.toString()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var merchant by remember(identity){mutableStateOf(if(schema.s("plugin")=="jktongyx")"1000"else"")}
    var selected by remember(identity){mutableStateOf(merchant)}
    var reveal by remember(identity){mutableStateOf(false)}
    var refresh by remember(identity){mutableIntStateOf(0)}
    var state by remember(identity){mutableStateOf<JSONObject?>(null)}
    var failure by remember(identity){mutableStateOf("")}
    LaunchedEffect(identity,selected,reveal,refresh){
        state=null;failure="";var failures=0
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
            while(true){
                var wait=monitorPollDelayMs(failures)
                try{
                    val args=JSONObject(identity).put("reveal",if(reveal)"1"else"0")
                    if(selected.isNotBlank())args.put("merchant_id",selected)
                    state=model.managementRead("plugins/monitor",args);failure="";failures=0;wait=5000L
                }catch(e:Exception){if(e is CancellationException)throw e;state=null;failure=e.message?:"诊断读取失败";failures=(failures+1).coerceAtMost(4);wait=monitorPollDelayMs(failures)}
                delay(wait)
            }
        }
    }
    Group(Modifier.padding(horizontal=16.dp)){
        SectionTitle("监控诊断")
        Input("查询商户号",merchant,{merchant=it},hint=if(schema.s("plugin")=="jktongypc")"留空查看最近上报商户"else"按商户与客户端名称查询",keyboard=KeyboardType.Number,enabled=!model.busy)
        ActionLine("刷新监控状态",icon=Icons.Outlined.Refresh,enabled=!model.busy){selected=merchant.trim();refresh++}
        state?.let{status->
            val label=when{!status.optBoolean("channel_enabled")->"通道已停用";!status.optBoolean("heartbeat_enabled")->"未校验心跳";status.optBoolean("online")->"支付在线";else->"支付离线"}
            Text(label,fontSize=14.sp,color=if(status.optBoolean("online"))primary()else MaterialTheme.colorScheme.error)
            HintLine(status.s("reason"))
            KeyValue("商户",status.s("merchant_id"));KeyValue("客户端",status.s("client_name").ifBlank{"尚未上报"})
            KeyValue("最近心跳",heartbeatTime(status.optLong("last_seen")))
            KeyValue("超时阈值","${status.s("timeout")} 秒")
            KeyValue("查询时间",heartbeatTime(status.optLong("server_time")))
            status.optJSONObject("reported")?.let{reported->
                SectionTitle("客户端上报配置")
                KeyValue("最近上报",heartbeatTime(reported.optLong("last_seen")))
                KeyValue("报告时间",heartbeatTime(reported.optLong("reported_at")))
                val values=reported.optJSONObject("values")?:obj()
                val labels=mapOf("sid" to "个码 SID","payment_link_sid" to "收款链接 SID","payment_link_shop_id" to "收款链接店铺","receipt_sid" to "收款单 SID","receipt_shop_id" to "收款单店铺")
                labels.forEach{(key,label)->KeyValue(label,values.s(key).ifBlank{"未上报"})}
                ActionLine(if(reveal)"隐藏上报 SID"else"显示上报 SID",icon=if(reveal)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,enabled=!model.busy){reveal=!reveal}
                HintLine("上报参数属于此商户；通道手填参数优先生效。")
            }
        }
        if(failure.isNotBlank())Text("读取失败：$failure",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
}

@Composable private fun MonitorConfigGenerator(model:AdminModel,schema:JSONObject) {
    val context=LocalContext.current;val clipboard=LocalClipboardManager.current;val scope=rememberCoroutineScope()
    val identity=schema.s("id")+schema.s("plugin")
    // 商户密钥和编码后的配置仅保留在内存中，不写入保存状态或 API 请求。
    var site by remember(identity){mutableStateOf(schema.s("site"))}
    var merchant by remember(identity){mutableStateOf(schema.s("merchant_id"))}
    var key by remember(identity){mutableStateOf("")}
    var merchants by remember(identity){mutableStateOf<List<JSONObject>>(emptyList())}
    var loadingMerchants by remember(identity){mutableStateOf(true)}
    var loadingKey by remember(identity){mutableStateOf(false)}
    var keyRequest by remember(identity){mutableIntStateOf(0)}
    var encoded by remember(identity){mutableStateOf("")}
    var bitmap by remember(identity){mutableStateOf<Bitmap?>(null)}
    var failure by remember(identity){mutableStateOf("")}
    var export by remember(identity){mutableStateOf<ByteArray?>(null)}
    LaunchedEffect(identity){
        try{merchants=model.managementRead("merchants/monitor-options",obj("limit" to 100)).rows()}
        catch(e:Exception){if(e is CancellationException)throw e;failure="读取商户列表失败，可手动填写商户号"}
        finally{loadingMerchants=false}
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        val bytes=export;export=null
        if(uri!=null&&bytes!=null)scope.launch{
            try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("无法保存图片")}}
            catch(e:Exception){if(e is CancellationException)throw e;failure="保存二维码失败"}
        }
    }
    LaunchedEffect(encoded){
        bitmap=null
        if(encoded.isNotBlank())try{bitmap=withContext(Dispatchers.Default){pluginQrBitmap(obj("qrcode" to encoded))}}
        catch(e:Exception){if(e is CancellationException)throw e;failure="生成二维码失败，请检查配置长度"}
    }
    DisposableEffect(bitmap){val image=bitmap;onDispose{image?.recycle()}}
    Group(Modifier.padding(horizontal=16.dp)){
        SectionTitle(if(schema.s("plugin")=="jktongypc")"PC 监控配置"else"手机监控配置")
        Input("平台地址",site,{site=it;encoded=""})
        val options=obj().apply{merchants.forEach{put(it.s("uid"),"${it.s("username")} · ${it.s("uid")}")}}
        if(options.length()>0)ChoiceField("选择商户",merchant,options,!loadingMerchants){merchant=it;key="";encoded="";keyRequest++}
        Input("商户号",merchant,{merchant=it;key="";encoded="";keyRequest++},keyboard=KeyboardType.Number)
        ActionLine("读取所选商户密钥",icon=Icons.Outlined.Key,enabled=merchant.isNotBlank()&&!model.busy&&!loadingKey){
            failure="";key="";encoded="";loadingKey=true
            val requestedUid=merchant.trim();val request=++keyRequest
            scope.launch{try{
                val received=model.managementRead("merchants/monitor-key",obj("uid" to requestedUid)).s("key")
                if(request==keyRequest&&merchant.trim()==requestedUid){key=received;if(key.isBlank())failure="该商户没有可用的 V1 密钥"}
            }catch(e:Exception){if(e is CancellationException)throw e;if(request==keyRequest)failure=e.message?:"读取商户密钥失败"}
            finally{loadingKey=false}}
        }
        Input("商户 V1 MD5 密钥",key,{key=it;encoded=""},hint="也可手动粘贴 V1 密钥",secret=true,enabled=!loadingKey)
        ActionLine("生成配置和二维码",icon=Icons.Outlined.QrCode){
            failure="";runCatching{monitorConfiguration(site,merchant.trim(),key.trim())}.onSuccess{encoded=it}.onFailure{failure=it.message?:"配置无效"}
        }
        if(encoded.isNotBlank()){
            ActionLine("复制监控配置",icon=Icons.Outlined.ContentCopy){clipboard.setText(AnnotatedString(encoded))}
            bitmap?.let{image->
                Image(image.asImageBitmap(),"监控配置二维码",Modifier.fillMaxWidth().height(240.dp))
                ActionLine("保存配置二维码",icon=Icons.Outlined.FileDownload){export=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();save.launch("monitor-${schema.s("id")}.png")}
            }
            ActionLine("清空生成配置",icon=Icons.Outlined.Clear){encoded="";key=""}
            HintLine("配置和二维码包含商户密钥，请只导入自己的监控客户端。")
        }
        if(failure.isNotBlank())Text(failure,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
}

@Composable private fun ReceiptShopPicker(model:AdminModel,schema:JSONObject) {
    val identity=model.current.args.toString()
    var list by remember(identity){mutableStateOf<JSONObject?>(null)}
    var selected by remember(identity){mutableStateOf("")}
    var confirm by remember(identity){mutableStateOf(false)}
    var clearConfirm by remember(identity){mutableStateOf(false)}
    var sid by remember(identity){mutableStateOf("")}
    fun args(action:String)=JSONObject(model.current.args.toString()).put("action",action)
    Group(Modifier.padding(horizontal=16.dp)){
        SectionTitle("微信收款单店铺与账号")
        Input("收款单 SID（可选）",sid,{sid=it},hint="留空使用已保存或最近上报的 SID",secret=true,enabled=!model.busy)
        ActionLine("获取收款单店铺",icon=Icons.Outlined.Storefront,enabled=!model.busy){
            model.save("plugins/run",args("receipt-shops").put("revision",schema.s("revision")).put("sid",sid.trim())){list=it;selected="";model.announce(it.s("message"));if(sid.isNotBlank())model.load()}
        }
        if(schema.optJSONObject("sid_overrides")?.optBoolean("receipt_sid")==true)ActionLine("清除手填 SID",icon=Icons.Outlined.Clear,enabled=!model.busy){clearConfirm=true}
        list?.let{choices->
            val options=obj().apply{choices.rows().forEach{put(it.s("selection"),"${it.s("shop_name").ifBlank{"店铺"}} · ${it.s("shop_id")} / ${it.s("account_name").ifBlank{it.s("account_id")}}")}}
            if(options.length()>0){
                ChoiceField("店铺 / 账号",selected,options,!model.busy){selected=it}
                ActionLine("绑定收款单店铺",icon=Icons.Outlined.Link,enabled=selected.isNotBlank()&&!model.busy){confirm=true}
            }else HintLine("没有可选的授权店铺")
        }
    }
    if(clearConfirm)ConfirmDialog("确认清除手填 SID？","下次查询将使用最近一次客户端上报的收款单 SID。",model.busy,{clearConfirm=false},model.error){
        model.save("plugins/run",args("receipt-clear-sid").put("revision",schema.s("revision"))){clearConfirm=false;list=null;sid="";model.announce(it.s("message"));model.load()}
    }
    if(confirm)ConfirmDialog("绑定所选收款单店铺？","绑定后切换到收款单模式，使用所选店铺与账号。",model.busy,{confirm=false},model.error){
        model.save("plugins/run",args("receipt-bind").put("ticket",list?.s("ticket")).put("selection",selected)){
            confirm=false;list=null;model.announce(it.s("message"));model.load()
        }
    }
}

@Composable private fun AutomaticApplicationConfig(model:AdminModel,schema:JSONObject) {
    val identity=model.current.args.toString()+schema.s("revision");val context=LocalContext.current
    var url by remember(identity){mutableStateOf("")};var failure by remember(identity){mutableStateOf("")}
    var confirm by remember(identity){mutableStateOf(false)}
    Group(Modifier.padding(horizontal=16.dp)){
        SectionTitle("支付宝自动配置")
        ActionLine("打开支付宝授权",icon=Icons.Outlined.OpenInBrowser,enabled=schema.s("login_url").isNotBlank()&&!model.busy){
            runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(schema.s("login_url"))).addCategory(Intent.CATEGORY_BROWSABLE))}.onFailure{failure="无法打开浏览器"}
        }
        if(schema.s("login_url").isBlank())HintLine("请先在支付参数中保存网关地址")
        Input("应用数据同步地址",url,{url=it},hint="粘贴授权后取得的地址，留空使用已保存的地址",secret=true,enabled=!model.busy)
        ActionLine("获取并导入应用信息",icon=Icons.Outlined.CloudDownload,enabled=!model.busy){confirm=true}
        HintLine("完整获取成功后替换 APPID、应用私钥及返回的 PID，并清除一次性同步地址。")
        if(failure.isNotBlank())Text(failure,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
    if(confirm)ConfirmDialog("导入应用信息？","获取成功后保存到当前通道；获取失败保留原有配置。",model.busy,{confirm=false},model.error){
        model.save("plugins/run",JSONObject(model.current.args.toString()).put("action","app-import").put("revision",schema.s("revision")).put("url",url)){
            confirm=false;url="";model.announce(it.s("message"));model.load()
        }
    }
}
