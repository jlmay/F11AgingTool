package com.decard.f11aging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.decard.NDKMethod.BasicOper
// IDCard 类来自 dc_reader_release_20260302133638.aar 中的 com.decard.entitys 包
import com.decard.entitys.IDCard
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F11-N 底座老化测试工具 — 主界面
 *
 * 测试项：身份证、非接触CPU卡、接触CPU卡、以太网PING、扫码
 * 老化模式：
 *   模式1：不断电循环（断电后开机自动恢复）
 *   模式2：每轮测试后重启，开机自动继续
 */
class MainActivity : AppCompatActivity() {

    // ── UI 控件 ───────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbModeNormal: RadioButton
    private lateinit var rbModeReboot: RadioButton
    private lateinit var etTotalCount: EditText
    private lateinit var etStartDelay: EditText
    private lateinit var etPingHost: EditText
    private lateinit var etPingCount: EditText
    private lateinit var cbIdCard: CheckBox
    private lateinit var cbContactlessCard: CheckBox
    private lateinit var cbContactCard: CheckBox
    private lateinit var cbEthernet: CheckBox
    private lateinit var cbBarcode: CheckBox
    private lateinit var cbStopOnError: CheckBox
    private lateinit var layoutBarcodeInput: View
    private lateinit var etBarcode: EditText
    private lateinit var tvCurrentCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvSuccessCount: TextView
    private lateinit var tvFailCount: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnViewLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var scrollLog: ScrollView
    private lateinit var tvLog: TextView
    // 网络信息显示控件（独立于日志区）
    private lateinit var tvIpAddress: TextView

    // ── 状态 ─────────────────────────────────────────────────
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // 暂停/继续
    private val isPaused = AtomicBoolean(false)
    private val pauseLock = Object()

    // 日志轮次缓冲（最近 10 轮日志，独立于文件写入）
    private val logRoundBuffers = mutableListOf<String>()
    private var currentRoundBuffer = StringBuilder()
    private val MAX_DISPLAY_ROUNDS = 10

    private var devHandle: Int = -1
    private var currentCount = 0
    private var successCount = 0
    private var failCount = 0
    private var barcodeSampleConfirmed = false
    private val barcodeAutoConfirmHandler = Handler(Looper.getMainLooper())
    private val barcodeAutoConfirmRunnable = Runnable { showBarcodeConfirmDialog(etBarcode.text.toString().trim()) }

    // 日志文件路径
    private val logDir by lazy {
        File(getExternalFilesDir(null), "logs").also { it.mkdirs() }
    }
    private var currentLogFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // ── 偏好设置键 ──────────────────────────────────────────
    private val PREFS_NAME = "f11aging_prefs"
    private val PREF_AUTO_MODE = "auto_mode_enabled"
    private val PREF_RUN_MODE = "run_mode"           // 1=不断电, 2=每轮重启
    private val PREF_REMAINING = "auto_mode_remaining"
    private val PREF_TOTAL = "auto_mode_total"
    private val PREF_CURRENT = "auto_mode_current"   // 已测次数
    private val PREF_SUCCESS = "auto_mode_success"    // 成功次数
    private val PREF_FAIL = "auto_mode_fail"          // 失败次数
    private val PREF_DELAY = "auto_mode_delay"
    private val PREF_PING_HOST = "auto_ping_host"
    private val PREF_PING_COUNT = "auto_ping_count"
    private val PREF_CB_IDCARD = "auto_cb_idcard"
    private val PREF_CB_CONTACTLESS = "auto_cb_contactless"
    private val PREF_CB_CONTACT = "auto_cb_contact"
    private val PREF_CB_ETHERNET = "auto_cb_ethernet"
    private val PREF_CB_BARCODE = "auto_cb_barcode"
    private val PREF_STOP_ON_ERROR = "auto_stop_on_error"
    private val PREF_BARCODE_REF = "auto_barcode_ref"
    private val PREF_BARCODE_CONFIRMED = "auto_barcode_confirmed"
    private val PREF_LOG_FILE = "auto_log_file"     // 日志文件绝对路径

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        restorePrefs()
        checkAutoStart()
    }

    // ── 绑定视图 ─────────────────────────────────────────────
    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvTitle  = findViewById(R.id.tvTitle)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        tvTitle.text = "F11-N 底座老化测试工具 v$versionName"
        rgMode = findViewById(R.id.rgMode)
        rbModeNormal = findViewById(R.id.rbModeNormal)
        rbModeReboot = findViewById(R.id.rbModeReboot)
        etTotalCount = findViewById(R.id.etTotalCount)
        etStartDelay = findViewById(R.id.etStartDelay)
        etPingHost = findViewById(R.id.etPingHost)
        etPingCount = findViewById(R.id.etPingCount)
        cbIdCard = findViewById(R.id.cbIdCard)
        cbContactlessCard = findViewById(R.id.cbContactlessCard)
        cbContactCard = findViewById(R.id.cbContactCard)
        cbEthernet = findViewById(R.id.cbEthernet)
        cbBarcode = findViewById(R.id.cbBarcode)
        cbStopOnError = findViewById(R.id.cbStopOnError)
        layoutBarcodeInput = findViewById(R.id.layoutBarcodeInput)
        etBarcode = findViewById(R.id.etBarcode)
        tvCurrentCount = findViewById(R.id.tvCurrentCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        tvSuccessCount = findViewById(R.id.tvSuccessCount)
        tvFailCount = findViewById(R.id.tvFailCount)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnViewLog = findViewById(R.id.btnViewLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        scrollLog = findViewById(R.id.scrollLog)
        tvLog = findViewById(R.id.tvLog)
        tvIpAddress = findViewById(R.id.tvIpAddress)
    }

    // ── 事件监听 ─────────────────────────────────────────────
    private fun setupListeners() {
        cbBarcode.setOnCheckedChangeListener { _, checked ->
            layoutBarcodeInput.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                // 如果已有已确认的样本码（模式2重启恢复），不重置
                if (!barcodeSampleConfirmed) {
                    etBarcode.isEnabled = true
                    etBarcode.text = null
                    etBarcode.text.clear()
                    etBarcode.requestFocus()
                }
            } else {
                barcodeSampleConfirmed = false
            }
        }

        // 扫码样本自动确认：收到回车 或 300ms 无新数据 → 弹窗确认
        etBarcode.setOnKeyListener { v: View, keyCode: Int, event: KeyEvent ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                val text = etBarcode.text.toString().trim()
                if (text.isNotEmpty() && !barcodeSampleConfirmed) {
                    showBarcodeConfirmDialog(text)
                }
                true
            } else false
        }
        etBarcode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (barcodeSampleConfirmed) return
                barcodeAutoConfirmHandler.removeCallbacks(barcodeAutoConfirmRunnable)
                if (s != null && s.isNotEmpty()) {
                    barcodeAutoConfirmHandler.postDelayed(barcodeAutoConfirmRunnable, 50)
                }
            }
        })

        btnStart.setOnClickListener {
            when {
                !isRunning.get() -> startTesting()
                isPaused.get() -> resumeFromPause()
                else -> pauseTesting()
            }
        }
        btnStop.setOnClickListener { stopTesting() }
        btnViewLog.setOnClickListener { viewLogs() }
        btnClearLog.setOnClickListener {
            tvLog.text = ""
        }
    }

    // ── 偏好设置恢复 ─────────────────────────────────────────
    private fun restorePrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etPingHost.setText(prefs.getString(PREF_PING_HOST, "www.baidu.com"))
        etPingCount.setText(prefs.getString(PREF_PING_COUNT, "10"))
    }

    /** 保存测试状态到 SharedPreferences（断电/重启恢复用） */
    private fun saveState(remaining: Int, runMode: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_AUTO_MODE, true)
            putInt(PREF_RUN_MODE, runMode)
            putInt(PREF_REMAINING, remaining)
            putInt(PREF_TOTAL, remaining + currentCount)
            putInt(PREF_CURRENT, currentCount)
            putInt(PREF_SUCCESS, successCount)
            putInt(PREF_FAIL, failCount)
            putInt(PREF_DELAY, etStartDelay.text.toString().toIntOrNull() ?: 0)
            putString(PREF_PING_HOST, etPingHost.text.toString())
            putString(PREF_PING_COUNT, etPingCount.text.toString())
            putBoolean(PREF_CB_IDCARD, cbIdCard.isChecked)
            putBoolean(PREF_CB_CONTACTLESS, cbContactlessCard.isChecked)
            putBoolean(PREF_CB_CONTACT, cbContactCard.isChecked)
            putBoolean(PREF_CB_ETHERNET, cbEthernet.isChecked)
            putBoolean(PREF_CB_BARCODE, cbBarcode.isChecked)
            putBoolean(PREF_STOP_ON_ERROR, cbStopOnError.isChecked)
            putString(PREF_BARCODE_REF, etBarcode.text.toString())
            putBoolean(PREF_BARCODE_CONFIRMED, barcodeSampleConfirmed)
            putString(PREF_LOG_FILE, currentLogFile?.absolutePath ?: "")
            apply()
        }
    }

    /** 清除自动模式标志和所有测试状态（测试完成或手动停止时调用） */
    private fun clearAutoState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_MODE, false)
            .putInt(PREF_REMAINING, 0)
            .putInt(PREF_TOTAL, 0)
            .putInt(PREF_CURRENT, 0)
            .putInt(PREF_SUCCESS, 0)
            .putInt(PREF_FAIL, 0)
            .putString(PREF_LOG_FILE, "")
            .apply()
    }

    // ── 开机自动恢复检查 ──────────────────────────────────────
    private fun checkAutoStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_AUTO_MODE, false)) return

        val remaining = prefs.getInt(PREF_REMAINING, 0)
        val total = prefs.getInt(PREF_TOTAL, 0)
        val runMode = prefs.getInt(PREF_RUN_MODE, 1)
        val delay = prefs.getInt(PREF_DELAY, 1000)

        // 剩余为0，任务已完成
        if (remaining <= 0) {
            clearAutoState()
            appendLogScreen("[恢复] 所有测试已完成")
            return
        }

        val modeName = if (runMode == 2) "模式2(每次重启)" else "模式1(不断电)"
        appendLogScreen("[恢复] 开机检测到未完成测试，模式=$modeName，剩余 $remaining/$total 次")

        // 恢复计数器
        currentCount = prefs.getInt(PREF_CURRENT, 0)
        successCount = prefs.getInt(PREF_SUCCESS, 0)
        failCount = prefs.getInt(PREF_FAIL, 0)

        // 恢复日志文件（继续写入同一个文件）
        val savedLogPath = prefs.getString(PREF_LOG_FILE, "")
        if (!savedLogPath.isNullOrBlank()) {
            val savedFile = File(savedLogPath)
            if (savedFile.parentFile?.exists() == true) {
                currentLogFile = savedFile
            }
        }

        // 恢复 UI 配置
        if (runMode == 2) rbModeReboot.isChecked else rbModeNormal.isChecked
        etTotalCount.setText(total.toString())
        etStartDelay.setText(delay.toString())
        cbIdCard.isChecked = prefs.getBoolean(PREF_CB_IDCARD, true)
        cbContactlessCard.isChecked = prefs.getBoolean(PREF_CB_CONTACTLESS, false)
        cbContactCard.isChecked = prefs.getBoolean(PREF_CB_CONTACT, false)
        cbEthernet.isChecked = prefs.getBoolean(PREF_CB_ETHERNET, true)
        // 先恢复扫码样本相关数据，再设置 cbBarcode（避免其 listener 触发清空文本）
        barcodeSampleConfirmed = prefs.getBoolean(PREF_BARCODE_CONFIRMED, false)
        etBarcode.setText(prefs.getString(PREF_BARCODE_REF, ""))
        cbBarcode.isChecked = prefs.getBoolean(PREF_CB_BARCODE, false)
        if (barcodeSampleConfirmed) etBarcode.isEnabled = false
        // 恢复 cbStopOnError（需要放在所有 cb 之后）
        cbStopOnError.isChecked = prefs.getBoolean(PREF_STOP_ON_ERROR, false)
        etPingHost.setText(prefs.getString(PREF_PING_HOST, "www.baidu.com"))
        etPingCount.setText(prefs.getString(PREF_PING_COUNT, "10"))

        // 更新统计 UI
        updateCountUI()
        tvTotalCount.text = total.toString()

        // 延时后继续测试
        handler.postDelayed({ resumeFromBoot(remaining, total, runMode) }, delay.toLong())
    }

    /** 开机恢复后继续测试 */
    private fun resumeFromBoot(remaining: Int, total: Int, runMode: Int) {
        if (isRunning.get()) return

        // 如果没有已保存的日志文件（断电导致文件丢失等），才创建新文件
        if (currentLogFile == null || !currentLogFile!!.exists()) {
            val ts = sdfFile.format(Date())
            currentLogFile = File(logDir, "aging_${ts}.txt")
        }
        val ts = sdf.format(Date())
        appendLogScreen("\n====== 恢复测试 [$ts] ======")

        isRunning.set(true)
        setUIRunning(true)
        updateNetworkInfo()

        executor.submit { openReader() }
        executor.submit {
            continueTestLoop(remaining, total, runMode)
        }
    }

    // ── 开始/停止测试 ────────────────────────────────────────
    private fun startTesting() {
        if (isRunning.get()) return

        // 检查「显示在其他应用上层」权限（开机自启动必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("overlay_warned", false)) {
                prefs.edit().putBoolean("overlay_warned", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("自启动权限提醒")
                    .setMessage("为支持开机自动恢复测试，需要开启「显示在其他应用上层」权限。\n\n是否现在去设置？")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("以后再说", null)
                    .show()
            }
        }

        val isRebootMode = rbModeReboot.isChecked
        val runMode = if (isRebootMode) 2 else 1
        val total = etTotalCount.text.toString().toIntOrNull() ?: 0

        if (total <= 0) { toast("请设置有效的测试次数"); return }
        val hasTasks = cbIdCard.isChecked || cbContactlessCard.isChecked ||
                cbContactCard.isChecked || cbEthernet.isChecked || cbBarcode.isChecked
        if (!hasTasks) { toast("请至少勾选一个测试项"); return }
        if (cbBarcode.isChecked && !barcodeSampleConfirmed) {
            toast("请先扫码并确认样本二维码再开始测试"); return
        }

        // 保存初始状态（模式1也保存，以防断电恢复）
        currentCount = 0; successCount = 0; failCount = 0
        saveState(remaining = total, runMode = runMode)

        // 重置日志缓冲
        synchronized(logRoundBuffers) {
            logRoundBuffers.clear()
            currentRoundBuffer = StringBuilder()
        }
        updateCountUI()
        tvTotalCount.text = total.toString()

        val ts = sdfFile.format(Date())
        currentLogFile = File(logDir, "aging_${ts}.txt")
        appendLog("====== 测试开始 [$ts] ======")
        appendLog("总次数=$total | 模式=${getModeName()} | 延时=${etStartDelay.text}ms")
        val items = buildString {
            if (cbIdCard.isChecked) append(" ①身份证")
            if (cbContactlessCard.isChecked) append(" ②非接触CPU")
            if (cbContactCard.isChecked) append(" ③接触CPU")
            if (cbEthernet.isChecked) append(" ④以太网")
            if (cbBarcode.isChecked) append(" ⑤扫码")
        }
        appendLog("测试项目:$items")

        isRunning.set(true)
        setUIRunning(true)
        updateNetworkInfo()

        executor.submit { openReader() }
        executor.submit {
            if (isRebootMode) {
                // 模式2：只跑一轮，然后重启
                runSingleRoundAndReboot(roundIndex = 1, remaining = total)
            } else {
                // 模式1：不断电循环
                runTestLoop(total, runMode = 1)
            }
        }
    }

    private fun stopTesting() {
        isRunning.set(false)
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        clearAutoState()
        appendLog("====== 测试已手动停止 ======")
        handler.post {
            findViewById<View>(R.id.layoutNetworkInfo).visibility = View.GONE
            setUIRunning(false)
        }
    }

    private fun pauseTesting() {
        isPaused.set(true)
        appendLogScreen("====== 用户暂停测试 ======")
        handler.post {
            btnStart.text = "继续测试"
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF8F00"))
            tvStatus.text = "● 已暂停"
            tvStatus.setTextColor(0xFFFF8F00.toInt())
        }
    }

    private fun resumeFromPause() {
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        appendLogScreen("====== 用户恢复测试 ======")
        handler.post {
            btnStart.text = "暂停测试"
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1565C0"))
            tvStatus.text = "● 测试中"
            tvStatus.setTextColor(0xFFFF8F00.toInt())
        }
    }

    // ── 模式1：不断电循环 ─────────────────────────────────────
    private fun runTestLoop(total: Int, runMode: Int) {
        val startIndex = currentCount + 1

        for (i in startIndex..total) {
            checkPause()
            if (!isRunning.get()) break
            appendLogScreen("\n─── 第 $i / $total 轮 ───")
            val result = runOnce()
            currentCount = i
            if (result) successCount++ else failCount++
            handler.post { updateCountUI() }

            // 每轮后保存状态（断电恢复用）
            saveState(remaining = total - i, runMode = runMode)

            // 轮间延时（每轮结束后等待，控制测试节奏）
            if (i < total && isRunning.get()) {
                val delay = readEditText(etStartDelay).toLongOrNull() ?: 0L
                if (delay > 0) {
                    appendLogScreen("轮间延时 ${delay}ms ...")
                    Thread.sleep(delay)
                }
            }
        }

        if (isRunning.get()) {
            appendLog("\n====== 全部测试完成 ======")
            appendLog("成功: $successCount  失败: $failCount  总计: $currentCount")
            clearAutoState()
        }
        closeReader()
        isRunning.set(false)
        handler.post { setUIRunning(false) }
    }

    // ── 模式2：每轮重启 ───────────────────────────────────────
    /**
     * 模式2专用：执行一轮测试，然后重启系统
     * @param roundIndex  当前是第几轮
     * @param remaining   剩余次数（含本轮）
     */
    private fun runSingleRoundAndReboot(roundIndex: Int, remaining: Int) {
        checkPause()
        if (!isRunning.get()) return

        appendLogScreen("\n─── 第 $roundIndex 轮 ───")
        val result = runOnce()
        currentCount = roundIndex
        if (result) successCount++ else failCount++
        handler.post { updateCountUI() }

        val newRemaining = remaining - 1

        // 更新剩余次数
        saveState(remaining = newRemaining, runMode = 2)

        if (!isRunning.get()) {
            // 手动停止了
            closeReader()
            isRunning.set(false)
            handler.post { setUIRunning(false) }
            return
        }

        if (newRemaining <= 0) {
            // 所有测试完成
            appendLog("\n====== 全部测试完成 ======")
            appendLog("成功: $successCount  失败: $failCount  总计: $currentCount")
            clearAutoState()
            closeReader()
            isRunning.set(false)
            handler.post { setUIRunning(false) }
            return
        }

        // 还有剩余次数，重启继续
        appendLogScreen("[模式2] 本轮完成，剩余 $newRemaining 次，准备重启系统...")
        closeReader()
        // 轮间延时（重启前等待）
        val rebootDelay = readEditText(etStartDelay).toLongOrNull() ?: 2000L
        if (rebootDelay > 0) {
            appendLogScreen("重启前等待 ${rebootDelay}ms ...")
            Thread.sleep(rebootDelay)
        }
        rebootDevice()
    }

    /** 开机恢复后继续模式2循环（逐轮重启） */
    private fun continueTestLoop(remaining: Int, total: Int, runMode: Int) {
        if (runMode == 2) {
            // 模式2：跑一轮后重启
            val roundIndex = currentCount + 1
            runSingleRoundAndReboot(roundIndex = roundIndex, remaining = remaining)
        } else {
            // 模式1：断电恢复，继续循环
            runTestLoop(total, runMode = 1)
        }
    }

    /** 执行一轮所有勾选的测试项，全部成功返回 true */
    /** 遇错停止检查：勾选后任一测试项失败即终止测试 */
    private fun checkStopOnError() {
        if (getCheckedFlag(cbStopOnError)) {
            isRunning.set(false)
            appendLog("====== 遇错停止测试 ======")
            clearAutoState()
        }
    }

    /** 执行一轮所有勾选的测试项，全部成功返回 true */
    private fun runOnce(): Boolean {
        var allOk = true

        if (getCheckedFlag(cbIdCard)) {
            val ok = testIdCard()
            if (ok) appendLogScreen("身份证: ✓ 成功") else { appendLog("身份证: ✗ 失败"); allOk = false; checkStopOnError(); if (!isRunning.get()) return false }
            checkPause(); if (!isRunning.get()) return false
        }
        if (getCheckedFlag(cbContactlessCard)) {
            val ok = testContactlessCard()
            if (ok) appendLogScreen("非接触CPU卡: ✓ 成功") else { appendLog("非接触CPU卡: ✗ 失败"); allOk = false; checkStopOnError(); if (!isRunning.get()) return false }
            checkPause(); if (!isRunning.get()) return false
        }
        if (getCheckedFlag(cbContactCard)) {
            val ok = testContactCard()
            if (ok) appendLogScreen("接触CPU卡: ✓ 成功") else { appendLog("接触CPU卡: ✗ 失败"); allOk = false; checkStopOnError(); if (!isRunning.get()) return false }
            checkPause(); if (!isRunning.get()) return false
        }
        if (getCheckedFlag(cbEthernet)) {
            val ok = testEthernet()
            if (ok) appendLogScreen("以太网PING: ✓ 成功") else { appendLog("以太网PING: ✗ 失败"); allOk = false; checkStopOnError(); if (!isRunning.get()) return false }
            checkPause(); if (!isRunning.get()) return false
        }
        if (getCheckedFlag(cbBarcode)) {
            val ok = testBarcode()
            if (ok) appendLogScreen("扫码: ✓ 成功") else { appendLog("扫码: ✗ 失败"); allOk = false; checkStopOnError(); if (!isRunning.get()) return false }
            checkPause(); if (!isRunning.get()) return false
        }

        if (allOk) appendLogScreen("本轮结果: 【全部成功】") else appendLog("本轮结果: 【有失败项】")
        return allOk
    }

    // ── 各测试项实现 ──────────────────────────────────────────

    /** 打开读卡器 */
    private fun openReader() {
        try {
            BasicOper.dc_AUSB_ReqPermission(this)
            Thread.sleep(500)
            devHandle = BasicOper.dc_open("AUSB", this, "", 0)
            appendLogScreen("读卡器打开: ${if (devHandle > 0) "成功(handle=$devHandle)" else "失败(code=$devHandle)"}")
        } catch (e: Exception) {
            appendLogScreen("读卡器打开异常: ${e.message}")
        }
    }

    /** 关闭读卡器 */
    private fun closeReader() {
        try {
            if (devHandle > 0) {
                BasicOper.dc_exit()
                devHandle = -1
                appendLogScreen("读卡器已关闭")
            }
        } catch (e: Exception) { /* ignore */ }
    }

    /**
     * 身份证读取：使用 SDK 封装的 IDCard 对象方式
     */
    private fun testIdCard(): Boolean {
        if (devHandle <= 0) return false

        // 1. 读取身份证序列号
        try {
            val idSnr = BasicOper.dc_get_idsnr()
            val idSnrParts = idSnr?.split("\\|".toRegex())
            if (idSnrParts == null || idSnrParts.size < 2 || idSnrParts[0] != "0000") {
                appendLog("  取序列号失败: $idSnr")
                return false
            }
            appendLogScreen("  身份证序列号: ${idSnrParts[1]}")
        } catch (e: Exception) {
            appendLog("  取序列号异常: ${e.message}")
            return false
        }

        // 2. 读取身份证信息（SDK 封装方式）
        try {
            val idCard: IDCard? = BasicOper.dc_SamAReadCardInfo(1)
            if (idCard == null) {
                appendLog("  读身份证失败")
                return false
            }
            val name = idCard.name?.trim() ?: ""
            val idNum = idCard.id?.trim() ?: ""
            if (name.isBlank() || idNum.isBlank()) {
                appendLog("  身份证信息不完整: name='$name' id='$idNum'")
                return false
            }
            appendLogScreen("  姓名: $name | 身份证号: $idNum")
            return true
        } catch (e: Exception) {
            appendLog("  读身份证异常: ${e.message}")
            return false
        }
    }

    private fun testContactlessCard(): Boolean {
        if (devHandle <= 0) {
            appendLog("  devHandle <= 0")
            return false
        }

        var allOk = true

        // 1. 配置卡类型（Type A），失败不阻断（部分设备不需要此步）
        try {
            val configRet = BasicOper.dc_config_card(0x00)
            val configParts = configRet?.split("\\|".toRegex())
            if (configParts != null && configParts.size >= 2) {
                if (configParts[0] == "0000") {
                    appendLogScreen("  dc_config_card: OK")
                } else {
                    appendLogScreen("  dc_config_card: ${configParts[0]} (非致命，继续)")
                }
            }
        } catch (e: Exception) {
            appendLogScreen("  dc_config_card 异常(非致命): ${e.message}")
        }

        // 2. 射频复位
        try {
            val resetRf = BasicOper.dc_reset()
            val resetRfParts = resetRf?.split("\\|".toRegex())
            if (resetRfParts == null || resetRfParts.size < 2 || resetRfParts[0] != "0000") {
                appendLog("  射频复位失败: $resetRf")
                return false
            }
        } catch (e: Exception) {
            appendLog("  dc_reset 异常: ${e.message}")
            return false
        }

        // 3. 寻卡（Type A）
        try {
            val findRet = BasicOper.dc_card_n_hex(0x01)
            val parts = findRet?.split("\\|".toRegex())
            if (parts == null || parts.size < 2 || parts[0] != "0000") {
                appendLog("  寻卡失败: $findRet")
                return false
            }
            appendLogScreen("  寻卡成功: ${parts[1]}")
        } catch (e: Exception) {
            appendLog("  寻卡异常: ${e.message}")
            return false
        }

        // 4. 非接触CPU卡复位
        try {
            val resetRet = BasicOper.dc_pro_resetInt_hex()
            val resetParts = resetRet?.split("\\|".toRegex())
            if (resetParts == null || resetParts.size < 2 || resetParts[0] != "0000") {
                appendLog("  复位失败: $resetRet")
                return false
            }
            appendLogScreen("  复位ATR: ${resetParts[1]}")
        } catch (e: Exception) {
            appendLog("  复位异常: ${e.message}")
            return false
        }

        // 5. 发送APDU取随机数
        try {
            val randRet = BasicOper.dc_procommandInt_hex("0084000008", 7)
            val randParts = randRet?.split("\\|".toRegex())
            if (randParts == null || randParts.size < 2 || randParts[0] != "0000") {
                appendLog("  取随机数失败: $randRet")
                allOk = false
            } else {
                appendLogScreen("  随机数: ${randParts[1]}")
            }
        } catch (e: Exception) {
            appendLog("  APDU异常: ${e.message}")
            allOk = false
        }

        // 6. 下电（释放卡片，避免下轮寻卡冲突）
        try {
            BasicOper.dc_pro_halt()
        } catch (_: Exception) {}

        return allOk
    }

    private fun testContactCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            val resetRet = BasicOper.dc_cpureset_hex()
            if (resetRet.isNullOrBlank() || isError(resetRet)) {
                appendLog("  接触卡复位失败: $resetRet")
                return false
            }
            appendLogScreen("  复位ATR: $resetRet")
            val randRet = BasicOper.dc_cpuapduInt_hex("0084000008")
            if (randRet.isNullOrBlank() || isError(randRet)) {
                appendLog("  取随机数失败: $randRet")
                return false
            }
            appendLogScreen("  随机数: $randRet")
            true
        } catch (e: Exception) {
            appendLog("  接触CPU卡测试异常: ${e.message}")
            false
        }
    }

    private fun testEthernet(): Boolean {
        return try {
            updateNetworkInfo()
            val host = etPingHost.text.toString().trim().ifBlank { "www.baidu.com" }
            val count = etPingCount.text.toString().toIntOrNull() ?: 10
            var okCount = 0
            for (i in 1..count) {
                checkPause()
                if (!isRunning.get()) return false
                val reachable = InetAddress.getByName(host).isReachable(3000)
                if (reachable) okCount++
            }
            appendLogScreen("  PING $host: $okCount/$count 成功")
            okCount == count
        } catch (e: Exception) {
            appendLog("  以太网测试异常: ${e.message}")
            false
        }
    }

    /** 扫码样本确认弹窗 */
    private fun showBarcodeConfirmDialog(scanned: String) {
        // 已确认样本时不重复弹窗（避免开机恢复时残留的延时任务触发）
        if (barcodeSampleConfirmed) return
        barcodeAutoConfirmHandler.removeCallbacks(barcodeAutoConfirmRunnable)
        handler.post {
            AlertDialog.Builder(this)
                .setTitle("样本码确认")
                .setMessage("检测到样本码：\n$scanned\n\n确认使用此样本码进行扫码测试？")
                .setPositiveButton("确认") { _, _ ->
                    barcodeSampleConfirmed = true
                    etBarcode.setText(scanned)
                    etBarcode.isEnabled = false
                    toast("样本码已确认")
                }
                .setNegativeButton("取消") { _, _ ->
                    barcodeAutoConfirmHandler.removeCallbacks(barcodeAutoConfirmRunnable)
                    barcodeSampleConfirmed = false
                    // 先禁用编辑框阻止扫码枪残余按键输入，再清空文本
                    etBarcode.isEnabled = false
                    etBarcode.text.clear()
                    // 等残余按键排空后再恢复（500ms 足以消化按键重复）
                    Handler(Looper.getMainLooper()).postDelayed({
                        etBarcode.isEnabled = true
                        etBarcode.requestFocus()
                    }, 500)
                    // 测试运行时取消确认 → 终止测试（无样本无法继续）
                    if (isRunning.get()) {
                        isRunning.set(false)
                        synchronized(pauseLock) { pauseLock.notifyAll() }
                        clearAutoState()
                        appendLog("====== 因取消样本二维码确认，测试终止 ======")
                        setUIRunning(false)
                    }
                }
                .setCancelable(false)
                .show()
        }
    }

    /** 跨线程读取 EditText 文本 */
    private fun readEditText(et: EditText): String {
        var text = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post { text = et.text.toString(); latch.countDown() }
        try { latch.await() } catch (_: InterruptedException) {}
        return text.trim()
    }

    private fun testBarcode(): Boolean {
        val sample = etBarcode.text.toString().trim()
        if (sample.isBlank() || !barcodeSampleConfirmed) {
            appendLogScreen("  无有效样本二维码（未配置或未确认），扫码测试失败")
            // 无有效样本时无条件终止测试（防止模式2无限重启循环）
            isRunning.set(false)
            synchronized(pauseLock) { pauseLock.notifyAll() }
            clearAutoState()
            appendLog("====== 因无有效样本二维码终止测试 ======")
            handler.post { setUIRunning(false) }
            return false
        }
        return try {
            // 清空编辑框，抑制软键盘，获取焦点等待扫码
            handler.post {
                etBarcode.isEnabled = true
                etBarcode.showSoftInputOnFocus = false
                etBarcode.inputType = android.text.InputType.TYPE_NULL
                etBarcode.text.clear()
                etBarcode.requestFocus()
            }
            Thread.sleep(300)

            // 再清空一次，排空确认弹窗期间扫码枪可能的残余按键输入
            handler.post { etBarcode.text.clear() }
            Thread.sleep(100)

            // 轮询等待扫码数据，200ms 文本无变化即认为接收完毕，10s 无数据则超时
            var scanned = ""
            var lastText = ""
            var lastChangeTime = 0L
            val startTime = System.currentTimeMillis()
            val DATA_TIMEOUT = 10000L
            val STABLE_MS = 200L
            while (isRunning.get()) {
                Thread.sleep(50)
                val current = readEditText(etBarcode)
                val now = System.currentTimeMillis()
                if (current.isNotEmpty()) {
                    if (current != lastText) {
                        lastText = current
                        lastChangeTime = now
                    } else if (now - lastChangeTime >= STABLE_MS) {
                        scanned = current
                        break
                    }
                } else if (now - startTime >= DATA_TIMEOUT) {
                    break
                }
            }

            handler.post { etBarcode.isEnabled = false }

            if (scanned.isEmpty()) {
                appendLog("  扫码超时，未检测到扫码数据")
                return false
            }

            appendLogScreen("  扫码结果: $scanned | 样本: $sample")
            scanned == sample
        } catch (e: Exception) {
            appendLog("  扫码测试异常: ${e.message}")
            false
        }
    }

    // ── 重启辅助 ──────────────────────────────────────────────

    /**
     * 重启设备：依次尝试多种方式
     * 1. DevicePolicyManager.reboot()（需设备所有者）
     * 2. su -c reboot（需要 root）
     * 3. PowerManager.reboot（需要系统签名）
     * 4. 均失败则降级为模式1继续循环
     */
    private fun rebootDevice() {
        // 方式1：DevicePolicyManager（需通过 adb 设为设备所有者）
        if (deviceOwnerReboot()) return

        // 方式2：shell su reboot（需要 root）
        if (shellReboot()) return

        // 方式3：PowerManager API（需要系统签名 APK）
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.reboot(null)
            return
        } catch (_: Exception) {}

        // 方式4：直接 reboot 命令
        try {
            Runtime.getRuntime().exec(arrayOf("reboot"))
            return
        } catch (_: Exception) {}

        appendLog("[模式2] ⚠ 自动重启失败（无root/系统签名/设备所有者权限）")
        appendLog("[模式2] 降级为不断电循环模式继续测试")

        // 降级：直接继续下一轮循环（不重启系统）
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = prefs.getInt(PREF_REMAINING, 0)
        val total = prefs.getInt(PREF_TOTAL, 0)
        if (remaining > 0) {
            appendLog("[模式2] 继续第 ${currentCount + 1} 轮（无重启）")
            continueTestLoop(remaining, total, runMode = 1)
        }
    }

    /** 通过 DevicePolicyManager 重启（需设备所有者权限） */
    private fun deviceOwnerReboot(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.reboot(android.content.ComponentName(this, DeviceAdminReceiver::class.java))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 尝试通过 su 执行 reboot 命令 */
    private fun shellReboot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            val exitCode = process.waitFor()
            // exitCode=0 表示 su 命令本身执行成功（reboot 会立即生效，通常不会返回）
            exitCode == 0 || exitCode == 143 // 143 = SIGTERM，正常
        } catch (_: Exception) {
            false
        }
    }

    // ── 日志相关 ──────────────────────────────────────────────

    /** 获取设备 IPv4 地址 */
    private fun getDeviceIpv4(): String {
        var ipv4 = "--"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    if (addr is InetAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (host != null && host.contains(".") && ipv4 == "--") {
                            ipv4 = host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ipv4
    }

    /** 更新网络信息固定显示区（独立于日志） */
    private fun updateNetworkInfo() {
        val ipv4 = getDeviceIpv4()
        handler.post {
            tvIpAddress.text = "IP: $ipv4"
            findViewById<View>(R.id.layoutNetworkInfo).visibility = View.VISIBLE
        }
    }

    /** 写入文件 + 屏幕显示（摘要/结果行） */
    fun appendLog(msg: String) {
        val line = "[${sdf.format(Date())}] $msg"
        try { currentLogFile?.appendText(line + "\n") } catch (_: Exception) {}
        appendToScreen(line, msg)
    }

    /** 仅屏幕显示（调试详情行，不写入历史日志文件） */
    fun appendLogScreen(msg: String) {
        val line = "[${sdf.format(Date())}] $msg"
        appendToScreen(line, msg)
    }

    /** 共享的屏幕缓冲区逻辑 */
    private fun appendToScreen(line: String, msg: String) {
        val isNewRound = msg.contains("─── 第") && msg.contains("轮 ───")

        synchronized(logRoundBuffers) {
            if (isNewRound && currentRoundBuffer.isNotEmpty()) {
                logRoundBuffers.add(currentRoundBuffer.toString())
                currentRoundBuffer = StringBuilder()
                while (logRoundBuffers.size > MAX_DISPLAY_ROUNDS - 1) {
                    logRoundBuffers.removeAt(0)
                }
            }
            currentRoundBuffer.append(line).append("\n")
        }

        handler.post {
            synchronized(logRoundBuffers) {
                val display = buildString {
                    for (round in logRoundBuffers) append(round)
                    append(currentRoundBuffer)
                }
                tvLog.text = display
            }
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun viewLogs() {
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            toast("暂无日志文件")
            return
        }
        val names = files.map { it.name }.toTypedArray()
        val checked = BooleanArray(files.size) { false }

        AlertDialog.Builder(this)
            .setTitle("历史日志（勾选后可删除）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("查看") { _, _ ->
                val idx = checked.indexOfFirst { it }
                if (idx < 0) { toast("请先勾选一个文件查看"); return@setPositiveButton }
                val content = try { files[idx].readText() } catch (e: Exception) { "读取失败: ${e.message}" }
                AlertDialog.Builder(this)
                    .setTitle(names[idx])
                    .setMessage(content)
                    .setPositiveButton("关闭", null)
                    .show()
            }
            .setNeutralButton("删除选中") { _, _ ->
                val toDelete = files.filterIndexed { i, _ -> checked[i] }
                if (toDelete.isEmpty()) { toast("未勾选任何文件"); return@setNeutralButton }
                AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("即将删除 ${toDelete.size} 个日志文件，确认？")
                    .setPositiveButton("删除") { _, _ ->
                        var delCount = 0
                        toDelete.forEach { f -> if (f.delete()) delCount++ }
                        toast("已删除 $delCount 个日志文件")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private fun getCheckedFlag(cb: CheckBox): Boolean {
        var v = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            v = cb.isChecked
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post { v = cb.isChecked; latch.countDown() }
            latch.await()
        }
        return v
    }

    private fun getModeName(): String = when {
        rbModeReboot.isChecked -> "每次重启"
        else -> "不断电循环"
    }

    private fun isError(ret: String?): Boolean {
        if (ret.isNullOrBlank()) return true
        val up = ret.uppercase()
        return up.startsWith("ERR") || up.startsWith("FFFE") || up.startsWith("FFFF")
    }

    /**
     * 阻塞当前（executor）线程直到恢复，或 isRunning 变为 false（停止时唤醒）
     */
    private fun checkPause() {
        while (isPaused.get() && isRunning.get()) {
            appendLogScreen("--- 测试已暂停 ---")
            while (isPaused.get() && isRunning.get()) {
                synchronized(pauseLock) {
                    try {
                        pauseLock.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            if (isRunning.get() && !isPaused.get()) {
                appendLogScreen("--- 测试继续 ---")
            }
        }
    }

    private fun setUIRunning(running: Boolean) {
        btnStart.isEnabled = true   // 三态按钮：开始/暂停/继续，始终可点
        btnStop.isEnabled = running
        rgMode.isEnabled = !running
        etTotalCount.isEnabled = !running
        etStartDelay.isEnabled = !running
        tvStatus.text = if (running) "● 测试中" else "● 空闲"
        tvStatus.setTextColor(if (running) 0xFFFF8F00.toInt() else 0xFFA5D6A7.toInt())
        if (running) {
            btnStart.text = "暂停测试"
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1565C0"))
        } else {
            btnStart.text = "开始测试"
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1565C0"))
            isPaused.set(false)
        }
    }

    private fun updateCountUI() {
        tvCurrentCount.text = currentCount.toString()
        tvSuccessCount.text = successCount.toString()
        tvFailCount.text = failCount.toString()
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        isRunning.set(false)
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        closeReader()
        super.onDestroy()
    }
}
