package com.example.gemmabuddy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import java.time.LocalDate

class StepCounterManager(private val context: Context) {

    var onMilestoneReached: ((milestone: Int) -> Unit)? = null
    var onStepsUpdated: ((steps: Int) -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var listening = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val totalSteps = event.values[0].toLong()
            val today = LocalDate.now().toString()
            val baselineDate = prefs.getString(PREF_BASELINE_DATE, "") ?: ""

            val baseline: Long
            if (baselineDate != today) {
                // 日付が変わったのでベースラインをリセット
                baseline = totalSteps
                prefs.edit()
                    .putLong(PREF_BASELINE_STEPS, totalSteps)
                    .putString(PREF_BASELINE_DATE, today)
                    .putStringSet(PREF_ACHIEVED_MILESTONES, emptySet())
                    .apply()
            } else {
                baseline = prefs.getLong(PREF_BASELINE_STEPS, totalSteps)
            }

            val todaySteps = (totalSteps - baseline).coerceAtLeast(0).toInt()
            prefs.edit().putInt(PREF_TODAY_STEPS, todaySteps).apply()

            // DB に保存
            try {
                AppDatabase.getInstance(context).memoryDao().upsertStepLog(today, todaySteps)
            } catch (e: Exception) {
                Log.w(TAG, "step_logs upsert 失敗", e)
            }

            onStepsUpdated?.invoke(todaySteps)
            checkMilestones(todaySteps)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start() {
        if (stepSensor == null) {
            Log.w(TAG, "TYPE_STEP_COUNTER センサーが利用不可")
            return
        }
        if (listening) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACTIVITY_RECOGNITION 権限なし — 歩数計測スキップ")
            return
        }
        try {
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            listening = true
            Log.i(TAG, "歩数センサー開始")
        } catch (e: SecurityException) {
            Log.w(TAG, "歩数センサー登録失敗: 権限不足", e)
        }
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(listener)
        listening = false
        Log.i(TAG, "歩数センサー停止")
    }

    fun getTodaySteps(): Int = prefs.getInt(PREF_TODAY_STEPS, 0)

    private fun checkMilestones(steps: Int) {
        val achieved = prefs.getStringSet(PREF_ACHIEVED_MILESTONES, emptySet())?.toMutableSet() ?: mutableSetOf()
        for (milestone in MILESTONES) {
            val key = milestone.toString()
            if (steps >= milestone && !achieved.contains(key)) {
                achieved.add(key)
                prefs.edit().putStringSet(PREF_ACHIEVED_MILESTONES, achieved).apply()
                onMilestoneReached?.invoke(milestone)
                break  // 一度に1マイルストーンだけ通知
            }
        }
    }

    companion object {
        private const val TAG = "StepCounterManager"
        const val PREFS_NAME = "step_counter"
        const val PREF_TODAY_STEPS = "today_steps"
        private const val PREF_BASELINE_STEPS = "baseline_steps"
        private const val PREF_BASELINE_DATE = "baseline_date"
        private const val PREF_ACHIEVED_MILESTONES = "achieved_milestones"

        val MILESTONES = listOf(500, 1000, 3000, 5000, 10000)
    }
}
