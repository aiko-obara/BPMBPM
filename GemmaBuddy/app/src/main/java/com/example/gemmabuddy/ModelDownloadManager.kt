package com.example.gemmabuddy

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus

class ModelDownloadManager(context: Context) {

    private val assetPackManager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context)

    /** モデルファイルのフルパスを返す。未ダウンロードの場合はnull */
    fun getModelPath(): String? {
        val location = assetPackManager.getPackLocation(PACK_NAME) ?: return null
        return "${location.assetsPath()}/$MODEL_FILE"
    }

    /** モデルがローカルに存在するか */
    fun isModelAvailable(): Boolean = getModelPath() != null

    /**
     * Play Storeからモデルをオンデマンドダウンロードする。
     * 進捗コールバック: onProgress(0.0f〜1.0f)
     */
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

    companion object {
        const val PACK_NAME = "gemmamodel"
        const val MODEL_FILE = "model/gemma4.task"
    }
}
