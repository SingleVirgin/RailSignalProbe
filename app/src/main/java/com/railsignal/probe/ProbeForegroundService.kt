package com.railsignal.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 采集服务：
 * - FusedLocation 1Hz（PRIORITY_HIGH_ACCURACY）记录 lat/lon/accuracy/speed/bearing
 * - 每 1s 轮询 TelephonyManager：serviceState、dataNetworkType、signalStrength、cellInfo
 * - 全部写入同一 JSONL 行（时间戳对齐），出隧道/进隧道的信号轨迹可离线分析
 */
class ProbeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var telephony: TelephonyManager
    private lateinit var outFile: File
    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // 最新状态缓存（各回调写入，JSONL 组装时读取）
    @Volatile private var lastSignal: JSONObject? = null
    @Volatile private var lastNetworkType: String = "UNKNOWN"
    @Volatile private var lastServiceState: String = "UNKNOWN"
    @Volatile private var lastCell: JSONObject? = null
    @Volatile private var lastDisplayInfo: String = "UNKNOWN"

    private var phoneListener: PhoneStateListener? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            writeLine(JSONObject().apply {
                put("t", sdf.format(Date()))
                put("type", "gps")
                put("lat", loc.latitude)
                put("lon", loc.longitude)
                put("acc_m", loc.accuracy)
                put("speed_mps", loc.speed)
                put("bearing", loc.bearing.toDouble())
                put("provider", loc.provider)
                // 同一时刻的电信状态一并记录
                put("network_type", lastNetworkType)
                put("service_state", lastServiceState)
                put("display_info", lastDisplayInfo)
                lastSignal?.let { put("signal", it) }
                lastCell?.let { put("cell", it) }
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outFile = File(getExternalFilesDir(null), "probe_$stamp.jsonl")

        registerPhoneListener()
        startForegroundServiceInternal()
        requestLocation()

        scope.launch {
            while (isActive) {
                pollCellInfo()
                delay(1000)
            }
        }
        android.util.Log.i(TAG, "采集写入 ${outFile.absolutePath}")
    }

    private fun registerPhoneListener() {
        phoneListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(ss: SignalStrength) {
                lastSignal = JSONObject().apply {
                    put("level", ss.level)
                    val css = ss.cellSignalStrengths.firstOrNull()
                    put("dbm", css?.dbm ?: -1)
                    put("rsrp", (css as? CellSignalStrengthLte)?.rsrp ?: -1)
                }
            }
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                lastNetworkType = networkTypeName(networkType)
            }
            override fun onServiceStateChanged(ss: ServiceState) {
                lastServiceState = when (ss.state) {
                    ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
                    ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                    else -> "UNKNOWN"
                }
            }
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                val nrAdvanced = runCatching { info.isNrAdvanced }.getOrDefault(false)
                lastDisplayInfo = "${info.networkType}/${if (nrAdvanced) "NR_ADV" else "STD"}"
            }
        }
        runCatching {
            telephony.listen(phoneListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        or PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        or PhoneStateListener.LISTEN_SERVICE_STATE
                        or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
        }
    }

    private fun pollCellInfo() {
        runCatching {
            val all: List<CellInfo> = telephony.allCellInfo
            val primary = all.firstOrNull { it.isRegistered } ?: all.firstOrNull()
            lastCell = when (primary) {
                is CellInfoLte -> JSONObject().apply {
                    put("rat", "LTE")
                    put("ci", primary.cellIdentity.ci)
                    put("pci", primary.cellIdentity.pci)
                    put("earfcn", primary.cellIdentity.earfcn)
                    val rsrp = primary.cellSignalStrength.rsrp
                    if (rsrp != CellInfo.UNAVAILABLE) put("rsrp", rsrp)
                }
                is CellInfoNr -> JSONObject().apply {
                    put("rat", "NR")
                    val ci = primary.cellIdentity as? CellIdentityNr
                    put("nci", ci?.nci ?: -1)
                    put("pci", ci?.pci ?: -1)
                    put("nrarfcn", ci?.nrarfcn ?: -1)
                }
                else -> primary?.let { JSONObject().apply { put("rat", it.javaClass.simpleName) } }
            }
        }
    }

    private fun requestLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2000L)
            .build()
        runCatching { client.requestLocationUpdates(req, locationCallback, mainLooper) }
    }

    private fun writeLine(json: JSONObject) {
        scope.launch { runCatching {
            outFile.appendText(json.toString() + "\n")
        } }
    }

    private fun networkTypeName(t: Int) = when (t) {
        20 -> "NR"
        18, 19 -> "LTE_CA"
        13 -> "LTE"
        14 -> "HSPAP"
        9 -> "HSUPA"
        10 -> "HSPA"
        8 -> "HSDPA"
        3 -> "UMTS"
        2 -> "EDGE"
        1 -> "GPRS"
        else -> "TYPE_$t"
    }

    private fun startForegroundServiceInternal() {
        val channelId = "probe"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(channelId, "采集", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("RailSignalProbe 采集中")
            .setContentText("记录 GPS 与信号轨迹")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onDestroy() {
        runCatching { telephony.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
        LocationServices.getFusedLocationProviderClient(this)
            .removeLocationUpdates(locationCallback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ProbeSvc"
    }
}
