package com.codex.remote.ui

import android.content.Intent
import android.graphics.*
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codex.remote.R
import com.codex.remote.data.local.PhoneControl
import com.codex.remote.data.local.fileRevision
import com.codex.remote.data.store.ApiProvider
import com.codex.remote.data.store.ApiStore
import com.codex.remote.data.api.OpenAiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

internal fun phoneModelIdentity(provider: ApiProvider, model: String) = fileRevision(
    listOf(provider.id,provider.baseUrl,provider.protocol.name,provider.encryptedKey,model).joinToString("\u0000").toByteArray())

@Composable
fun PhoneControlSetting(provider: ApiProvider?, model: String, access: String) {
    val context=LocalContext.current
    val active by PhoneControl.session.collectAsState()
    val identity=provider?.let {phoneModelIdentity(it,model)}
    val authorization=identity?.let {"$access:$it"}
    val currentAuthorization by rememberUpdatedState(authorization)
    var testing by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val preferences=remember {context.getSharedPreferences("phone-model-checks",0)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text(uiText(R.string.phone_control,"Phone control"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Switch(active==authorization&&authorization!=null,{ enable ->
                if(!enable)PhoneControl.stop() else if(provider!=null&&identity!=null) {
                    scope.launch {
                        testing=true;error=null
                        try {
                            check(PhoneControl.service!=null) {uiText(R.string.phone_grant_first,"Enable Codeb in system accessibility settings first.")}
                            if(!preferences.getBoolean(identity,false)) {
                                val answer=(100000+SecureRandom().nextInt(900000)).toString()
                                val bitmap=Bitmap.createBitmap(440,140,Bitmap.Config.ARGB_8888)
                                val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
                                canvas.drawText(answer,30f,101f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=78f;typeface=Typeface.MONOSPACE})
                                val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle()
                                val image="data:image/png;base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)
                                val reply=withTimeout(45_000){OpenAiClient().testImage(provider.copy(selectedModel=model),ApiStore(context).key(provider),image)}
                                check(reply.trim()==answer) {uiText(R.string.phone_probe_failed,"Image test did not pass. Phone control remains off; retry or choose another model.")}
                                preferences.edit().putBoolean(identity,true).apply()
                            }
                            check(currentAuthorization==authorization) {"Provider or model changed; enable phone control again"}
                            PhoneControl.arm(authorization!!)
                        } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException)throw e;error=e.message}
                        finally {testing=false}
                    }
                }
            },enabled=!testing&&provider?.enabled==true&&model.isNotBlank())
        }
        Text(uiText(R.string.phone_control_detail,"Allows this model to read screenshots and act on the phone. Screenshots are sent to the selected provider. First use runs a small image test; use the floating stop button to end access."),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text(uiText(R.string.phone_accessibility,"System accessibility settings"))}
        if(testing) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(uiText(R.string.phone_testing,"Testing image input…"))}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}
