package com.example.gemmabuddy

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

class ModelDownloadManager(private val context: Context) {

    private val assetPackManager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context)

    /** 内部ストレージ優先でモデルパスを返す（native open()が確実） */
    fun getModelPath(): String? = findInternalModel() ?: getPadModelPath()

    fun isModelAvailable(): Boolean = getModelPath() != null

    fun getModelSource(): ModelSource {
        if (findInternalModel() != null) return ModelSource.INTERNAL
        if (findExternalModel() != null) return ModelSource.EXTERNAL_NEEDS_COPY
        if (getPadModelPath() != null) return ModelSource.PLAY_ASSET_DELIVERY
        return ModelSource.NONE
    }

    /** 内部ストレージ（/data/data/）のモデルを返す */
    fun findInternalModel(): String? {
        return context.filesDir.walkTopDown()
            .filter { it.isFile && MODEL_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
            .firstOrNull()?.absolutePath
    }

    /** 外部ストレージのモデルを返す（native open()が失敗する場合あり） */
    fun findExternalModel(): String? {
        context.getExternalFilesDir(null)?.walkTopDown()
            ?.filter { it.isFile && MODEL_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
            ?.firstOrNull()?.let { return it.absolutePath }
        File("/sdcard/Download").takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && MODEL_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
            ?.firstOrNull()?.let { return it.absolutePath }
        return null
    }

    /** 外部→内部ストレージへコピー（Javaストリームなので確実に動く） */
    fun copyModelToInternalStorage(
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val externalPath = findExternalModel() ?: run {
            onFailure("コピー元のモデルが見つかりません")
            return
        }
        val source = File(externalPath)
        val dest = File(context.filesDir, source.name)
        if (dest.exists() && dest.length() == source.length()) {
            onSuccess()
            return
        }
        Thread {
            try {
                val total = source.length()
                var copied = 0L
                source.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(4 * 1024 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress(copied.toFloat() / total)
                        }
                    }
                }
                onSuccess()
            } catch (e: Exception) {
                dest.delete()
                onFailure(e.message ?: "コピー失敗")
            }
        }.start()
    }

    /** Play Storeからモデルをオンデマンドダウンロードする */
    fun downloadModel(
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        assetPackManager.fetch(listOf(PACK_NAME))
            .addOnSuccessListener { states ->
                val state = states.packStates()[PACK_NAME] ?: run {
                    onFailure("アセットパックが見つかりません")
                    return@addOnSuccessListener
                }
                if (state.status() == AssetPackStatus.COMPLETED) {
                    onSuccess()
                    return@addOnSuccessListener
                }
                registerStateListener(onProgress, onSuccess, onFailure)
            }
            .addOnFailureListener { e ->
                onFailure(e.localizedMessage ?: "不明なエラー")
            }
    }

    private fun getPadModelPath(): String? {
        val location = assetPackManager.getPackLocation(PACK_NAME) ?: return null
        val assetsDir = java.io.File("${location.assetsPath()}/model")
        return assetsDir.walkTopDown()
            .filter { it.isFile && MODEL_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
            .firstOrNull()?.absolutePath
    }

    private fun registerStateListener(
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val listener = object : AssetPackStateUpdateListener {
            override fun onStateUpdate(state: com.google.android.play.core.assetpacks.AssetPackState) {
                if (state.name() != PACK_NAME) return
                when (state.status()) {
                    AssetPackStatus.DOWNLOADING -> {
                        val total = state.totalBytesToDownload()
                        val progress = if (total > 0) state.bytesDownloaded().toFloat() / total else 0f
                        onProgress(progress)
                    }
                    AssetPackStatus.TRANSFERRING -> onProgress(0.99f)
                    AssetPackStatus.COMPLETED -> {
                        assetPackManager.unregisterListener(this)
                        onSuccess()
                    }
                    AssetPackStatus.FAILED -> {
                        assetPackManager.unregisterListener(this)
                        onFailure("ダウンロード失敗 (エラーコード: ${state.errorCode()})")
                    }
                    AssetPackStatus.CANCELED -> {
                        assetPackManager.unregisterListener(this)
                        onFailure("ダウンロードがキャンセルされました")
                    }
                    else -> {}
                }
            }
        }
        assetPackManager.registerListener(listener)
    }

    enum class ModelSource { NONE, INTERNAL, EXTERNAL_NEEDS_COPY, PLAY_ASSET_DELIVERY }

    companion object {
        const val PACK_NAME = "gemmamodel"
        const val MODEL_FILE = "model/gemma4.task"
        private val MODEL_EXTENSIONS = listOf(".litertlm", ".task", ".bin")
    }
}
