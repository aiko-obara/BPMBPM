package com.example.gemmabuddy

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

class ModelDownloadManager(context: Context) {

    private val assetPackManager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context)

    /**
     * モデルファイルのパスを返す。優先順位:
     * 1. AI Edge Galleryのキャッシュ（端末に既存）
     * 2. Play Asset Deliveryでダウンロード済み
     * 未検出の場合はnull
     */
    fun getModelPath(): String? = findAiEdgeGalleryModel() ?: getPadModelPath()

    /** モデルがローカルに存在するか */
    fun isModelAvailable(): Boolean = getModelPath() != null

    /** AI Edge Galleryのモデルソースを返す（UI表示用） */
    fun getModelSource(): ModelSource {
        if (findAiEdgeGalleryModel() != null) return ModelSource.AI_EDGE_GALLERY
        if (getPadModelPath() != null) return ModelSource.PLAY_ASSET_DELIVERY
        return ModelSource.NONE
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

    private fun findAiEdgeGalleryModel(): String? {
        val baseDir = File(AI_EDGE_GALLERY_BASE)
        if (!baseDir.exists()) return null
        // サブディレクトリを再帰的に検索して .litertlm または .task を探す
        return baseDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".litertlm") || it.name.endsWith(".task")) }
            .firstOrNull()
            ?.absolutePath
    }

    private fun getPadModelPath(): String? {
        val location = assetPackManager.getPackLocation(PACK_NAME) ?: return null
        return "${location.assetsPath()}/$MODEL_FILE"
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

    enum class ModelSource { NONE, AI_EDGE_GALLERY, PLAY_ASSET_DELIVERY }

    companion object {
        const val PACK_NAME = "gemmamodel"
        const val MODEL_FILE = "model/gemma4.task"
        private const val AI_EDGE_GALLERY_BASE =
            "/sdcard/Android/data/com.google.ai.edge.gallery/files"
    }
}
