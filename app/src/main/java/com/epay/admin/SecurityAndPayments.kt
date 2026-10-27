package com.epay.admin

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable internal fun ExportConfirmation(model:AdminModel) {
    if(model.exportConfirmation)ConfirmDialog("确认导出？","将导出当前筛选条件下的全部记录（最多 10000 条）为 CSV。确认后生成文件，再选择保存或分享。",model.busy,model::dismissExport){model.confirmExport()}
}

@Composable internal fun ChannelTestDialog(model:AdminModel,channel:JSONObject,dismiss:()->Unit,openUrl:((String)->Unit)?=null) {
    val context=LocalContext.current
    var amount by remember(channel.s("id")){mutableStateOf("1.00")}
    var validation by remember{mutableStateOf("")}
    var paymentUrl by remember{mutableStateOf("")}
    fun open(url:String) {
        runCatching {
            if(openUrl!=null)openUrl(url)
            else context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }.onSuccess{dismiss()}.onFailure{validation="无法打开默认浏览器，请安装或启用浏览器后重试"}
    }
    CompactDialog(onDismissRequest={if(!model.busy)dismiss()},title={Text("测试支付")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text(channel.s("name"),fontSize=13.sp)
        HintLine("将创建真实测试订单，点击支付后在手机默认浏览器继续付款。")
        Input("支付金额（元）",amount,{amount=it;validation=""},keyboard=KeyboardType.Decimal,enabled=!model.busy&&paymentUrl.isEmpty())
        if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        model.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    }},confirmButton={TextButton(enabled=!model.busy,onClick={
        if(paymentUrl.isNotEmpty())open(paymentUrl) else {
            val money=amount.toBigDecimalOrNull()
            validation=if(!Regex("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?").matches(amount)||money==null||money.signum()<=0)"请输入大于零、最多两位小数的金额"else""
            if(validation.isEmpty())model.save("channels/test",obj("id" to channel.s("id"),"money" to amount)){result->
                runCatching {
                    val base=platformUrl(model.address)
                    val url=base.resolve(result.s("url"))?:error("支付地址无效")
                    require(url.scheme=="https"&&url.host==base.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()){"支付地址与平台不一致"}
                    paymentUrl=url.toString();open(paymentUrl)
                }.onFailure{validation=it.message?:"支付地址无效"}
            }
        }
    }){Text(if(model.busy)"创建中…"else if(paymentUrl.isEmpty())"支付"else"继续支付")}},dismissButton={TextButton(onClick=dismiss,enabled=!model.busy){Text("取消")}})
}

@Composable internal fun TotpPage(model:AdminModel) {
    val enabled=model.content.optBoolean("totp");val clipboard=LocalClipboardManager.current
    var dialog by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var setup by remember{mutableStateOf<JSONObject?>(null)};var code by remember{mutableStateOf("")}
    var validation by remember{mutableStateOf("")};var deadline by remember{mutableLongStateOf(0L)};var remaining by remember{mutableLongStateOf(0L)}
    LaunchedEffect(deadline){while(deadline>0){remaining=((deadline-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0);if(remaining==0L)break;delay(1000)}}
    Page {
        Group(Modifier.padding(horizontal=16.dp)){
            Icon(Icons.Outlined.Security,null,tint=primary(),modifier=Modifier.size(24.dp))
            KeyValue("动态口令",if(enabled)"已启用"else"未启用")
            HintLine("绑定后，管理员登录需要输入认证软件生成的六位动态口令。绑定、重置或关闭成功后，所有设备需要重新登录。")
            if(setup==null) {
                PrimaryAction(if(enabled)"重置绑定"else"绑定动态口令",!model.busy){password="";validation="";dialog="prepare"}
                if(enabled)TextButton(onClick={password="";validation="";dialog="disable"},enabled=!model.busy){Text("关闭动态口令",color=MaterialTheme.colorScheme.error)}
            }
        }
        setup?.let{pending->Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("使用认证软件添加账号")
            HintLine(if(enabled)"验证新口令成功后才会替换旧绑定。"else"扫描二维码或手动输入下方密钥，再填写新口令。")
            if(remaining>0) {
                NativeQrCode(pending.s("uri"))
                KeyValue("手动密钥",pending.s("secret"))
                TextButton(onClick={clipboard.setText(AnnotatedString(pending.s("secret")));model.announce("密钥已复制")}){Text("复制密钥")}
                Text("请在 ${remaining/60}:${(remaining%60).toString().padStart(2,'0')} 内完成绑定",fontSize=12.sp,color=muted())
                Input("新的动态口令",code,{code=it;validation=""},keyboard=KeyboardType.Number,enabled=!model.busy)
                if(validation.isNotEmpty())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                PrimaryAction("完成绑定",!model.busy){
                    validation=if(!Regex("[0-9]{6}").matches(code))"请输入六位动态口令"else""
                    if(validation.isEmpty())model.save("profile/totp/bind",obj("setup_token" to pending.s("setup_token"),"code" to code)){result->
                        setup=null;code="";deadline=0;model.clearSession();model.announce(result.s("message","绑定成功，请重新登录"))
                    }
                }
            } else HintLine("绑定已过期，请重新生成。原有设置未更改。")
            TextButton(onClick={setup=null;code="";deadline=0;password="";validation="";dialog="prepare"},enabled=!model.busy){Text("重新生成")}
        }}
    }
    if(dialog.isNotEmpty())CompactDialog(onDismissRequest={if(!model.busy){dialog="";password=""}},title={Text(if(dialog=="disable")"关闭动态口令？"else if(enabled)"重置动态口令"else"绑定动态口令")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        HintLine(if(dialog=="disable")"关闭后登录不再需要动态口令，所有设备需要重新登录。"else if(enabled)"先生成新绑定信息；原绑定会保留到新口令验证成功。"else"验证管理员密码后，生成认证软件使用的二维码。")
        Input("管理员密码",password,{password=it;validation=""},secret=true,enabled=!model.busy)
        if(validation.isNotEmpty())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        model.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    }},confirmButton={TextButton(enabled=!model.busy,onClick={
        validation=if(password.isEmpty())"请输入管理员密码"else""
        if(validation.isEmpty()) {
            val action=dialog
            model.save("profile/totp/$action",obj("admin_password" to password)){result->
                password="";dialog=""
                if(action=="disable"){setup=null;code="";deadline=0;model.clearSession();model.announce(result.s("message","已关闭，请重新登录"))}
                else if(result.s("secret").isNotEmpty()&&result.s("uri").startsWith("otpauth://")) {
                    setup=result;code="";remaining=result.optLong("expires_in",600);deadline=SystemClock.elapsedRealtime()+remaining*1000
                } else model.announce("绑定信息已失效，请重新生成")
            }
        }
    }){Text(if(model.busy)"处理中…"else if(dialog=="disable")"确认关闭"else"下一步")}},dismissButton={TextButton(enabled=!model.busy,onClick={dialog="";password=""}){Text("取消")}})
}
