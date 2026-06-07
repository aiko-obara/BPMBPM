package com.example.gemmabuddy

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CharacterGenerationActivity : AppCompatActivity() {

    private lateinit var ivCharPreview: ImageView
    private lateinit var tvCharName: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var btnRoll: Button
    private lateinit var btnDeploy: Button
    private lateinit var llCharThumbs: LinearLayout
    private lateinit var llBuddyList: LinearLayout
    private lateinit var tvEmptyBuddies: TextView

    private lateinit var buddyStore: BuddyStore
    private lateinit var imageProvider: BuddyImageProvider

    private var currentFolder: String? = null
    private var currentFrames: CharacterFrames? = null
    private val thumbViews = mutableListOf<ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_character_generation)
        setupBottomNav(NavTab.BUDDIES)

        buddyStore = BuddyStore(this)
        imageProvider = BuddyImageProvider(this)

        ivCharPreview = findViewById(R.id.iv_char_preview)
        tvCharName = findViewById(R.id.tv_char_name)
        tvEmptyHint = findViewById(R.id.tv_empty_hint)
        btnRoll = findViewById(R.id.btn_roll)
        btnDeploy = findViewById(R.id.btn_deploy)
        llCharThumbs = findViewById(R.id.ll_char_thumbs)
        llBuddyList = findViewById(R.id.ll_buddy_list)
        tvEmptyBuddies = findViewById(R.id.tv_empty_buddies)

        btnRoll.setOnClickListener { rollCharacter() }
        btnDeploy.setOnClickListener { confirmDeploy() }

        buildThumbnails()
        rollCharacter()
    }

    override fun onResume() {
        super.onResume()
        refreshBuddyList()
    }

    private fun buildThumbnails() {
        val folders = imageProvider.listFolders()
        llCharThumbs.removeAllViews()
        thumbViews.clear()
        val sizePx = (72 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()

        folders.forEachIndexed { index, folder ->
            val bmp = imageProvider.loadThumbnail(folder)
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also {
                    it.setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(6, 6, 6, 6)
                setBackgroundResource(R.drawable.bg_thumb_normal)
                if (bmp != null) setImageBitmap(bmp)
                setOnClickListener {
                    val frames = imageProvider.loadFrames(folder)
                    if (frames != null) selectCharacter(index, folder, frames)
                }
            }
            thumbViews.add(iv)
            llCharThumbs.addView(iv)
        }
    }

    private fun selectCharacter(index: Int, folder: String, frames: CharacterFrames) {
        currentFolder = folder
        currentFrames = frames

        thumbViews.forEachIndexed { i, iv ->
            iv.setBackgroundResource(
                if (i == index) R.drawable.bg_thumb_selected else R.drawable.bg_thumb_normal
            )
        }

        ivCharPreview.setImageBitmap(frames.normal1)
        tvCharName.text = BuddyImageProvider.displayName(folder)
        tvEmptyHint.visibility = View.GONE
        ivCharPreview.visibility = View.VISIBLE
        tvCharName.visibility = View.VISIBLE
        btnDeploy.isEnabled = true
    }

    private fun rollCharacter() {
        val result = imageProvider.pickNext()
        if (result == null) {
            tvEmptyHint.visibility = View.VISIBLE
            ivCharPreview.visibility = View.GONE
            tvCharName.visibility = View.GONE
            btnDeploy.isEnabled = false
            btnRoll.isEnabled = false
            return
        }
        val (folder, frames) = result
        val index = imageProvider.listFolders().indexOf(folder)
        selectCharacter(index, folder, frames)
        btnRoll.isEnabled = imageProvider.hasMultiple()
    }

    private fun confirmDeploy() {
        val frames = currentFrames ?: return
        val folder = currentFolder ?: return
        val autoName = BuddyImageProvider.displayName(folder)

        val input = EditText(this).apply {
            setText(autoName)
            selectAll()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_name_buddy_title)
            .setView(input)
            .setPositiveButton(R.string.action_deploy) { _, _ ->
                val name = input.text.toString().trim().ifBlank { autoName }
                deployBuddy(frames, name, folder)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deployBuddy(frames: CharacterFrames, name: String, templateType: String) {
        try {
            val entry = buddyStore.saveNew(
                name, frames.normal1, templateType,
                normal2Bitmap = frames.normal2,
                speakingBitmap = frames.speaking
            )
            sendBroadcast(Intent(OverlayService.ACTION_RELOAD_CHARACTER))
            Toast.makeText(this, getString(R.string.toast_deploy_done, entry.name), Toast.LENGTH_SHORT).show()
            refreshBuddyList()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBuddyList() {
        val buddies = buddyStore.loadAll()
        llBuddyList.removeAllViews()

        if (buddies.isEmpty()) {
            tvEmptyBuddies.visibility = View.VISIBLE
            return
        }
        tvEmptyBuddies.visibility = View.GONE

        buddies.forEach { entry ->
            val card = LayoutInflater.from(this)
                .inflate(R.layout.layout_buddy_card, llBuddyList, false)

            val thumb = card.findViewById<ImageView>(R.id.iv_buddy_thumb)
            val file = buddyStore.getFile(entry)
            if (file.exists()) {
                thumb.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            }

            card.findViewById<TextView>(R.id.tv_buddy_name).text = entry.name
            val personalityLabel = entry.defaultPersonalityId
                ?.let { id -> BuddyPersonality.all.find { it.id == id } }
                ?.let { "  •  ${it.emoji}${it.name}" } ?: ""
            card.findViewById<TextView>(R.id.tv_buddy_template).text =
                "${BuddyImageProvider.displayName(entry.templateType)}  •  ${formatDate(entry.createdAt)}$personalityLabel"

            // カード長押しで性格を割り当て
            card.setOnLongClickListener { showPersonalityPicker(entry); true }

            val chipActive = card.findViewById<LinearLayout>(R.id.chip_active)
            val btnSwitch = card.findViewById<Button>(R.id.btn_switch)
            if (entry.isActive) {
                chipActive.visibility = View.VISIBLE
                btnSwitch.visibility = View.GONE
            } else {
                chipActive.visibility = View.GONE
                btnSwitch.visibility = View.VISIBLE
                btnSwitch.setOnClickListener { switchBuddy(entry.id) }
            }

            card.findViewById<Button>(R.id.btn_delete).setOnClickListener {
                confirmDelete(entry)
            }

            llBuddyList.addView(card)
        }
    }

    private fun showPersonalityPicker(entry: BuddyEntry) {
        val items = BuddyPersonality.all.map { "${it.emoji} ${it.name}" }.toTypedArray()
        val currentIdx = BuddyPersonality.all
            .indexOfFirst { it.id == entry.defaultPersonalityId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_buddy_personality_title, entry.name))
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val selected = BuddyPersonality.all[which]
                buddyStore.setPersonality(entry.id, selected.id)
                val label = "${selected.emoji} ${selected.name}"
                val msg = if (entry.isActive) {
                    getString(R.string.personality_set_active, label)
                } else {
                    getString(R.string.personality_set_inactive, label)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                refreshBuddyList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun switchBuddy(id: String) {
        buddyStore.setActive(id)
        sendBroadcast(Intent(OverlayService.ACTION_RELOAD_CHARACTER))
        Toast.makeText(this, R.string.toast_switched, Toast.LENGTH_SHORT).show()
        refreshBuddyList()
    }

    private fun confirmDelete(entry: BuddyEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title, entry.name))
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                buddyStore.delete(entry.id)
                sendBroadcast(Intent(OverlayService.ACTION_RELOAD_CHARACTER))
                Toast.makeText(this, getString(R.string.toast_deleted, entry.name), Toast.LENGTH_SHORT).show()
                refreshBuddyList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun formatDate(ts: Long): String =
        SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(ts))
}
