package com.railsignal.probe

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * GPS 采集验证工具：高铁实车采集
 * 记录: GPS 轨迹(1Hz) + 信号强度 + 网络类型 + 基站信息，JSONL 格式
 * 输出: /sdcard/Android/data/com.railsignal.probe/files/probe_*.jsonl
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        appendLog("RailSignalProbe v0.1 — 高铁信号采集工具")
        appendLog("输出目录: ${getExternalFilesDir(null)}")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        startBtn = Button(this).apply { text = "开始采集" }
        stopBtn = Button(this).apply { text = "停止采集" }
        logView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }

        startBtn.setOnClickListener { onStartProbe() }
        stopBtn.setOnClickListener {
            stopService(Intent(this, ProbeForegroundService::class.java))
            appendLog("[已停止]")
        }

        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
        setContentView(root)
    }

    private fun onStartProbe() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return
        }
        val intent = Intent(this, ProbeForegroundService::class.java)
        startForegroundService(intent)
        appendLog("[开始] 采集服务已启动（屏幕可熄灭）")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            onStartProbe()
        } else {
            Toast.makeText(this, "缺少权限，无法采集", Toast.LENGTH_LONG).show()
        }
    }

    fun appendLog(line: String) {
        runOnUiThread { logView.append(line + "\n") }
    }

    companion object {
        fun serviceComponent(ctx: android.content.Context) =
            ComponentName(ctx, ProbeForegroundService::class.java)
    }
}
