package com.epay.admin

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var integrityBlocked = false
    private val notificationTradeNo = mutableStateOf<String?>(null)
    private val notificationOrders = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!verifyInstallation()) return
        notificationTradeNo.value = intent.getStringExtra(EXTRA_NOTIFICATION_TRADE_NO)
        notificationOrders.value = intent.getBooleanExtra(EXTRA_NOTIFICATION_ORDERS,false)
        enableEdgeToEdge()
        setContent { AdminTheme { AdminApp(viewModel(),notificationTradeNo.value,notificationOrders.value){notificationTradeNo.value=null;notificationOrders.value=false} } }
    }
    override fun onResume() {
        super.onResume()
        verifyInstallation()
    }
    private fun verifyInstallation(): Boolean {
        if (integrityBlocked) return false
        val problem = AppIntegrity.problem(this) ?: return true
        integrityBlocked = true
        android.app.AlertDialog.Builder(this).setTitle("安全校验失败").setMessage(problem)
            .setCancelable(false).setPositiveButton("退出") { _, _ -> finishAndRemoveTask() }.show()
        return false
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);notificationTradeNo.value=intent.getStringExtra(EXTRA_NOTIFICATION_TRADE_NO);notificationOrders.value=intent.getBooleanExtra(EXTRA_NOTIFICATION_ORDERS,false) }
    companion object { const val EXTRA_NOTIFICATION_TRADE_NO="receipt_trade_no";const val EXTRA_NOTIFICATION_ORDERS="receipt_orders" }
}

private val Green = Color(0xFF078744)
private val Accent = Color(0xFF07C160)
private tailrec fun Context.activity():ComponentActivity? = when(this){
    is ComponentActivity -> this
    is ContextWrapper -> if(baseContext===this)null else baseContext.activity()
    else -> null
}
@Composable fun AdminTheme(content:@Composable ()->Unit) {
    val colors=if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFF75DE9D),onPrimary=Color(0xFF00391D),secondaryContainer=Color(0xFF173C29),onSecondaryContainer=Color(0xFF75DE9D),background=Color(0xFF111111),surface=Color(0xFF1E1E1E),surfaceVariant=Color(0xFF292929),surfaceContainerLow=Color(0xFF191919),surfaceContainer=Color(0xFF222222),surfaceContainerHigh=Color(0xFF292929),surfaceContainerHighest=Color(0xFF333333),onSurface=Color(0xFFECECEC),onSurfaceVariant=Color(0xFFB0B0B0))
    else lightColorScheme(primary=Green,onPrimary=Color.White,secondaryContainer=Color(0xFFDDF4E7),onSecondaryContainer=Green,background=Color(0xFFEDEDED),surface=Color.White,surfaceVariant=Color(0xFFF4F4F4),surfaceContainerLow=Color(0xFFFAFAFA),surfaceContainer=Color(0xFFF7F7F7),surfaceContainerHigh=Color.White,surfaceContainerHighest=Color(0xFFF0F0F0),onSurface=Color(0xFF191919),onSurfaceVariant=Color(0xFF6B6B6B),outlineVariant=Color(0xFFE8E8E8))
    MaterialTheme(colorScheme=colors,typography=AdminTypography,shapes=Shapes(extraSmall=RoundedCornerShape(5.dp),small=RoundedCornerShape(6.dp),medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(10.dp),extraLarge=RoundedCornerShape(12.dp)),content=content)
}
@Composable internal fun muted()=MaterialTheme.colorScheme.onSurfaceVariant
@Composable internal fun primary()=MaterialTheme.colorScheme.primary
private fun orderStatus(row:JSONObject)=when(row.optInt("status")){0->"未支付";1->"已支付";2->"已退款";3->"已冻结";4->"预授权";else->"未知状态"}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AdminApp(model:AdminModel,notificationTradeNo:String?=null,notificationOrders:Boolean=false,onNotificationOpened:()->Unit={}) {
    val drawer=rememberDrawerState(DrawerValue.Closed);val scope=rememberCoroutineScope();val snack=remember { SnackbarHostState() };val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,model.signedIn,model.notificationsEnabled) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_START||event==Lifecycle.Event.ON_RESUME)model.startReceiptPolling()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))model.startReceiptPolling()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.signedIn,model.busy,notificationTradeNo,notificationOrders) {
        if(model.signedIn&&!model.busy&&(notificationOrders||!notificationTradeNo.isNullOrBlank())) {
            if(notificationOrders) model.go("orders","订单",obj("status" to "1"),root=true)
            else model.go("order","订单详情",obj("trade_no" to notificationTradeNo),root=true)
            onNotificationOpened()
        }
    }
    ExportConfirmation(model)
    LaunchedEffect(model.notice){model.notice?.let {snack.showSnackbar(it);model.clearNotice()}}
    LaunchedEffect(model.exportReady){model.exportReady?.let { csv ->
        try { val directory=File(context.cacheDir,"exports").apply{mkdirs()};directory.listFiles()?.forEach{it.delete()};val file=File(directory,"epay-records-${System.currentTimeMillis()}.csv");file.writeText(csv,Charsets.UTF_8)
            val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"导出记录"))
        }catch(_:Exception){model.announce("无法打开分享面板，请检查设备文件应用")};model.clearExport()
    }}
    BackHandler(enabled=drawer.isOpen){scope.launch{drawer.close()}}
    BackHandler(enabled=model.signedIn&&!drawer.isOpen&&(model.canGoBack||model.current.route !in listOf("home","orders","merchants","channels"))){model.back()}
    if(!model.signedIn){Scaffold(snackbarHost={SnackbarHost(snack)}){padding->LoginPage(model,Modifier.padding(padding))};return}
    ModalNavigationDrawer(drawerState=drawer,drawerContent={
        AdminMenuDrawer(model.platform,model.current.route,model.menus,model.busy,drawer.isOpen,
            onHome={scope.launch{drawer.close()};model.go("home","工作台",root=true)},
            onSelect={item->scope.launch{drawer.close()};model.selectMenu(item)},
            onLogout={scope.launch{drawer.close()};model.logout()})
    }){
        Scaffold(containerColor=MaterialTheme.colorScheme.background,
            topBar={CenterAlignedTopAppBar(expandedHeight=48.dp,title={Text(model.platform,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Outlined.Menu,"全部后台菜单",Modifier.size(21.dp))}},actions={IconButton(onClick={model.go("profile","管理员资料")},enabled=!model.busy){Icon(Icons.Outlined.Settings,"管理员个人资料",Modifier.size(21.dp))}},colors=TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor=MaterialTheme.colorScheme.background))},
            bottomBar={Surface(color=MaterialTheme.colorScheme.surface){Column{HorizontalDivider(thickness=.5.dp);Row(Modifier.fillMaxWidth().navigationBarsPadding()){
                listOf(Triple("home","首页",Icons.Outlined.Home),Triple("orders","订单",Icons.Outlined.ReceiptLong),Triple("merchants","商户",Icons.Outlined.PeopleAlt),Triple("channels","通道",Icons.Outlined.AccountTree)).forEach{(route,title,icon)->
                    val selected=model.current.route==route
                    Column(Modifier.weight(1f).heightIn(min=54.dp).selectable(selected=selected,enabled=!model.busy,role=Role.Tab,onClick={model.go(route,if(route=="home")"工作台"else title,root=true)}).padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Icon(icon,null,Modifier.size(21.dp),tint=if(selected)primary()else muted());Text(title,fontSize=10.sp,lineHeight=14.sp,color=if(selected)primary()else muted())
                    }
                }
            }}}},snackbarHost={SnackbarHost(snack)}
        ){padding->
            Column(Modifier.padding(padding).fillMaxSize()){
                if(model.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(model.canGoBack||model.current.route !in listOf("home","orders","merchants","channels"))Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick={if(!model.back())model.go("home","工作台",root=true)},enabled=!model.busy){Icon(Icons.Outlined.ArrowBack,"返回")};Text(model.current.title,fontWeight=FontWeight.Medium,fontSize=13.sp,lineHeight=19.sp)}
                model.error?.let {error-> ErrorBanner(error){model.load()} }
                Box(Modifier.fillMaxSize().widthIn(max=840.dp).align(Alignment.CenterHorizontally)){
                    key(model.current.route,model.current.args.s("resource")) {
                    RefreshContainer(model.refreshing && model.current.route !in listRoutes,model.current.route in refreshableRoutes && model.current.route !in listRoutes && !model.loading && !model.busy,model::refresh) {
                    when(model.current.route){
                        "home"->HomePage(model)
                        "orders","merchants","channels","resource","sessions"->ListPage(model)
                        "order"->OrderPage(model)
                        "order-notifications"->OrderNotificationPage(model)
                        "merchant","merchant-new"->MerchantForm(model)
                        "merchant-policy"->MerchantPolicyPage(model)
                        "tasks"->TasksPage(model)
                        "task-edit"->TaskEditorPage(model)
                        "task-logs"->TaskLogsPage(model)
                        "task-history"->TaskHistoryPage(model)
                        "task-plugin"->TaskPluginPage(model)
                        "complaints"->ComplaintsPage(model)
                        "complaint"->ComplaintPage(model)
                        "complaint-accounts"->ComplaintAccountsPage(model)
                        "channel","settings","management","operation","bulk","credentials"->NativeForm(model)
                        "plugin"->PluginPage(model)
                        "stats"->StatsPage(model)
                        "tool"->ToolPage(model)
                        "result"->ResultPage(model)
                        "profile"->ProfilePage(model)
                        "totp"->TotpPage(model)
                        else->Page{EmptyMessage("该扩展模块尚未接入","平台未提供此扩展模块的原生接口。")}
                    }
                    }
                    }
                    if(model.loading&&model.content.length()==0)Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=2.dp)}
                }
            }
        }
    }
}

@Composable internal fun AdminMenuDrawer(platform:String,currentRoute:String,groups:List<JSONObject>,busy:Boolean,isOpen:Boolean,onHome:()->Unit,onSelect:(JSONObject)->Unit,onLogout:()->Unit) {
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(isOpen) { if(!isOpen)expandedGroup=null }
    ModalDrawerSheet(modifier=Modifier.widthIn(max=276.dp),drawerContainerColor=MaterialTheme.colorScheme.background){
        LazyColumn(modifier=Modifier.fillMaxHeight()){
            item { Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){BrandIcon(34);Column{Text(platform,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium);Text("平台管理中心",color=muted(),fontSize=11.sp,lineHeight=16.sp)}} }
            item { Row(Modifier.fillMaxWidth().background(if(currentRoute=="home")primary().copy(alpha=.07f)else Color.Transparent).clickable(enabled=!busy,onClick=onHome).heightIn(min=CompactRowHeight).padding(horizontal=20.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Home,null,Modifier.size(18.dp),tint=primary());Text("平台首页",Modifier.padding(start=12.dp),fontSize=13.sp,color=primary())} }
            groups.forEach { group->
                val title=group.s("title")
                val expanded=expandedGroup==title
                item(key="group:$title") {
                    Row(Modifier.fillMaxWidth().semantics{stateDescription=if(expanded)"已展开" else "已收起"}
                        .clickable(role=Role.Button,onClickLabel=if(expanded)"收起$title" else "展开$title"){expandedGroup=if(expanded)null else title}
                        .heightIn(min=CompactRowHeight).padding(horizontal=20.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(title,Modifier.weight(1f),fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,color=if(expanded)primary() else MaterialTheme.colorScheme.onSurface)
                        Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,tint=muted(),modifier=Modifier.size(20.dp))
                    }
                }
                if(expanded)items(group.rows("items"),key={"menu:$title:${it.s("title")}"}) { item->
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled=!busy,role=Role.Button){onSelect(item)}
                        .heightIn(min=CompactRowHeight).padding(start=36.dp,end=20.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(item.s("title"),Modifier.weight(1f),fontSize=13.sp,lineHeight=18.sp)
                        Text(if(item.optBoolean("available"))"›" else "网页",fontSize=12.sp,lineHeight=16.sp,color=muted())
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(top=16.dp));TextButton(onClick=onLogout,modifier=Modifier.fillMaxWidth().padding(12.dp),enabled=!busy){Text("退出登录")} }
        }
    }
}

@Composable private fun LoginPage(model:AdminModel,modifier:Modifier=Modifier) {
    var url by rememberSaveable { mutableStateOf(model.address.takeIf{it.startsWith("https://")}?:"") }
    var user by rememberSaveable { mutableStateOf("admin") };var password by remember {mutableStateOf("")};var answer by remember {mutableStateOf("")};var code by remember {mutableStateOf("")}
    LaunchedEffect(model.challenge,model.signedIn){if(model.challenge.isNotEmpty()||model.signedIn)password=""}
    LaunchedEffect(model.captcha?.s("token")){answer=""}
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally){
        BrandIcon(46);Spacer(Modifier.height(18.dp));Text("平台管理",fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold);Text("随时掌握每一笔生意",Modifier.padding(top=8.dp,bottom=32.dp),fontSize=13.sp,lineHeight=19.sp,color=muted())
        if(model.challenge.isEmpty()){
            Input("平台地址",url,{url=it;model.resetLogin()},hint="https://pay.example.com/",keyboard=KeyboardType.Uri,enabled=!model.busy)
            Input("管理员账号",user,{user=it},enabled=!model.busy)
            Input("管理员密码",password,{password=it},secret=true,enabled=!model.busy)
            model.captcha?.let{Input("验证码：${it.s("question")}",answer,{answer=it},keyboard=KeyboardType.Number,enabled=!model.busy)}
        }else{
            Text("动态口令验证",fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium);Text("输入验证器中的 6 位动态口令",Modifier.padding(vertical=16.dp),color=muted());Input("动态口令",code,{code=it.filter(Char::isDigit).take(6)},keyboard=KeyboardType.Number,enabled=!model.busy)
        }
        model.error?.let{Text(it,Modifier.fillMaxWidth().padding(vertical=12.dp),color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=19.sp)}
        Button(onClick={model.login(url,user,password,answer,code)},enabled=!model.busy&&(if(model.challenge.isEmpty())url.isNotBlank()&&user.isNotBlank()&&password.isNotEmpty() else code.length==6),shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().padding(top=24.dp).heightIn(min=50.dp)) {if(model.busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text(if(model.challenge.isEmpty())"登录" else "验证并登录",fontSize=13.sp,lineHeight=19.sp)}
        if(model.challenge.isNotEmpty())TextButton(onClick={model.resetLogin();code=""}){Text("返回重新登录")}
        Text("使用平台原有管理员账号登录\n平台需先部署 App 接口",Modifier.padding(top=30.dp),fontSize=12.sp,color=muted(),textAlign=androidx.compose.ui.text.style.TextAlign.Center,lineHeight=20.sp)
    }
}

@Composable private fun BrandIcon(size:Int=48) { Box(Modifier.size(size.dp).clip(RoundedCornerShape((size/4).dp)).background(Accent),contentAlignment=Alignment.Center){Icon(Icons.Outlined.AccountBalanceWallet,null,tint=Color.White,modifier=Modifier.size((size*.58).dp))} }
@Composable internal fun Input(label:String,value:String,change:(String)->Unit,hint:String="",keyboard:KeyboardType=KeyboardType.Text,enabled:Boolean=true,secret:Boolean=false,multi:Boolean=false) = CompactInput(label,value,change,hint,keyboard,enabled,secret,multi)

@Composable internal fun Page(content:@Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(top=6.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content) }
@Composable internal fun Group(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){CompactControls{Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(7.dp)).padding(horizontal=12.dp,vertical=5.dp),verticalArrangement=Arrangement.spacedBy(2.dp),content=content)}}
@Composable internal fun SectionTitle(title:String,subtitle:String="",action:String="",onAction:()->Unit={}) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium);if(subtitle.isNotEmpty())Text(subtitle,fontSize=12.sp,lineHeight=16.sp,color=muted(),modifier=Modifier.padding(top=2.dp))};if(action.isNotEmpty())TextButton(onClick=onAction){Text(action)}}}
@Composable internal fun EmptyMessage(title:String,body:String="") {Column(Modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.Inbox,null,tint=muted(),modifier=Modifier.size(40.dp));Text(title,Modifier.padding(top=16.dp),fontSize=16.sp,lineHeight=22.sp);if(body.isNotEmpty())Text(body,Modifier.padding(top=8.dp),fontSize=13.sp,color=muted(),lineHeight=21.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
@Composable private fun ErrorBanner(message:String,retry:()->Unit) {Surface(color=MaterialTheme.colorScheme.errorContainer){Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f).padding(vertical=12.dp),fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onErrorContainer);TextButton(onClick=retry){Text("刷新")}}}}
@Composable internal fun KeyValue(label:String,value:String){Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(label,Modifier.weight(.36f),color=muted(),style=MaterialTheme.typography.bodyMedium);Text(value.ifEmpty{"—"},Modifier.weight(.64f),style=MaterialTheme.typography.bodyMedium)} }
@Composable private fun Badge(text:String,positive:Boolean=true){val color=if(positive)primary() else muted();Text(text,color=color,fontSize=12.sp,lineHeight=16.sp,modifier=Modifier.background(color.copy(alpha=.08f),RoundedCornerShape(4.dp)).padding(horizontal=7.dp,vertical=4.dp))}

@Composable private fun HomePage(model:AdminModel){
    val data=model.content
    Page{
        Column(Modifier.padding(horizontal=20.dp)){SectionTitle("工作台","今日经营概览",action="刷新",onAction={model.load()})}
        Group(Modifier.padding(horizontal=16.dp)){
            Text("今日成交金额（元）",fontSize=13.sp,lineHeight=19.sp,color=muted());Text(money(data.s("amount","0")),fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.SansSerif,modifier=Modifier.padding(vertical=4.dp))
            val before=data.s("previous_amount").toBigDecimalOrNull()?:BigDecimal.ZERO;val amount=data.s("amount").toBigDecimalOrNull()?:BigDecimal.ZERO
            val change=if(before.signum()>0)amount.subtract(before).multiply(BigDecimal(100)).divide(before,1,java.math.RoundingMode.HALF_UP) else null
            Text(if(change==null)"昨日 ¥${money(before.toPlainString())}" else "较昨日 ${if(change.signum()>=0)"+" else ""}$change%",fontSize=13.sp,lineHeight=18.sp,color=primary())
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Row(Modifier.fillMaxWidth()){Metric("已支付订单",data.s("paid","0"),Modifier.weight(1f));Metric("支付成功率",data.s("success_rate","0.0")+"%",Modifier.weight(1f));Metric("今日利润",money(data.s("profit","0")),Modifier.weight(1f))}
        }
        Group(Modifier.padding(horizontal=16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text("成交趋势",Modifier.weight(1f),fontWeight=FontWeight.Medium,fontSize=13.sp,lineHeight=19.sp);listOf(7,30).forEach{days->TextButton(onClick={model.query(obj("days" to days))},enabled=!model.loading){Text("${days}天",color=if(model.current.args.optInt("days",7)==days)primary() else muted())}}}
            TrendChart(data.rows("trend"),amountNote=data.s("amount_basis"))
        }
        Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("最近订单",action="全部",onAction={model.go("orders","订单记录",root=true)})
            if(data.rows("recent").isEmpty())EmptyMessage("暂无订单")
            data.rows("recent").forEachIndexed{i,row->OrderRow(row){model.go("order","订单详情",obj("trade_no" to row.s("trade_no")))};if(i<data.rows("recent").lastIndex)HorizontalDivider()}
        }
        Text("更新于 ${data.s("updated_at","—")} · 北京时间",Modifier.fillMaxWidth().padding(horizontal=20.dp),fontSize=11.sp,lineHeight=16.sp,color=muted(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier){Column(modifier){Text(value,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,maxLines=2);Text(label,fontSize=11.sp,lineHeight=16.sp,color=muted(),modifier=Modifier.padding(top=4.dp))}}
@Composable internal fun TrendChart(points:List<JSONObject>,amountNote:String="",ordersTitle:String="订单笔数",ordersNote:String="按创建日期统计全部订单，含未支付订单"){
    var selected by remember(points){mutableIntStateOf(points.lastIndex)}
    var metricName by rememberSaveable{mutableStateOf(TrendMetric.AMOUNT.name)}
    val metric=TrendMetric.valueOf(metricName);val color=primary();val grid=MaterialTheme.colorScheme.outlineVariant
    fun metricTitle(option:TrendMetric)=if(option==TrendMetric.ORDERS)ordersTitle else option.title
    val values=points.map(metric::value);val scale=metric.scale(values)
    val dates=points.map{it.s("day")}
    val scroll=key(dates){rememberScrollState()}
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    val dateStyle=MaterialTheme.typography.labelSmall.copy(fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium)
    val labelWidth=dates.maxOfOrNull{measurer.measure(AnnotatedString(it.takeLast(5)),dateStyle).size.width}?:0
    val dayWidth=with(density){labelWidth.toDp()+8.dp}.coerceAtLeast(40.dp)
    LaunchedEffect(dates,scroll.maxValue){if(scroll.maxValue!=Int.MAX_VALUE)scroll.scrollTo(scroll.maxValue)}
    val sample=points.getOrNull(selected)
    Row(Modifier.selectableGroup().background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)).padding(2.dp)){
        TrendMetric.entries.forEach{option->
            Box(Modifier.testTag("trend-metric-${option.name}")
                .background(if(metric==option)MaterialTheme.colorScheme.surface else Color.Transparent,RoundedCornerShape(4.dp))
                .heightIn(min=36.dp).selectable(selected=metric==option,role=Role.Tab,onClick={metricName=option.name})
                .padding(horizontal=12.dp,vertical=5.dp),contentAlignment=Alignment.Center){
                Text(metricTitle(option),fontSize=12.sp,lineHeight=18.sp,color=if(metric==option)color else muted(),fontWeight=if(metric==option)FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(sample?.s("day")?:"暂无数据",Modifier.weight(1f),fontSize=12.sp,lineHeight=16.sp,color=muted())
        Text(metric.format(sample?.let(metric::value)?:BigDecimal.ZERO),Modifier.testTag("trend-selected-value"),fontSize=13.sp,lineHeight=18.sp,color=color)
    }
    var viewportWidth by remember{mutableIntStateOf(0)}
    val chartWidth=maxOf(with(density){viewportWidth.toDp()},dayWidth*points.size.coerceAtLeast(1))
    Box(Modifier.fillMaxWidth().onSizeChanged{viewportWidth=it.width}){
        Column(Modifier.horizontalScroll(scroll)){
            Column(Modifier.width(chartWidth)){
                Canvas(Modifier.testTag("trend-canvas").fillMaxWidth().height(138.dp).semantics { contentDescription=metricTitle(metric)+"趋势。"+points.joinToString("；"){it.s("day")+"，"+metric.format(metric.value(it))} }.pointerInput(points){detectTapGestures{position->if(points.isNotEmpty())selected=(position.x/(size.width.toFloat()/points.size)).toInt().coerceIn(0,points.lastIndex)}}){
                    val top=8.dp.toPx();val bottom=size.height-8.dp.toPx();val width=size.width
                    val step=width/points.size.coerceAtLeast(1)
                    repeat(3){i->val y=top+(bottom-top)*i/2;drawLine(grid,Offset(0f,y),Offset(width,y),1.dp.toPx())}
                    if(points.isNotEmpty()){
                        fun point(i:Int):Offset = Offset(step*(i+.5f),bottom-scale.ratio(values[i])*(bottom-top))
                        val line=Path().apply{
                            val first=point(0);moveTo(first.x,first.y)
                            // Horizontal tangents at each sample keep the smooth curve within real daily values.
                            for(i in 1 until points.size){val previous=point(i-1);val next=point(i);val middle=(previous.x+next.x)/2f;cubicTo(middle,previous.y,middle,next.y,next.x,next.y)}
                        }
                        val area=Path().apply{addPath(line);lineTo(point(points.lastIndex).x,bottom);lineTo(point(0).x,bottom);close()};drawPath(area,Brush.verticalGradient(listOf(color.copy(alpha=.18f),color.copy(alpha=.01f))))
                        drawPath(line,color,style=Stroke(width=2.5.dp.toPx(),cap=StrokeCap.Round))
                        points.indices.forEach{i->drawCircle(color,2.dp.toPx(),point(i))}
                        if(selected in points.indices){val p=point(selected);drawLine(color.copy(alpha=.25f),Offset(p.x,top),Offset(p.x,bottom),1.dp.toPx());drawCircle(color,4.dp.toPx(),p)}
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=4.dp)){
                    dates.forEachIndexed{i,day->Text(day.takeLast(5),Modifier.weight(1f).heightIn(min=36.dp).selectable(selected=i==selected,role=Role.Button,onClick={selected=i}).padding(top=4.dp),style=dateStyle,color=if(i==selected)color else muted(),fontWeight=if(i==selected)FontWeight.Medium else FontWeight.Normal,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=1,softWrap=false)}
                }
            }
        }
    }
    if(points.isNotEmpty())Text("纵轴 ${metric.format(scale.low)} — ${metric.format(scale.high)}",fontSize=11.sp,lineHeight=16.sp,color=muted())
    val note=if(metric==TrendMetric.ORDERS)ordersNote else amountNote
    if(note.isNotBlank())Text(note,fontSize=11.sp,lineHeight=16.sp,color=muted())
    if(scroll.maxValue in 1 until Int.MAX_VALUE)Text("左右滑动查看全部日期",fontSize=11.sp,lineHeight=16.sp,color=muted())
}

@Composable private fun OrderRow(row:JSONObject,click:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=click).heightIn(min=60.dp).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(32.dp).background(primary().copy(alpha=.09f),RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.ReceiptLong,null,tint=primary(),modifier=Modifier.size(20.dp))}
    Column(Modifier.weight(1f)){Text(row.s("name"),fontSize=13.sp,lineHeight=19.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(row.s("type_name")+" · "+row.s("addtime").takeLast(8),fontSize=12.sp,lineHeight=16.sp,color=muted(),modifier=Modifier.padding(top=2.dp))}
    Column(horizontalAlignment=Alignment.End){Text("¥${money(row.s("money"))}",fontWeight=FontWeight.Medium,fontSize=13.sp,lineHeight=19.sp);Text(orderStatus(row),fontSize=12.sp,lineHeight=16.sp,color=if(row.optInt("status")==1)primary() else muted(),modifier=Modifier.padding(top=2.dp))}
}}

@Composable private fun ListPage(model:AdminModel)=NativeListPage(model)

private fun notificationState(state:String):String=when(state){
    "unrecorded"->"暂无发送记录";"not_required"->"无需商户回调";"sending"->"发送中 / 结果待确认";"succeeded"->"商户已确认"
    "failed"->"发送失败";"unknown"->"接收结果未确认";"exhausted"->"自动重试已结束";else->state
}

@Composable private fun OrderNotificationPage(model:AdminModel){
    val rows=model.content.rows()
    val sources=mapOf("payment" to "支付完成","cron" to "自动重试","cron_exhausted" to "失败重发","admin" to "管理员补发","merchant" to "商户补发","app" to "App 补发")
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            if(rows.isEmpty())HintLine("暂无发送记录，历史订单不会补造明细。")
            rows.forEachIndexed{index,attempt->
                Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(notificationState(attempt.s("status")),fontSize=13.sp,fontWeight=FontWeight.Medium)
                    Text(sources[attempt.s("source")]?:attempt.s("source"),fontSize=12.sp,color=muted())
                }
                KeyValue("发送时间",attempt.s("started_at"))
                if(!attempt.isNull("http_status"))KeyValue("HTTP 状态",attempt.s("http_status"))
                if(!attempt.isNull("duration_ms"))KeyValue("请求耗时","${attempt.s("duration_ms")} ms")
                if(!attempt.isNull("error_summary"))HintLine(attempt.s("error_summary"))
                if(index<rows.lastIndex)HorizontalDivider(Modifier.padding(vertical=8.dp))
            }
            if(model.content.optBoolean("has_more"))TextButton(onClick={model.load(true)},enabled=!model.loading,modifier=Modifier.fillMaxWidth()){Text(if(model.loading)"正在加载…"else"加载更早记录",fontSize=12.sp)}
        }
    }
}

@Composable private fun OrderPage(model:AdminModel){
    val row=model.content;var confirm by remember{mutableStateOf(false)};var deleteConfirm by remember(row.s("trade_no")){mutableStateOf(false)}
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            Badge(orderStatus(row),row.optInt("status")==1)
            Text("¥${money(row.s("money"))}",fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=6.dp))
            Text(row.s("name"),fontSize=13.sp,lineHeight=19.sp)
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            val essential=listOf("trade_no","uid","type_name","channel_name","addtime","endtime")
            essential.filter{row.has(it)}.forEach{KeyValue(labels[it]?:it,row.s(it))}
            CollapsibleSection("更多订单信息"){
                row.keys().forEach{key->if(key !in essential+listOf("money","name","status","pending_operation","suborders","channel_closed","status_only_reset","notification","notify","notifytime","tid"))KeyValue(labels[key]?:key,row.s(key))}
            }
        }
        row.optJSONObject("notification")?.let{notification->Group(Modifier.padding(horizontal=16.dp)){
            SectionTitle("回调通知")
            KeyValue("发送状态",notificationState(notification.s("state")))
            KeyValue("累计发送","${notification.optInt("attempts")} 次")
            if(!notification.isNull("next_retry_at"))KeyValue("下次重试",notification.s("next_retry_at"))
            if(!notification.isNull("last_error"))HintLine(notification.s("last_error"))
            ActionLine("发送记录",icon=Icons.Outlined.History){model.go("order-notifications","回调发送记录",obj("trade_no" to row.s("trade_no")))}
        }}
        row.rows("suborders").takeIf{it.isNotEmpty()}?.let{children->Group(Modifier.padding(horizontal=16.dp)){SectionTitle("合单子订单");children.forEach{sub->KeyValue("子订单号",sub.s("sub_trade_no"));KeyValue("渠道订单号",sub.s("api_trade_no"));KeyValue("金额 / 已退","¥${money(sub.s("money"))} / ¥${money(sub.s("refundmoney"))}");KeyValue("状态",orderStatus(sub));HorizontalDivider(Modifier.padding(vertical=6.dp))}}}
        Group(Modifier.padding(horizontal=16.dp)){
            val args=obj("trade_no" to row.s("trade_no"));val status=row.optInt("status")
            val actions=buildList<UiAction>{
                if(status in listOf(1,2,3))add(UiAction("订单退款",Icons.Outlined.Undo){model.operation("refund","订单退款",JSONObject(args.toString()).put("money",runCatching{BigDecimal(row.s("realmoney")).subtract(BigDecimal(row.s("refundmoney","0").ifEmpty{"0"})).toPlainString()}.getOrDefault("")))})
                if(status in listOf(1,3))add(UiAction(if(status==1)"冻结订单"else"解冻订单",Icons.Outlined.Lock){model.operation(if(status==1)"freeze"else"unfreeze",if(status==1)"冻结订单"else"解冻订单",args)})
                if(status==0)add(UiAction("删除订单",Icons.Outlined.DeleteOutline,true){deleteConfirm=true})
                if(status==0&&!row.optBoolean("channel_closed")){
                    if(!row.optBoolean("status_only_reset"))add(UiAction("手工补单",Icons.Outlined.Edit){model.operation("manual-fill","手工补单",args)})
                }
                if(status==4){add(UiAction("预授权扣款"){model.operation("preauth-pay","预授权扣款",args)});add(UiAction("预授权解冻"){model.operation("preauth-release","预授权解冻",args)})}
                if(status==1&&row.optInt("settle") in listOf(1,3)){
                    if(row.s("plugin") in listOf("alipayd","wxpaynp"))add(UiAction("确认订单结算"){model.operation("order-settle","确认订单结算",args)})
                    if(row.s("plugin")=="alipayrp")add(UiAction("红包转账重试"){model.operation("redpacket-retry","红包转账重试",args)})
                }
                if(status in 1..3&&row.optInt("tid")==0)add(UiAction("重新通知商户",Icons.Outlined.Notifications){confirm=true})
                if(status in listOf(0,1,2)){
                    if(status!=0)add(UiAction("改未完成",Icons.Outlined.Edit){model.changeOrderStatus("order-mark-unpaid",row.s("trade_no"),status)})
                    if(status!=1)add(UiAction("改已完成",Icons.Outlined.CheckCircle){model.changeOrderStatus("order-mark-paid",row.s("trade_no"),status)})
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically){Text("订单管理",Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium);MoreActions(actions,enabled=!model.busy&&!row.has("pending_operation"),label="操作")}
            ActionLine("退款记录",icon=Icons.Outlined.History){model.go("resource","退款记录",obj("resource" to "refunds","q" to row.s("trade_no")))}
            if(row.optBoolean("channel_closed"))HintLine("渠道已关单")
            if(row.optBoolean("status_only_reset"))HintLine("已保留原账务，禁止重复补单入账")
            row.optJSONObject("pending_operation")?.let{pending->HintLine("渠道结果待核实 · ${pending.s("reference")}");ActionLine("登记渠道核实结果"){model.operation("order-reconcile","核实渠道结果",JSONObject(args.toString()).put("reference",pending.s("reference")))}}
        }
    }
    if(confirm)ConfirmDialog("重新发送订单通知？","服务器立即发送，结果以商户回执为准。失败的近期订单按计划重试。",model.busy,{confirm=false},model.error){model.save("orders/notify",obj("trade_no" to row.s("trade_no"))){result->confirm=false;model.announce(result.s("message"));model.load()}}
    if(deleteConfirm)ConfirmDialog("删除订单？","订单号：${row.s("trade_no")}\n删除后不可恢复。",model.busy,{deleteConfirm=false},model.error,confirmLabel="删除",destructive=true){
        model.save("actions/run",obj("action" to "order-delete","values" to obj("trade_no" to row.s("trade_no")))){result->
            deleteConfirm=false;model.announce(result.s("message","订单已删除"));if(!model.back())model.go("orders","订单",root=true)
        }
    }
}

@Composable private fun MerchantForm(model:AdminModel){
    val create=model.current.route=="merchant-new";val row=model.content
    if(!create&&row.length()==0)return
    val fields=remember("merchant-${if(create)"new"else row.s("uid")}",row.toString()){mutableStateMapOf<String,String>().apply{putAll(listOf("username","email","phone","account","url","qq","gid","status","pay","settle").associateWith{key->row.s(key,if(key in listOf("status","pay","settle"))"1" else if(key=="gid")"0" else "")})}}
    var validation by remember{mutableStateOf("")};var confirm by remember{mutableStateOf(false)}
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            if(!create){SectionTitle("商户 #${row.s("uid")}","账户余额 ¥${money(row.s("money"))}");HorizontalDivider(Modifier.padding(vertical=8.dp))}
            listOf("username","email","phone","account","url","qq").forEach{key->Input((labels[key]?:key)+(if(key=="username")" *"else ""),fields[key]?:"",{fields[key]=it},keyboard=when(key){"email"->KeyboardType.Email;"phone"->KeyboardType.Phone;"url"->KeyboardType.Uri;else->KeyboardType.Text},enabled=!model.busy)}
            ChoiceField("用户组",fields["gid"]?:"0",obj().apply{model.groups.forEach{put(it.s("gid"),it.s("name"))}},!model.busy){fields["gid"]=it}
            listOf("status" to "商户正常使用","pay" to "允许收款","settle" to "允许结算").forEach{(key,title)->Column{Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).toggleable(value=fields[key]=="1",enabled=!model.busy,role=Role.Switch,onValueChange={fields[key]=if(it)"1"else"0"}),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp,color=muted());Switch(checked=fields[key]=="1",onCheckedChange=null,enabled=!model.busy,modifier=Modifier.scale(.8f))};HorizontalDivider(thickness=.5.dp)}}
            Text("邮箱和手机号至少填写一项。新增商户的初始余额为 0。",fontSize=12.sp,color=muted(),lineHeight=19.sp)
            if(validation.isNotEmpty())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=18.sp)
            Box(Modifier.padding(top=8.dp)){PrimaryAction(if(create)"新增商户"else"保存资料",!model.busy){validation=when{fields["username"].isNullOrBlank()->"请填写商户名称";fields["email"].isNullOrBlank()&&fields["phone"].isNullOrBlank()->"请填写邮箱或手机号";else->""};if(validation.isEmpty())confirm=true}}
        }

        if(!create)Group(Modifier.padding(horizontal=16.dp)){
            CollapsibleSection("认证与有效期"){
            listOf("endtime" to "用户组到期","cert" to "认证状态","certtype" to "认证类型","certmethod" to "认证方式","certname" to "认证姓名","certcorpname" to "企业名称","certtime" to "认证时间").forEach{(key,title)->KeyValue(title,when(key){"cert"->if(row.optInt(key)==1)"已认证"else"未认证";"certtype"->if(row.optInt("cert")!=1)"—"else if(row.optInt(key)==1)"企业"else"个人";"certmethod"->if(row.optInt("cert")!=1)"—"else when(row.optInt(key)){1->"微信快捷认证";2->"手机号三要素";3->"人工审核";else->"支付宝快捷认证"};else->row.s(key).ifEmpty{"—"}})}
            }
        }
        if(!create)Group(Modifier.padding(horizontal=16.dp)){
            val actions=buildList<UiAction>{
                add(UiAction("子通道管理"){model.go("resource","商户子通道",obj("resource" to "subchannels","uid" to row.s("uid")))})
                add(UiAction("自定义支付参数"){model.go("credentials","商户自定义支付参数",obj("kind" to "merchant","id" to row.s("uid")))})
                add(UiAction("API 密钥"){model.edit("merchant-api","API 密钥",row.s("uid"))})
                listOf("balance" to "余额加扣款","merchant-password" to "重设登录密码","merchant-key" to "重置 API 密钥","merchant-rsa" to "生成 RSA 密钥","merchant-delete" to "删除无业务商户").forEach{(action,title)->add(UiAction(title,destructive=action=="merchant-delete"){model.operation(action,title,obj("uid" to row.s("uid")))})}
            }
            Row(verticalAlignment=Alignment.CenterVertically){Text("商户业务",Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium);MoreActions(actions,!model.busy,label="管理")}
            ActionLine("高级资料与结算设置",icon=Icons.Outlined.Tune){model.edit("merchants","商户高级资料",row.s("uid"))}
            ActionLine("通道策略",icon=Icons.Outlined.Tune){model.go("merchant-policy","商户通道策略",obj("uid" to row.s("uid")))}
            listOf("orders" to "订单记录","records" to "资金明细","settlements" to "结算记录","domains" to "授权域名").forEach{(resource,title)->ActionLine(title){model.go(if(resource=="orders")"orders"else"resource",title,obj("resource" to resource,"uid" to row.s("uid")))}}
        }
    }
    if(confirm)ConfirmDialog(if(create)"确认新增商户？"else"保存商户资料？",fields["username"]?:"",model.busy,{confirm=false},model.error){
        val data=JSONObject(fields.toMap());if(!create)data.put("uid",row.s("uid"))
        model.save(if(create)"merchants/create"else"merchants/update",data){result->confirm=false;model.announce(if(create)"商户已创建，编号 ${result.s("uid")}"else"商户资料已保存");model.back()}
    }
}

@Composable private fun ProfilePage(model:AdminModel){
    val context=LocalContext.current
    val profile=model.content;var dialog by remember{mutableStateOf("")};var nickname by remember(profile.toString()){mutableStateOf(profile.s("nickname"))};var email by remember(profile.toString()){mutableStateOf(profile.s("email"))};var currentPassword by remember{mutableStateOf("")};var newPassword by remember{mutableStateOf("")};var validation by remember{mutableStateOf("")}
    Page{
        Group(Modifier.padding(horizontal=16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){BrandIcon(48);Column(Modifier.weight(1f)){Text(profile.s("nickname","管理员"),fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium);Text("${profile.s("username")} · ${profile.s("role")}",fontSize=13.sp,lineHeight=18.sp,color=muted(),modifier=Modifier.padding(top=6.dp))}}
            HorizontalDivider(Modifier.padding(top=12.dp));KeyValue("平台地址",model.address);KeyValue("联系邮箱",profile.s("email"));KeyValue("动态口令",if(profile.optBoolean("totp"))"已启用"else"未启用")
        }
        Column(Modifier.padding(horizontal=16.dp).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(10.dp))){
            ReceiptNotificationRow(model)
            MenuRow(Icons.Outlined.BatterySaver,"后台运行设置") {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))) }
            }
            MenuRow(Icons.Outlined.Person,"编辑个人资料"){validation="";dialog="profile"}
            MenuRow(Icons.Outlined.Lock,"修改管理员密码"){validation="";dialog="password"}
            MenuRow(Icons.Outlined.Security,"动态口令管理"){model.go("totp","动态口令管理")}
            MenuRow(Icons.Outlined.Password,"设置支付密码"){model.operation("payment-password","设置支付密码")}
            MenuRow(Icons.Outlined.Devices,"登录设备管理"){model.go("sessions","登录设备管理")}
            MenuRow(Icons.Outlined.SwapHoriz,"切换平台"){dialog="switch"}
        }
        Button(onClick={dialog="logout"},enabled=!model.busy,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).heightIn(min=44.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.error)){Text("退出登录")}
        Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",Modifier.fillMaxWidth().padding(top=10.dp),fontSize=12.sp,lineHeight=16.sp,color=muted(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
    if(dialog in listOf("profile","password"))CompactDialog(onDismissRequest={if(!model.busy){dialog="";currentPassword="";newPassword=""}},title={Text(if(dialog=="profile")"个人资料"else"修改密码")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        if(dialog=="profile"){Input("昵称",nickname,{nickname=it},enabled=!model.busy);Input("邮箱",email,{email=it},keyboard=KeyboardType.Email,enabled=!model.busy)}else{Input("原密码",currentPassword,{currentPassword=it},secret=true,enabled=!model.busy);Input("新密码",newPassword,{newPassword=it},secret=true,enabled=!model.busy);Text("至少 12 位，保存后所有设备需要重新登录。",fontSize=12.sp,lineHeight=16.sp,color=muted())}
        if(validation.isNotEmpty())Text(validation,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=18.sp);model.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=18.sp)}
    }},confirmButton={TextButton(onClick={
        validation=if(dialog=="profile"&&nickname.isBlank())"请填写昵称"else if(dialog=="password"&&(newPassword.length<12||newPassword==currentPassword))"新密码至少 12 位，且不能与原密码相同"else""
        if(validation.isEmpty())if(dialog=="profile")model.save("profile/save",obj("nickname" to nickname,"email" to email)){dialog="";model.announce("个人资料已保存");model.load()}else model.save("profile/password",obj("current_password" to currentPassword,"new_password" to newPassword)){currentPassword="";newPassword="";dialog="";model.clearSession();model.announce("密码已更新，请重新登录")}
    },enabled=!model.busy){Text(if(model.busy)"保存中…"else"保存")}},dismissButton={TextButton(onClick={dialog="";currentPassword="";newPassword=""},enabled=!model.busy){Text("取消")}})
    if(dialog in listOf("logout","switch"))ConfirmDialog(if(dialog=="switch")"切换平台？"else"退出登录？","当前设备的登录会话将被撤销。",model.busy,{dialog=""}){dialog="";model.logout()}
}
@Composable private fun ReceiptNotificationRow(model:AdminModel) {
    val context=LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME)resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission=remember(context,resumeTick,model.notificationsEnabled){ReceiptNotificationCenter.permissionGranted(context)}
    val checked=model.notificationsEnabled&&permission
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)model.setReceiptNotifications(true) else model.announce("未允许通知，收款提醒没有开启")
    }
    Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).clickable(enabled=!model.busy) {
        if(checked)model.setReceiptNotifications(false)
        else if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            val activity=context.activity()
            val requested=context.getSharedPreferences("receipt_notifications",android.content.Context.MODE_PRIVATE).getBoolean("permission_requested",false)
            if(requested&&activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)==false){
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }
            }else{
                context.getSharedPreferences("receipt_notifications",android.content.Context.MODE_PRIVATE).edit().putBoolean("permission_requested",true).apply()
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        else if(!permission){
            val settings=if(NotificationManagerCompat.from(context).areNotificationsEnabled())Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID,ReceiptNotificationCenter.CHANNEL) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            runCatching { context.startActivity(settings.putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }
        } else model.setReceiptNotifications(true)
    }.padding(horizontal=14.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(Icons.Outlined.NotificationsActive,null,tint=primary(),modifier=Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start=10.dp)){
            Text("收款通知",fontSize=13.sp,lineHeight=19.sp)
            Text(if(checked)"常驻连接提醒；锁屏时请允许后台运行"else"开启后保持连接并显示常驻通知",fontSize=11.sp,lineHeight=15.sp,color=muted())
        }
        Switch(checked=checked,onCheckedChange=null,enabled=!model.busy,modifier=Modifier.scale(.82f))
    }
    HorizontalDivider(Modifier.padding(start=44.dp))
}
@Composable private fun MenuRow(icon:ImageVector,title:String,click:()->Unit){Column{Row(Modifier.fillMaxWidth().clickable(onClick=click).heightIn(min=CompactRowHeight).padding(horizontal=14.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=primary(),modifier=Modifier.size(20.dp));Text(title,Modifier.weight(1f).padding(start=10.dp),fontSize=13.sp,lineHeight=19.sp);Icon(Icons.Outlined.ChevronRight,null,tint=muted(),modifier=Modifier.size(18.dp))};HorizontalDivider(Modifier.padding(start=44.dp))}}
@Composable internal fun ConfirmDialog(title:String,body:String,busy:Boolean,dismiss:()->Unit,error:String?=null,confirmLabel:String="确认",destructive:Boolean=false,confirm:()->Unit){CompactDialog(onDismissRequest={if(!busy)dismiss()},title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(body,fontSize=13.sp,lineHeight=19.sp);error?.let{Text(it,Modifier.padding(top=8.dp),fontSize=13.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick=confirm,enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=if(destructive)MaterialTheme.colorScheme.error else primary())){Text(if(busy)"处理中…"else confirmLabel)}},dismissButton={TextButton(onClick=dismiss,enabled=!busy){Text("取消")}})}
