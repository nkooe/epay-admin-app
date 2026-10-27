package com.epay.admin

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Destination(val route: String, val title: String, val args: JSONObject = obj())

class AdminModel private constructor(application: Application, initialSource: Source?, restoreStored: Boolean) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null, true)
    internal constructor(application: Application, source: Source) : this(application, source, false)
    private val vault = SessionVault(application)
    private var source: Source? = initialSource
    var signedIn by mutableStateOf(false); private set
    var notificationsEnabled by mutableStateOf(ReceiptNotificationPrefs.enabled(application)); private set
    var platform by mutableStateOf("易支付"); private set
    var address by mutableStateOf(""); private set
    var current by mutableStateOf(Destination("home", "工作台")); private set
    var content by mutableStateOf(obj()); private set
    var menus by mutableStateOf(emptyList<JSONObject>()); private set
    var groups by mutableStateOf(emptyList<JSONObject>()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var captcha by mutableStateOf<JSONObject?>(null); private set
    var challenge by mutableStateOf(""); private set
    var exportReady by mutableStateOf<String?>(null); private set
    var exportConfirmation by mutableStateOf(false); private set
    private var pageJob: Job? = null
    private var pageGeneration = 0L
    private val history = mutableListOf<Destination>()
    val canGoBack:Boolean get()=history.isNotEmpty()
    private var pendingFingerprint = ""
    private var pendingKey = ""

    init {
        if (restoreStored) vault.read()?.let { stored ->
            runCatching {
                val base = platformUrl(stored.s("base"))
                address = base.toString(); platform = stored.s("platform", "易支付")
                source = NetworkSource.forStoredSession(base, vault, stored)
                signedIn = true
            }.onFailure { vault.clear() }
        }
        if (initialSource != null) signedIn = true
        if (signedIn) start()
    }
    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        error = e.message ?: "操作失败，请重试"
        if (e is ApiFailure && e.status == 401 && signedIn) { stopReceiptPolling();ReceiptNotificationWork.cancel(getApplication());(source as? NetworkSource)?.clear(); signedIn = false; challenge = ""; captcha = null }
    }
    fun clearNotice() { notice = null }
    fun clearExport() { exportReady = null }
    fun requestExport() { if(!busy && current.route in listOf("orders","resource"))exportConfirmation=true }
    fun dismissExport() { exportConfirmation=false }
    fun confirmExport() { if(exportConfirmation){exportConfirmation=false;export()} }
    fun login(url: String, username: String, password: String, answer: String, code: String) {
        if (busy) return
        viewModelScope.launch {
            busy=true;error=null
            try {
                val base=platformUrl(url)
                val network=(source as? NetworkSource)?.takeIf { it.base==base } ?: NetworkSource(base,vault).also { source=it;captcha=null;challenge="" }
                address=base.toString()
                val result=if(challenge.isNotEmpty()) network.call("auth/totp",obj("challenge" to challenge,"code" to code),true)
                else network.call("auth/login",obj("username" to username,"password" to password,"device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}","captcha_token" to (captcha?.s("token")?:""),"captcha_answer" to answer),true)
                when {
                    result.optBoolean("needs_captcha") -> {captcha=result.getJSONObject("captcha");error=result.s("captcha_error").ifEmpty { null }}
                    result.optBoolean("needs_totp") -> {challenge=result.s("challenge");captcha=null}
                    result.has("access_token") -> {network.accept(result);platform=result.s("platform","易支付");signedIn=true;challenge="";captcha=null;start()}
                    else -> throw ApiFailure("登录响应不完整，请更新平台 App 接口")
                }
            }catch(e:Exception){fail(e)}finally{busy=false}
        }
    }
    fun resetLogin() { source=null;challenge="";captcha=null;error=null }
    private fun start() {
        history.clear();current=Destination("home","工作台");content=obj();load()
        viewModelScope.launch {
            try { menus=source!!.call("menu").rows("groups");groups=source!!.call("groups").rows() }catch(e:Exception){if(e is CancellationException)throw e;notice=e.message}
        }
        if(notificationsEnabled)registerReceiptDevice()
    }
    private fun registerReceiptDevice() {
        if(!notificationsEnabled||!ReceiptNotificationCenter.permissionGranted(getApplication()))return
        viewModelScope.launch {
            try {
                if(!ReceiptNotificationPrefs.hasCursor(getApplication(),address)) {
                    val cursor=source?.call("notifications/cursor")
                    ReceiptNotificationPrefs.initializeCursor(getApplication(),address,cursor?.s("latest_id")?.toLongOrNull()?:0L)
                }
                source?.call("notifications/device",obj("device_id" to ReceiptNotificationPrefs.deviceId(getApplication()),"enabled" to 1),true,UUID.randomUUID().toString())
                // 先初始化游标和订阅，再启动后台任务，避免旧事件与首次轮询竞争而被误报为新收款。
                ReceiptNotificationWork.schedule(getApplication())
                startReceiptPolling()
            }
            catch(e:Exception){if(e is CancellationException)throw e}
        }
    }
    fun setReceiptNotifications(enabled:Boolean) {
        if(busy||!signedIn)return
        if (enabled && notificationsEnabled) {
            // 用户可能仍保留通知开关，但已在系统设置中关闭通知权限或通知渠道；权限恢复后重新注册。
            if (ReceiptNotificationCenter.permissionGranted(getApplication())) {
                registerReceiptDevice(); startReceiptPolling()
            }
            return
        }
        if (!enabled && !notificationsEnabled) return
        viewModelScope.launch {
            busy=true
            try {
                val device=ReceiptNotificationPrefs.deviceId(getApplication())
                if(enabled) {
                    val base=address
                    val cursor=source!!.call("notifications/cursor")
                    ReceiptNotificationPrefs.initializeCursor(getApplication(),base,cursor.s("latest_id").toLongOrNull()?:0L)
                    source!!.call("notifications/device",obj("device_id" to device,"enabled" to 1),true,UUID.randomUUID().toString())
                    ReceiptNotificationPrefs.setEnabled(getApplication(),true);notificationsEnabled=true
                    ReceiptNotificationWork.schedule(getApplication());startReceiptPolling();notice="收款通知已开启"
                } else {
                    ReceiptNotificationPrefs.setEnabled(getApplication(),false);notificationsEnabled=false
                    stopReceiptPolling();ReceiptNotificationWork.cancel(getApplication())
                    source?.call("notifications/device",obj("device_id" to device,"enabled" to 0),true,UUID.randomUUID().toString())
                    notice="收款通知已关闭"
                }
            } catch(e:Exception) { fail(e) }
            finally {busy=false}
        }
    }
    fun startReceiptPolling() {
        notificationsEnabled=ReceiptNotificationPrefs.enabled(getApplication())
        if(!signedIn||!notificationsEnabled||source !is NetworkSource||!ReceiptNotificationCenter.permissionGranted(getApplication())||!ReceiptNotificationPrefs.hasCursor(getApplication(),address))return
        try { ReceiptConnectionService.start(getApplication()) }
        catch (_: IllegalStateException) { notice="请回到 App 开启收款常驻连接" }
        catch (_: SecurityException) { notice="系统未允许常驻连接，请检查后台运行设置" }
    }
    fun stopReceiptPolling() { ReceiptConnectionService.stop(getApplication()) }
    fun go(route:String,title:String,args:JSONObject=obj(),root:Boolean=false) {
        if (busy) return
        exportConfirmation=false
        if(root)history.clear() else history.add(current)
        current=Destination(route,title,args);content=obj();load()
    }
    fun back():Boolean {
        if(busy)return true
        exportConfirmation=false
        if(history.isEmpty())return false
        current=history.removeAt(history.lastIndex);content=obj();load();return true
    }
    fun selectMenu(item:JSONObject) {
        val route=item.s("route");val title=item.s("title")
        when {
            !item.optBoolean("available",true) -> go("unavailable",title)
            route.startsWith("export:") -> { if(route=="export:orders")go("orders","订单记录",obj("export_hint" to true),true) else go("resource","付款记录",obj("resource" to "transfers","export_hint" to true));requestExport() }
            route.startsWith("resource:") -> go("resource",title,obj("resource" to route.substringAfter(':')))
            route.startsWith("setting:") -> go("settings",title,obj("group" to route.substringAfter(':')))
            route.startsWith("action:") -> operation(route.substringAfter(':'),title)
            route.startsWith("stats:") -> go("stats",title,obj("kind" to route.substringAfter(':')))
            route.startsWith("tool:") -> go("tool",title,obj("tool" to route.substringAfter(':')))
            else -> go(route,title,root=route in listOf("home","orders","merchants","channels"))
        }
    }
    private fun request(destination:Destination=current):Pair<String,JSONObject> = when(destination.route) {
        "home" -> "dashboard" to destination.args
        "order" -> "orders/detail" to destination.args
        "order-notifications" -> "orders/notify-history" to destination.args
        "merchant" -> "merchants/detail" to destination.args
        "merchant-policy" -> "merchants/policy" to destination.args
        "task-edit" -> "tasks/editor" to destination.args
        "task-history" -> "tasks/history" to destination.args
        "task-logs" -> "tasks/logs" to destination.args
        "task-plugin" -> "tasks/plugin" to destination.args
        "complaint" -> "complaints/detail" to destination.args
        "complaint-accounts" -> "complaints/schema" to destination.args
        "channel" -> "channels/config" to destination.args
        "plugin" -> "plugins/schema" to destination.args
        "resource" -> "resources" to destination.args
        "management" -> "management/schema" to destination.args
        "credentials" -> "credentials/schema" to destination.args
        "operation","bulk" -> "actions/schema" to destination.args
        "stats" -> "statistics" to destination.args
        "tool" -> "tools/schema" to destination.args
        "totp" -> "profile" to destination.args
        else -> destination.route to destination.args
    }
    fun query(args:JSONObject) { current=current.copy(args=args);load() }
    fun operation(action:String,title:String,args:JSONObject=obj()) { go("operation",title,JSONObject(args.toString()).put("action",action)) }
    fun changeOrderStatus(action:String,tradeNo:String,status:Int) {
        if(action !in listOf("order-mark-unpaid","order-mark-paid")||status !in listOf(0,1,2)||tradeNo.isBlank())return
        val target=if(action=="order-mark-paid")1 else 0
        if(status==target)return
        save("actions/run",obj("action" to action,"values" to obj("trade_no" to tradeNo,"expected_status" to status.toString()))) { result ->
            notice=result.s("message","订单状态已更新")
            load()
        }
    }
    internal suspend fun pollPlugin(data:JSONObject):JSONObject {
        try { return source!!.call("plugins/run",data,true,UUID.randomUUID().toString()) }
        catch(e:Exception){if(e is ApiFailure&&e.status==401)fail(e);throw e}
    }
    internal suspend fun managementRead(route:String,data:JSONObject):JSONObject {
        require(route in setOf("complaints/image","plugins/monitor","merchants/monitor-options","merchants/monitor-key"))
        try{return source!!.call(route,data)}
        catch(e:Exception){if(e is ApiFailure&&e.status==401)fail(e);throw e}
    }
    fun edit(resource:String,title:String,id:String="") { go("management",title,obj("resource" to resource,"id" to id,"defaults" to current.args)) }
    fun showResult(result:JSONObject) { cancelPage();history.add(current);current=Destination("result","操作结果");content=result;error=null }
    private fun cancelPage() { pageGeneration++;pageJob?.cancel();loading=false;refreshing=false }
    fun refresh() {
        if (busy || loading || current.route !in refreshableRoutes) return
        if (current.route in listRoutes || current.route=="complaints") current=current.copy(args=JSONObject(current.args.toString()).put("page",1))
        loadPage(more=false,refresh=true)
    }
    fun load(more:Boolean=false) = loadPage(more,refresh=false)
    private fun loadPage(more:Boolean,refresh:Boolean) {
        val generation=++pageGeneration
        pageJob?.cancel()
        refreshing=false
        if(current.route in listOf("merchant-new","unavailable","result")){loading=false;error=null;return}
        val destination=current
        val previousRows=content.rows()
        val nextPage=content.optInt("page",1)+1
        val nextCursor=content.optLong("next_cursor")
        loading=true;refreshing=refresh;error=null
        pageJob=viewModelScope.launch {
            try {
                val (route,args)=request(destination)
                val query=JSONObject(args.toString())
                if(more){if(destination.route in setOf("order-notifications","task-history"))query.put("before",nextCursor)else query.put("page",nextPage)}
                val result=if(destination.route=="bulk"&&query.s("action")=="order-notify")obj("name" to "批量通知商户","note" to "服务器立即发送并记录商户回执，失败的近期订单按计划重试。","fields" to JSONArray())else source!!.call(route,query)
                if(generation==pageGeneration){
                    if(more)result.put("rows",JSONArray(previousRows+result.rows()))
                    content=result
                    if(destination.route=="profile") platform=result.s("platform",platform)
                }
            }catch(e:Exception){if(e is CancellationException)throw e;if(generation==pageGeneration)fail(e)}
            finally{if(generation==pageGeneration){loading=false;refreshing=false}}
        }
    }
    fun save(route:String,data:JSONObject,onSuccess:(JSONObject)->Unit={ notice="已保存";load() }) {
        if(busy)return
        viewModelScope.launch {
            busy=true;error=null
            val fingerprint=route+data.toString()
            if(fingerprint!=pendingFingerprint){pendingFingerprint=fingerprint;pendingKey=UUID.randomUUID().toString()}
            try {
                val result=source!!.call(route,data,true,pendingKey)
                pendingFingerprint="";pendingKey="";busy=false;onSuccess(result)
            }catch(e:Exception){ if(e is ApiFailure&&e.status in 400..499&&e.status!=409){pendingFingerprint="";pendingKey=""};fail(e) }
            finally{busy=false}
        }
    }
    fun runBatch(values:JSONObject) {
        if(busy)return
        val args=JSONObject(current.args.toString());val targets=args.optJSONArray("targets")?:return
        if(targets.length() !in 1..50){error="每次请选择 1 至 50 条";return}
        viewModelScope.launch{
            busy=true;error=null
            try{
                val result=executeBatch(source!!,args.s("action"),args.s("target_key"),(0 until targets.length()).map{targets.getString(it)},values)
                busy=false
                if(result.optBoolean("auth_expired")){
                    clearSession();error="会话已失效，批量已停止。重新登录后请核对操作记录与业务状态。"
                }else showResult(result)
            }finally{busy=false}
        }
    }
    fun logout() {
        if(busy)return
        viewModelScope.launch {
            busy=true
            try {
                if(notificationsEnabled)runCatching { source?.call("notifications/device",obj("device_id" to ReceiptNotificationPrefs.deviceId(getApplication()),"enabled" to 0),true,UUID.randomUUID().toString()) }
                ReceiptNotificationPrefs.setEnabled(getApplication(),false);notificationsEnabled=false;stopReceiptPolling();ReceiptNotificationWork.cancel(getApplication())
                source?.call("auth/logout",post=true)
            }catch(e:Exception){ if(e is CancellationException)throw e; notice="本机已退出；网络异常时，可从网页撤销旧会话" }
            finally { clearSession();busy=false }
        }
    }
    fun clearSession() { cancelPage();stopReceiptPolling();ReceiptNotificationWork.cancel(getApplication());(source as? NetworkSource)?.clear();vault.clear();ReceiptNotificationCenter.reset(getApplication());source=null;signedIn=false;menus=emptyList();groups=emptyList();history.clear();error=null;challenge="";captcha=null;content=obj();exportConfirmation=false;exportReady=null;pendingFingerprint="";pendingKey="" }
    fun announce(message:String) {notice=message}
    private fun export() {
        if(busy)return
        viewModelScope.launch {
            busy=true;error=null
            try {
                val (route, args)=request()
                require(route in listOf("orders","resources"))
                val query=JSONObject(args.toString()).put("limit",100)
                val rows=mutableListOf<JSONObject>();var page=1;var more:Boolean
                do { val result=source!!.call(route,query.put("page",page));rows.addAll(result.rows());more=result.optBoolean("has_more");page++ }while(more&&page<=100)
                if(rows.isEmpty())throw ApiFailure("当前筛选条件没有可导出的记录")
                if(more)throw ApiFailure("记录超过 10000 条，请缩小日期范围后导出")
                val fields=rows.first().keys().asSequence().toList()
                val csv="\uFEFF"+fields.joinToString(","){csvCell(labels[it]?:it)}+"\r\n"+rows.joinToString("\r\n"){row->fields.joinToString(","){csvCell(row.s(it))}}
                exportReady=csv;notice="已导出 ${rows.size} 条记录"
            }catch(e:Exception){fail(e)}finally{busy=false}
        }
    }
}

val labels=mapOf("subchannel" to "子通道编号","sub_trade_no" to "子订单号","combine" to "是否合单","keytype" to "API 签名模式","publickey" to "RSA 公钥","reference" to "业务编号","state" to "处理状态","request_id" to "请求编号","created" to "记录时间","detail" to "操作资料","allmoney" to "合计金额","count" to "笔数","costmoney" to "扣款金额","pay_order_no" to "渠道付款号","result" to "处理结果","proof_url" to "凭证链接","download_url" to "下载链接","jumpurl" to "授权链接","balance" to "可用余额","trade_no" to "平台订单号","out_trade_no" to "商户订单号","api_trade_no" to "支付订单号","uid" to "商户编号","name" to "名称","username" to "商户名称","merchant_name" to "商户名称","money" to "金额","realmoney" to "实付金额","getmoney" to "入账金额","profitmoney" to "利润","refundmoney" to "已退金额","addtime" to "创建时间","endtime" to "完成时间","status" to "状态值","notify" to "通知状态值","channel" to "通道编号","channel_name" to "支付通道","type_name" to "支付方式","email" to "邮箱","phone" to "手机号","gid" to "用户组编号","group_name" to "用户组","account" to "结算账号","url" to "网站地址","qq" to "QQ","settle_id" to "结算方式编号","pay" to "收款开关","settle" to "结算开关","id" to "编号","type" to "类型","content" to "内容","date" to "时间","ip" to "IP 地址","buyer" to "支付用户","orders" to "订单数","paid" to "已支付笔数","amount" to "成交金额","last_time" to "最近支付时间","oldmoney" to "变动前余额","newmoney" to "变动后余额","remark" to "备注","refund_no" to "退款单号","biz_no" to "付款单号","rate" to "费率","price" to "价格","expire" to "有效期","sort" to "排序","device" to "设备","showname" to "显示名称","author" to "作者","types" to "支持方式","plugin" to "插件","code" to "邀请码","domain" to "授权域名","kind" to "模式","last_seen" to "最近活动时间")
