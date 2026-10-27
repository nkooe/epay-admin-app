package com.epay.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable internal fun MerchantApiPage(model:AdminModel) {
    if(model.content.length()==0)return
    val info=model.content.optJSONObject("api_info")
    Page{MerchantApiInfo(model,info)}
}

@Composable internal fun MerchantApiInfo(model:AdminModel,info:JSONObject?) {
    if(info==null){
        Group(Modifier.padding(horizontal=16.dp)){
            HintLine("暂未读取到商户密钥信息，请更新平台接口后重试。")
            TextButton(onClick={model.load()},enabled=!model.loading){Text("重新读取",fontSize=12.sp)}
        }
        return
    }
    val clipboard=LocalClipboardManager.current
    var view by remember(info.s("uid")){mutableStateOf(if(info.s("keytype")=="1")"rsa"else"md5")}
    fun copy(label:String,value:String){clipboard.setText(AnnotatedString(value));model.announce("${label}已复制")}
    Group(Modifier.padding(horizontal=16.dp)){
        ChoiceField("密钥类型",view,obj("md5" to "MD5 密钥","rsa" to "RSA 密钥")){view=it}
    }
    if(view=="md5")Group(Modifier.padding(horizontal=16.dp)){
        ApiCopyValue("商户 MD5 密钥",info.s("md5_key"),empty="尚未配置"){copy("MD5 密钥",info.s("md5_key"))}
    }else Group(Modifier.padding(horizontal=16.dp)){
        ApiCopyValue("平台公钥",info.s("platform_public_key"),multiline=true,empty="尚未配置"){copy("平台公钥",info.s("platform_public_key"))}
        HorizontalDivider(Modifier.padding(top=6.dp),thickness=.5.dp)
        ApiCopyValue("商户公钥",info.s("merchant_public_key"),multiline=true,empty="尚未生成"){copy("商户公钥",info.s("merchant_public_key"))}
        HintLine("点击公钥可展开；复制包含完整内容。")
    }
    Group(Modifier.padding(horizontal=16.dp)){
        val address=info.s("apiurl").ifBlank{model.address}
        ApiCopyValue("接口地址",address){copy("接口地址",address)}
        HorizontalDivider(thickness=.5.dp)
        ApiCopyValue("商户 ID",info.s("uid")){copy("商户 ID",info.s("uid"))}
    }
}

@Composable private fun ApiCopyValue(label:String,value:String,multiline:Boolean=false,empty:String="未配置",copy:()->Unit) {
    var expanded by remember(label,value){mutableStateOf(false)}
    Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp,color=muted())
            TextButton(onClick=copy,enabled=value.isNotBlank(),contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(44.dp).semantics{contentDescription="复制$label"}){Text("复制",fontSize=12.sp)}
        }
        if(value.isBlank())Text(empty,fontSize=12.sp,lineHeight=18.sp,color=muted())
        else if(multiline)Text(value,
            Modifier.fillMaxWidth().semantics{stateDescription=if(expanded)"已展开"else"已收起"}.clickable{expanded=!expanded},
            fontSize=12.sp,lineHeight=17.sp,fontFamily=FontFamily.Monospace,maxLines=if(expanded)Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
        else SelectionContainer{Text(value,fontSize=12.sp,lineHeight=18.sp,fontFamily=FontFamily.Monospace)}
    }
}
