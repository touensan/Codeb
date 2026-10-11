package com.codex.remote.data.local

import android.app.PendingIntent
import android.content.*
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.UUID

object RuntimeSetup {
    const val VERSION = "0.162.0"
    const val TERMUX_URL = "https://codeb.baiyun.si/downloads/termux/termux-0.118.3-arm64-v8a.apk"
    const val bootstrap = "curl -fL --retry 2 https://codeb.baiyun.si/tools/codeb-phone-setup.sh -o ~/codeb-setup.sh && bash ~/codeb-setup.sh"
    fun installed(context:Context)=runCatching {context.packageManager.getPackageInfo("com.termux",0)}.isSuccess

    suspend fun run(context: Context, install: Boolean, root: Boolean, statusOnly: Boolean = false): String {
        val action = context.packageName + ".SETUP." + UUID.randomUUID()
        val result = CompletableDeferred<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val data = intent.getBundleExtra("result") ?: return
                val output = data.getString("stdout").orEmpty().takeLast(6000)
                if(data.getInt("exitCode",-1)==0 && data.getInt("err",android.app.Activity.RESULT_OK)==android.app.Activity.RESULT_OK) result.complete(output)
                else result.completeExceptionally(IllegalStateException((output+"\n"+data.getString("stderr").orEmpty()+"\n"+data.getString("errmsg").orEmpty()).trim().take(6000)))
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
        val callback = PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_ONE_SHOT or
            if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
        val prefix = "/data/data/com.termux/files/usr/bin"
        val script = context.assets.open("codeb-runtime.cjs").use { Base64.getEncoder().encodeToString(it.readBytes()) }
        val launcher = "$TERMUX_HOME/.local/share/codeb/setup.cjs"
        val command = buildString {
            append("set -eu; umask 077; ")
            if(install) append("command -v node >/dev/null && command -v curl >/dev/null && command -v tar >/dev/null || { ${bootstrap}; }; mkdir -p ${shellQuote("$TERMUX_HOME/.local/share/codeb")}; printf %s ${shellQuote(script)} | $prefix/base64 -d > ${shellQuote(launcher)}; $prefix/node ${shellQuote(launcher)} install; ")
            if(statusOnly) append("printf 'Termux: OK\\nNode.js: '; node --version 2>/dev/null || true; printf 'Codex: '; ${shellQuote("$TERMUX_HOME/.local/share/codeb/runtime/$VERSION/bin/codex")} --version 2>/dev/null || true; printf '\\nStage: '; cat ${shellQuote("$TERMUX_HOME/.local/share/codeb/setup-stage")} 2>/dev/null || true; printf '\\n'; ")
            else {
            append("printf 'Termux: OK\\nNode.js: '; node --version; printf 'Codex: '; ${shellQuote("$TERMUX_HOME/.local/share/codeb/runtime/$VERSION/bin/codex")} --version; ")
            if(root) append("printf 'Root UID: '; su -c 'id -u'; ") else append("printf 'UID: '; id -u; ")
            }
        }
        try {
            context.startService(Intent("com.termux.RUN_COMMAND").setClassName("com.termux","com.termux.app.RunCommandService").apply {
                putExtra("com.termux.RUN_COMMAND_PATH","$prefix/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS",arrayOf("-c",command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND",true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",callback)
            })
            return withTimeout(if(install) 900_000 else if(statusOnly) 8_000 else 25_000) {result.await()}.also {if(root&&!statusOnly)check(it.lineSequence().any {line -> line.trim()=="Root UID: 0"}) {"Root authorization did not return UID 0"}}
        } finally {callback.cancel();context.unregisterReceiver(receiver)}
    }
}

// Existing projects stay in place so saved histories keep their canonical paths.
fun shortPhonePath(path: String): String = path
    .replace("$TERMUX_HOME/.local/share/codeb/workspaces/standard", "~/Codeb")
    .replace("$TERMUX_HOME/.local/share/codeb/workspaces/root", "~/Codeb-root")
    .replace(TERMUX_HOME, "~")
