package com.codex.remote

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.store.*
import com.codex.remote.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WorkspaceAttachmentDeviceTest {
    @Test fun phoneDocumentsAndImagesAreCopiedIntoBothRuntimeIdentities() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val resolver = context.contentResolver
        val store = ApiStore(context); val original = store.providers()
        val provider = ApiProvider("attachment-check", "Check", "https://api.baiyun.si/v1", store.encryptKey("no-inference"), ApiProtocol.RESPONSES,
            listOf(ApiModel("gpt-6.1-sol")), "gpt-6.1-sol")
        val png = ByteArrayOutputStream().apply {
            val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE); bitmap.compress(Bitmap.CompressFormat.PNG,100,this); bitmap.recycle()
        }.toByteArray()
        store.saveProviders(original + provider)
        try {
            for (access in listOf("standard", "root")) {
                val vm = AppViewModel(context.applicationContext as android.app.Application, autoRestore = false)
                try {
                    withContext(Dispatchers.Main) { vm.connect(SavedConnection(id="attachments-$access",name="Attachment check",host="localhost",username=access,
                        authType=AuthType.PASSWORD,localAccess=access,localProviderId=provider.id)) }
                    withTimeout(90_000) { while(vm.state.value.connectionStatus!=ConnectionStatus.CONNECTED || vm.state.value.selectedProjectPath==null) {
                        check(vm.state.value.connectionStatus!=ConnectionStatus.ERROR) { vm.state.value.connectionMessage }; delay(100)
                    } }
                    for ((mime,bytes) in listOf("image/png" to png, "text/plain" to "手机文件内容\n".toByteArray())) {
                        val image = mime.startsWith("image/")
                        val values=ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME,"Codeb-check-${System.nanoTime()}.${if(image) "png" else "txt"}")
                            put(MediaStore.MediaColumns.MIME_TYPE,mime)
                            put(MediaStore.MediaColumns.IS_PENDING,1)
                        }
                        val uri=checkNotNull(resolver.insert(if(image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                        try {
                            resolver.openOutputStream(uri)!!.use {it.write(bytes)}
                            resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                            val attached=vm.importWorkspaceAttachment(uri,image)
                            assertNotNull(attached.uploadedPath)
                            assertEquals("",attached.dataUrl)
                            val files=vm.phoneFiles()
                            try { assertArrayEquals(bytes,files.read(attached.uploadedPath!!)) }
                            finally {files.delete(attached.uploadedPath!!);files.delete(attached.uploadedPath.substringBeforeLast('/'))}
                        } finally {resolver.delete(uri,null,null)}
                    }
                } finally {withContext(Dispatchers.Main) {vm.disconnect()}}
            }
        } finally {store.saveProviders(original)}
    }
}
