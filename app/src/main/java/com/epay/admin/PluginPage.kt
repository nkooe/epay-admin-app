package com.epay.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readCertificate(stream:InputStream):ByteArray {
    val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=262144){"文件不能超过 256 KB"};out.write(buffer,0,n)}
    val bytes=out.toByteArray();require(bytes.isNotEmpty()){ "文件为空" };return bytes
}

internal fun pluginQrBitmap(qr:JSONObject):Bitmap {
    val value=qr.s("qrcode")
    if(qr.s("qrcode_type")=="image"){
        require(value.startsWith("data:image/")&&value.length<400000){"二维码图片格式无效"}
        val bytes=Base64.decode(value.substringAfter(','),Base64.DEFAULT)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048&&bounds.outHeight in 1..2048){"二维码图片尺寸无效"}
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("二维码图片无法读取")
    }
    require(value.isNotBlank()&&value.length<=4096){"二维码内容无效"}
    val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,600,600)
    return Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply{
        setPixels(IntArray(600*600){i->if(matrix[i%600,i/600])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,600,0,0,600,600)
    }
}

@Composable internal fun PluginPage(model:AdminModel) {
    val schema=model.content;if(schema.length()==0)return
    val identity=model.current.args.toString();val context=LocalContext.current;val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var qr by remember(identity){mutableStateOf<JSONObject?>(null)}
    var bitmap by remember(identity){mutableStateOf<Bitmap?>(null)}
    var ticket by remember(identity){mutableStateOf("")};var polling by remember(identity){mutableStateOf(false)}
    var deadline by remember(identity){mutableLongStateOf(0L)}
    var message by remember(identity){mutableStateOf("")};var validation by remember(identity){mutableStateOf("")}
    var stores by remember(identity){mutableStateOf<JSONObject?>(null)}
    var store by remember(identity){mutableStateOf("")};var terminal by remember(identity){mutableStateOf("")}
    val files=remember(identity){mutableStateMapOf<String,String>()}
    val sizes=remember(identity){mutableStateMapOf<String,Int>()}
    var slot by remember(identity){mutableStateOf("")};var reading by remember(identity){mutableStateOf(false)}
    var confirm by remember(identity){mutableStateOf("")};var exportPng by remember(identity){mutableStateOf<ByteArray?>(null)}
    fun args(action:String)=JSONObject(model.current.args.toString()).put("action",action)
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null){val selectedSlot=slot;reading=true;validation="";scope.launch{
            try{val bytes=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use(::readCertificate)?:error("无法读取文件")};files[selectedSlot]=Base64.encodeToString(bytes,Base64.NO_WRAP);sizes[selectedSlot]=bytes.size}
            catch(e:Exception){if(e is CancellationException)throw e;validation=e.message?:"文件读取失败"}finally{reading=false}
        }}
    }
    val saveQr=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        val bytes=exportPng;exportPng=null
        if(uri!=null&&bytes!=null)scope.launch{
            try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("无法保存文件")};model.announce("二维码已保存，请在对应应用中选择图片扫码")}
            catch(e:Exception){if(e is CancellationException)throw e;validation="二维码保存失败，请重试"}
        }
    }
    LaunchedEffect(qr?.toString()){
        bitmap=null
        qr?.let{value->try{bitmap=withContext(Dispatchers.Default){pluginQrBitmap(value)}}catch(e:Exception){if(e is CancellationException)throw e;validation="二维码显示失败，请重新获取";polling=false}}
    }
    DisposableEffect(bitmap){val currentBitmap=bitmap;onDispose{currentBitmap?.recycle()}}
    LaunchedEffect(ticket,polling){
        if(ticket.isNotEmpty()&&polling)lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
            var failures=0
            while(polling){
                if(SystemClock.elapsedRealtime()>=deadline){polling=false;qr=null;message="授权会话已过期，请重新获取二维码";break}
                delay(2500)
                try{
                    val result=model.pollPlugin(args("qr-poll").put("ticket",ticket));failures=0
                    message=result.s("message");validation=""
                    if(result.s("state")=="done"){polling=false;qr=null;ticket="";model.announce("授权成功");break}
                }catch(e:Exception){
                    if(e is CancellationException)throw e
                    failures++;validation=e.message?:"查询授权结果失败"
                    if(e is ApiFailure&&e.status in 400..499||failures>=3){polling=false;break}
                    delay(2500)
                }
            }
        }
    }
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle(schema.s("name"),schema.s("plugin"))
            if(!schema.optBoolean("available"))HintLine("此插件没有扫码、门店或证书操作；支付参数在通道密钥配置中修改。")
            if(schema.optBoolean("qr_login")){
                ActionLine(if(polling)"停止查询授权结果"else"扫码授权",icon=Icons.Outlined.QrCode,enabled=!model.busy&&!reading){if(polling){polling=false;message="已停止查询；重新开始可获取新二维码"}else confirm="qr-start"}
                bitmap?.let{image->Image(image.asImageBitmap(),"登录授权二维码",Modifier.fillMaxWidth().height(240.dp));ActionLine("保存二维码",icon=Icons.Outlined.FileDownload,enabled=!model.busy){confirm="qr-save"}}
                if(ticket.isNotBlank()&&!polling)ActionLine("继续查询",icon=Icons.Outlined.Refresh,enabled=!model.busy){polling=true;validation=""}
            }
            if(schema.optBoolean("account_login"))ActionLine("同步账号登录",icon=Icons.Outlined.Login,enabled=!model.busy&&!reading){polling=false;confirm="account-login"}
            if(schema.optBoolean("shared_session"))ActionLine("同步共享 AppSession",icon=Icons.Outlined.Sync,enabled=!model.busy&&!reading){confirm="shared-session-sync"}
            if(message.isNotBlank())HintLine(message)
        }
        NativePluginCapabilities(model,schema)
        if(schema.optBoolean("stores"))Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("门店绑定")
            ActionLine("获取门店和终端",icon=Icons.Outlined.Storefront,enabled=!model.busy&&!reading){
                polling=false;model.save("plugins/run",args("stores")){result->stores=result;store="";terminal="";message=result.s("message");validation=""}
            }
            stores?.let{selection->
                val rows=selection.rows();val options=obj().apply{rows.forEach{put(it.s("store_sn"),it.s("store_name"))}}
                if(rows.isNotEmpty()){
                    ChoiceField("门店",store,options,!model.busy){store=it;terminal=""}
                    if(store.isNotBlank())ChoiceField("终端",terminal,obj().apply{rows.filter{it.s("store_sn")==store}.forEach{put(it.s("terminal_sn"),it.s("terminal_name"))}},!model.busy){terminal=it}
                    ActionLine("绑定并同步收款码",icon=Icons.Outlined.Link,enabled=terminal.isNotBlank()&&!model.busy){confirm="store-bind"}
                }
            }
        }
        schema.optJSONObject("certificates")?.let{certs->Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("支付证书",if(certs.s("merchant").isBlank())"请先保存商户号"else"商户号 ${certs.s("merchant")}")
            certs.rows("files").forEach{file->
                val key=file.s("slot")
                ActionLine(file.s("label"),icon=Icons.Outlined.UploadFile,enabled=!model.busy&&!reading&&certs.s("merchant").isNotBlank()){
                    slot=key;filePicker.launch("*/*")
                }
                HintLine(if(files.containsKey(key))"已选择 · ${sizes[key]} 字节"else if(file.optBoolean("configured"))"已配置 · 点击替换"else"未配置 · $key")
            }
            if(files.isNotEmpty()){
                ActionLine("确认上传 ${files.size} 个文件",icon=Icons.Outlined.CloudUpload,enabled=!model.busy&&!reading){polling=false;confirm="cert-upload"}
                ActionLine("清空已选文件",enabled=!model.busy&&!reading){files.clear();sizes.clear()}
            }
            HintLine("支持 PEM 格式，每个文件最多 256 KB。私钥仅上传，不提供查看或下载。")
        }}
        if(reading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=16.dp))
        if(validation.isNotBlank())Text(validation,Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
    if(confirm.isNotBlank()){
        val title=when(confirm){"qr-start"->"开始扫码授权？";"qr-save"->"保存二维码？";"store-bind"->"绑定所选门店？";"cert-upload"->"上传并启用证书？";"shared-session-sync"->"同步共享 AppSession？";else->"同步账号登录？"}
        val detail=when(confirm){
            "qr-start"->"使用已保存的通道配置获取二维码。扫码确认成功后替换该通道登录信息。"
            "qr-save"->"将登录二维码保存到你选择的位置，供对应应用从相册扫码。请在授权完成后删除。"
            "store-bind"->"门店：${store}\n终端：${terminal}\n绑定后使用此门店终端的收款码。"
            "cert-upload"->"商户号：${schema.getJSONObject("certificates").s("merchant")}\n${files.keys.joinToString("\n")}\n校验成功后启用，替换现有同类型文件。"
            "shared-session-sync"->"使用当前配置的星译付账号同步有效 AppSession，并安全保存到当前主通道或子通道。会话内容不会返回 App。"
            else->"使用已保存的账号配置登录，并沿用插件规则同步同账号通道的登录态。"
        }
        ConfirmDialog(title,detail,model.busy,{confirm=""},model.error){
            if(confirm=="qr-save"){
                bitmap?.let{image->exportPng=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();saveQr.launch("channel-${schema.s("id")}-authorization.png")};confirm=""
            }else{
                val action=confirm;val data=args(action)
                if(action=="shared-session-sync")data.put("action","shared-session-sync").put("revision",schema.s("revision"))
                if(action=="store-bind")data.put("ticket",stores?.s("ticket")).put("store_sn",store).put("terminal_sn",terminal)
                if(action=="cert-upload")data.put("revision",schema.getJSONObject("certificates").s("revision")).put("files",obj().apply{files.forEach{(k,v)->put(k,v)}})
                model.save("plugins/run",data){result->
                    confirm="";validation="";message=result.s("message")
                    when(action){
                        "shared-session-sync"->{model.load()}
                        "qr-start"->{qr=result;ticket=result.s("ticket");deadline=SystemClock.elapsedRealtime()+600000;polling=true}
                        "store-bind"->{stores=null;model.announce(message)}
                        "cert-upload"->{files.clear();sizes.clear();model.announce(message);model.load()}
                        else->model.announce(message)
                    }
                }
            }
        }
    }
}
