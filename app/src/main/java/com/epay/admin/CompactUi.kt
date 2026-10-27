package com.epay.admin

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal val CompactRowHeight=40.dp

/** Avoid Material's extra 48dp reservation around already clickable dense rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompactControls(content:@Composable ()->Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CompactRowHeight,content=content)
}

/** Shared dialog spacing for confirmations, filters and native forms. */
@Composable internal fun CompactDialog(
    onDismissRequest:()->Unit,
    title:@Composable ()->Unit,
    text:@Composable ()->Unit,
    confirmButton:@Composable ()->Unit,
    dismissButton:(@Composable ()->Unit)?=null
) {
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false)) { CompactControls {
        Surface(Modifier.padding(horizontal=24.dp,vertical=16.dp).widthIn(max=360.dp).fillMaxWidth(),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal=16.dp,vertical=12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium){title()}
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f,fill=false)){ProvideTextStyle(MaterialTheme.typography.bodyLarge){text()}}
                Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }}
}

/** Dense rows grow with multiline content and system font scaling. */
@Composable internal fun CompactInput(label:String,value:String,change:(String)->Unit,hint:String="",keyboard:KeyboardType=KeyboardType.Text,enabled:Boolean=true,secret:Boolean=false,multi:Boolean=false) {
    var reveal by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val colors=MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=multi || maxWidth<260.dp || LocalDensity.current.fontScale>1.25f || label.length>15
        val editor:@Composable ()->Unit={
            BasicTextField(value,onValueChange=change,enabled=enabled,singleLine=!multi,minLines=if(multi)2 else 1,maxLines=if(multi)6 else 1,
                textStyle=TextStyle(fontSize=13.sp,lineHeight=19.sp,color=if(enabled)colors.onSurface else colors.onSurfaceVariant),
                cursorBrush=SolidColor(colors.primary),
                visualTransformation=if(secret&&!reveal)PasswordVisualTransformation()else VisualTransformation.None,
                keyboardOptions=KeyboardOptions(keyboardType=if(secret)KeyboardType.Password else keyboard,imeAction=if(multi)ImeAction.Default else ImeAction.Next),
                modifier=Modifier.fillMaxWidth().onFocusChanged{focused=it.isFocused}.semantics{contentDescription=label},
                decorationBox={inner->Box(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).padding(vertical=6.dp),contentAlignment=Alignment.CenterStart){
                    if(value.isEmpty())Text(hint.ifEmpty{if(secret)"请输入"else"未填写"},color=colors.onSurfaceVariant,fontSize=12.sp,lineHeight=19.sp)
                    inner()
                }})
        }
        Column {
            if(stacked)Column(Modifier.padding(vertical=5.dp)){
                Text(label,fontSize=12.sp,lineHeight=18.sp,color=colors.onSurfaceVariant)
                Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){editor()};if(secret)RevealButton(reveal,enabled){reveal=!reveal}}
            }else Row(Modifier.heightIn(min=CompactRowHeight),verticalAlignment=Alignment.CenterVertically){
                Text(label,Modifier.fillMaxWidth(.36f).widthIn(max=124.dp).padding(end=12.dp),fontSize=12.sp,lineHeight=18.sp,color=colors.onSurfaceVariant)
                Box(Modifier.weight(1f)){editor()}
                if(secret)RevealButton(reveal,enabled){reveal=!reveal}
            }
            HorizontalDivider(color=if(focused)colors.primary else colors.outlineVariant,thickness=if(focused)1.dp else .5.dp)
        }
    }
}

@Composable private fun RevealButton(reveal:Boolean,enabled:Boolean,click:()->Unit){CompactControls{IconButton(click,enabled=enabled){Icon(if(reveal)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(reveal)"隐藏内容"else"显示内容",Modifier.size(18.dp),tint=muted())}}}

@Composable internal fun SelectionRow(label:String,value:String,enabled:Boolean=true,click:()->Unit){
    val stacked=LocalDensity.current.fontScale>1.25f || label.length>15
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).clickable(enabled=enabled,role=Role.Button,onClick=click).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
            if(stacked)Column(Modifier.weight(1f)){Text(label,fontSize=12.sp,lineHeight=18.sp,color=muted());Text(value,fontSize=13.sp,lineHeight=19.sp)}
            else {Text(label,Modifier.fillMaxWidth(.36f).widthIn(max=124.dp).padding(end=12.dp),fontSize=12.sp,lineHeight=18.sp,color=muted());Text(value,Modifier.weight(1f),fontSize=13.sp,lineHeight=19.sp)}
            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp),tint=muted())
        }
        HorizontalDivider(thickness=.5.dp)
    }
}

internal data class UiAction(val title:String,val icon:ImageVector=Icons.Outlined.ChevronRight,val destructive:Boolean=false,val run:()->Unit)

/** Anchor to the trigger while keeping long menus inside the visible window. */
private class ActionMenuPosition(private val margin:Int):PopupPositionProvider {
    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
        val maxX=(windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin)
        val maxY=(windowSize.height-popupContentSize.height-margin).coerceAtLeast(margin)
        val x=if(layoutDirection==LayoutDirection.Ltr)anchorBounds.right-popupContentSize.width else anchorBounds.left
        val y=when {
            anchorBounds.bottom+popupContentSize.height<=windowSize.height-margin->anchorBounds.bottom
            anchorBounds.top-popupContentSize.height>=margin->anchorBounds.top-popupContentSize.height
            else->maxY
        }
        return IntOffset(x.coerceIn(margin,maxX),y.coerceIn(margin,maxY))
    }
}

@Composable internal fun MoreActions(actions:List<UiAction>,enabled:Boolean=true,label:String="更多",iconOnly:Boolean=false){
    var open by remember{mutableStateOf(false)}
    LaunchedEffect(enabled){if(!enabled)open=false}
    if(actions.isNotEmpty())CompactControls {
        Box {
            if(iconOnly)IconButton(onClick={open=true},enabled=enabled){Icon(Icons.Outlined.MoreHoriz,label,Modifier.size(20.dp))}
            else TextButton(onClick={open=true},enabled=enabled){Text(label,fontSize=12.sp);Icon(Icons.Outlined.ExpandMore,null,Modifier.size(15.dp))}
            if(open){
                val margin=with(LocalDensity.current){8.dp.roundToPx()}
                val position=remember(margin){ActionMenuPosition(margin)}
                Popup(popupPositionProvider=position,onDismissRequest={open=false},properties=PopupProperties(focusable=true)){
                    Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=6.dp){
                        Column(
                            Modifier.testTag("action-menu")
                                .widthIn(min=120.dp,max=260.dp)
                                .heightIn(max=(LocalConfiguration.current.screenHeightDp.dp-16.dp).coerceAtLeast(48.dp))
                                .width(IntrinsicSize.Max)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical=4.dp)
                        ){
                            actions.forEach{action->Row(
                                Modifier.testTag("menu-item-${action.title}").fillMaxWidth().heightIn(min=36.dp)
                                    .clickable(enabled=enabled,role=Role.Button){open=false;action.run()}
                                    .padding(horizontal=10.dp,vertical=4.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                Row(Modifier.testTag("menu-content-${action.title}").weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    Icon(action.icon,null,Modifier.size(18.dp),tint=(if(action.destructive)MaterialTheme.colorScheme.error else muted()).copy(alpha=if(enabled)1f else .38f))
                                    Text(action.title,Modifier.weight(1f),fontSize=13.sp,lineHeight=18.sp,color=(if(action.destructive)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface).copy(alpha=if(enabled)1f else .38f))
                                }
                            }}
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ActionLine(title:String,subtitle:String="",icon:ImageVector?=null,enabled:Boolean=true,click:()->Unit){
    Column{
        Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).clickable(enabled=enabled,role=Role.Button,onClick=click).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
            if(icon!=null){Icon(icon,null,Modifier.size(18.dp),tint=primary());Spacer(Modifier.width(10.dp))}
            Column(Modifier.weight(1f)){Text(title,fontSize=13.sp,lineHeight=19.sp);if(subtitle.isNotBlank())Text(subtitle,fontSize=11.sp,lineHeight=16.sp,color=muted())}
            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp),tint=muted())
        };HorizontalDivider(thickness=.5.dp)
    }
}

@Composable internal fun HintLine(text:String,modifier:Modifier=Modifier){
    if(text.isNotBlank())Row(modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Icon(Icons.Outlined.Info,null,Modifier.padding(top=1.dp).size(14.dp),tint=muted())
        Text(text,fontSize=11.sp,lineHeight=17.sp,color=muted())
    }
}

@Composable internal fun CollapsibleSection(title:String,content:@Composable ColumnScope.()->Unit){
    var expanded by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().heightIn(min=CompactRowHeight).semantics{stateDescription=if(expanded)"已展开"else"已收起"}.clickable(role=Role.Button){expanded=!expanded}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium)
        Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,Modifier.size(18.dp),tint=muted())
    }
    if(expanded)Column(content=content)
}

@Composable internal fun PrimaryAction(title:String,enabled:Boolean=true,click:()->Unit){
    CompactControls{Button(onClick=click,enabled=enabled,shape=RoundedCornerShape(6.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=8.dp),modifier=Modifier.fillMaxWidth().heightIn(min=CompactRowHeight)){Text(title,fontSize=13.sp,lineHeight=18.sp)}}
}

@Composable internal fun CompactSearch(value:String,change:(String)->Unit,hint:String="搜索名称或编号",search:()->Unit){
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(7.dp)).heightIn(min=44.dp).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(Icons.Outlined.Search,null,Modifier.size(17.dp),tint=muted());Spacer(Modifier.width(8.dp))
        BasicTextField(value,change,singleLine=true,textStyle=TextStyle(fontSize=13.sp,color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(primary()),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),modifier=Modifier.weight(1f).semantics{contentDescription=hint},decorationBox={inner->Box{if(value.isEmpty())Text(hint,fontSize=12.sp,color=muted());inner()}})
        CompactControls{IconButton(onClick=search){Icon(Icons.Outlined.ArrowForward,"搜索",Modifier.size(18.dp),tint=primary())}}
    }
}
