package com.decard.f11aging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.decard.NDKMethod.BasicOper
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
    private lateinit var layoutBarcodeInput: View
    private lateinit var etBarcodeRef: EditText
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

    // 日志轮次缓冲（最近 10 轮日志，独立于文件写入）
    private val logRoundBuffers = mutableListOf<String>()
    private var currentRoundBuffer = StringBuilder()
    private val MAX_DISPLAY_ROUNDS = 10

    private var devHandle: Int = -1
    private var currentCount = 0
    private var successCount = 0
    private var failCount = 0

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
    private val PREF_BARCODE_REF = "auto_barcode_ref"
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
        layoutBarcodeInput = findViewById(R.id.layoutBarcodeInput)
        etBarcodeRef = findViewById(R.id.etBarcodeRef)
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
        }

        btnStart.setOnClickListener { startTesting() }
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
            putString(PREF_BARCODE_REF, etBarcodeRef.text.toString())
            putString(PREF_LOG_FILE, currentLogFile?.absolutePath ?: "")
            apply()
        }
    }

    /** 清除自动模式标志（测试完成或手动停止时调用） */
    private fun clearAutoState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_MODE, false)
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
        val delay = prefs.getInt(PREF_DELAY, 0)

        // 剩余为0，任务已完成
        if (remaining <= 0) {
            clearAutoState()
            appendLog("[恢复] 所有测试已完成")
            return
        }

        val modeName = if (runMode == 2) "模式2(每次重启)" else "模式1(不断电)"
        appendLog("[恢复] 开机检测到未完成测试，模式=$modeName，剩余 $remaining/$total 次")

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
        cbContactlessCard.isChecked = prefs.getBoolean(PREF_CB_CONTACTLESS, true)
        cbContactCard.isChecked = prefs.getBoolean(PREF_CB_CONTACT, false)
        cbEthernet.isChecked = prefs.getBoolean(PREF_CB_ETHERNET, true)
        cbBarcode.isChecked = prefs.getBoolean(PREF_CB_BARCODE, false)
        etBarcodeRef.setText(prefs.getString(PREF_BARCODE_REF, ""))
        etPingHost.setText(prefs.getString(PREF_PING_HOST, "www.baidu.com"))
        etPingCount.setText(prefs.getString(PREF_PING_COUNT, "10"))

        // 更新统计 UI
        updateCountUI()
        tvTotalCount.text = total.toString()

        // 延时后继续测试
        handler.postDelayed({ resumeTesting(remaining, total, runMode) }, delay.toLong())
    }

    /** 开机恢复后继续测试 */
    private fun resumeTesting(remaining: Int, total: Int, runMode: Int) {
        if (isRunning.get()) return

        // 如果没有已保存的日志文件（断电导致文件丢失等），才创建新文件
        if (currentLogFile == null || !currentLogFile!!.exists()) {
            val ts = sdfFile.format(Date())
            currentLogFile = File(logDir, "aging_${ts}.txt")
        }
        val ts = sdf.format(Date())
        appendLog("\n====== 恢复测试 [$ts] ======")

        isRunning.set(true)
        setUIRunning(true)
        updateNetworkInfo()

        executor.submit { openReader() }
        val delay = etStartDelay.text.toString().toLongOrNull() ?: 0L
        executor.submit {
            if (delay > 0) {
                appendLog("等待启动延时 ${delay}ms ...")
                Thread.sleep(delay)
            }
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
        if (cbBarcode.isChecked && etBarcodeRef.text.toString().isBlank()) {
            toast("请先扫描样本二维码再开始测试"); return
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

        isRunning.set(true)
        setUIRunning(true)
        updateNetworkInfo()

        executor.submit { openReader() }
        val delay = etStartDelay.text.toString().toLongOrNull() ?: 0L
        executor.submit {
            if (delay > 0) {
                appendLog("等待启动延时 ${delay}ms ...")
                Thread.sleep(delay)
            }
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
        clearAutoState()
        appendLog("====== 测试已手动停止 ======")
        handler.post {
            findViewById<View>(R.id.layoutNetworkInfo).visibility = View.GONE
            setUIRunning(false)
        }
    }

    // ── 模式1：不断电循环 ─────────────────────────────────────
    private fun runTestLoop(total: Int, runMode: Int) {
        val startIndex = currentCount + 1

        for (i in startIndex..total) {
            if (!isRunning.get()) break
            appendLog("\n─── 第 $i / $total 轮 ───")
            val result = runOnce()
            currentCount = i
            if (result) successCount++ else failCount++
            handler.post { updateCountUI() }

            // 每轮后保存状态（断电恢复用）
            saveState(remaining = total - i, runMode = runMode)
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
        if (!isRunning.get()) return

        appendLog("\n─── 第 $roundIndex 轮 ───")
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
        appendLog("[模式2] 本轮完成，剩余 $newRemaining 次，准备重启系统...")
        closeReader()
        Thread.sleep(2000)
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
    private fun runOnce(): Boolean {
        var allOk = true

        if (getCheckedFlag(cbIdCard)) {
            val ok = testIdCard()
            appendLog("身份证: ${if (ok) "✓ 成功" else "✗ 失败"}")
            if (!ok) allOk = false
        }
        if (getCheckedFlag(cbContactlessCard)) {
            val ok = testContactlessCard()
            appendLog("非接触CPU卡: ${if (ok) "✓ 成功" else "✗ 失败"}")
            if (!ok) allOk = false
        }
        if (getCheckedFlag(cbContactCard)) {
            val ok = testContactCard()
            appendLog("接触CPU卡: ${if (ok) "✓ 成功" else "✗ 失败"}")
            if (!ok) allOk = false
        }
        if (getCheckedFlag(cbEthernet)) {
            val ok = testEthernet()
            appendLog("以太网PING: ${if (ok) "✓ 成功" else "✗ 失败"}")
            if (!ok) allOk = false
        }
        if (getCheckedFlag(cbBarcode)) {
            val ok = testBarcode()
            appendLog("扫码: ${if (ok) "✓ 成功" else "✗ 失败"}")
            if (!ok) allOk = false
        }

        appendLog("本轮结果: ${if (allOk) "【全部成功】" else "【有失败项】"}")
        return allOk
    }

    // ── 各测试项实现 ──────────────────────────────────────────

    /** 打开读卡器 */
    private fun openReader() {
        try {
            BasicOper.dc_AUSB_ReqPermission(this)
            Thread.sleep(500)
            devHandle = BasicOper.dc_open("AUSB", this, "", 0)
            appendLog("读卡器打开: ${if (devHandle > 0) "成功(handle=$devHandle)" else "失败(code=$devHandle)"}")
        } catch (e: Exception) {
            appendLog("读卡器打开异常: ${e.message}")
        }
    }

    /** 关闭读卡器 */
    private fun closeReader() {
        try {
            if (devHandle > 0) {
                BasicOper.dc_exit()
                devHandle = -1
                appendLog("读卡器已关闭")
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun testIdCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            val idCard: IDCard? = BasicOper.dc_IdCardReadCardInfo(1)
            if (idCard == null) return false
            val name = idCard.name ?: ""
            val id = idCard.id ?: ""
            val ok = name.isNotBlank() && id.isNotBlank()
            if (ok) appendLog("  姓名: $name | 身份证号: $id")
            else appendLog("  身份证信息为空: name=$name, id=$id")
            ok
        } catch (e: Exception) {
            appendLog("  身份证测试异常: ${e.message}")
            false
        }
    }

    private fun testContactlessCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            val findRet = BasicOper.dc_card_hex(0x01)
            if (findRet.isNullOrBlank() || isError(findRet)) {
                appendLog("  寻卡失败: $findRet")
                return false
            }
            appendLog("  寻卡: $findRet")
            val resetRet = BasicOper.dc_pro_resethex()
            if (resetRet.isNullOrBlank() || isError(resetRet)) {
                appendLog("  复位失败: $resetRet")
                return false
            }
            appendLog("  复位ATR: $resetRet")
            val randRet = BasicOper.dc_procommandInt_hex("0084000008", 7)
            if (randRet.isNullOrBlank() || isError(randRet)) {
                appendLog("  取随机数失败: $randRet")
                return false
            }
            appendLog("  随机数: $randRet")
            true
        } catch (e: Exception) {
            appendLog("  非接触CPU卡测试异常: ${e.message}")
            false
        }
    }

    private fun testContactCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            val resetRet = BasicOper.dc_cpureset_hex()
            if (resetRet.isNullOrBlank() || isError(resetRet)) {
                appendLog("  接触卡复位失败: $resetRet")
                return false
            }
            appendLog("  复位ATR: $resetRet")
            val randRet = BasicOper.dc_cpuapduInt_hex("0084000008")
            if (randRet.isNullOrBlank() || isError(randRet)) {
                appendLog("  取随机数失败: $randRet")
                return false
            }
            appendLog("  随机数: $randRet")
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
                if (!isRunning.get()) return false
                val reachable = InetAddress.getByName(host).isReachable(3000)
                if (reachable) okCount++
            }
            appendLog("  PING $host: $okCount/$count 成功")
            okCount == count
        } catch (e: Exception) {
            appendLog("  以太网测试异常: ${e.message}")
            false
        }
    }

    private fun testBarcode(): Boolean {
        return try {
            val ref = etBarcodeRef.text.toString().trim()
            if (ref.isBlank()) {
                appendLog("  未设置样本二维码")
                return false
            }
            var scanned = ""
            handler.post { etBarcodeRef.requestFocus() }
            Thread.sleep(3000)
            handler.post { scanned = etBarcodeRef.text.toString().trim() }
            Thread.sleep(100)
            appendLog("  扫码结果: $scanned | 样本: $ref")
            scanned == ref
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

    fun appendLog(msg: String) {
        val line = "[${sdf.format(Date())}] $msg"
        // 写入文件（完整日志始终保留）
        try { currentLogFile?.appendText(line + "\n") } catch (_: Exception) {}

        // 检测新轮次开始（格式：─── 第 X / Y 轮 ───）
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

    private fun setUIRunning(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        rgMode.isEnabled = !running
        etTotalCount.isEnabled = !running
        etStartDelay.isEnabled = !running
        tvStatus.text = if (running) "● 测试中" else "● 空闲"
        tvStatus.setTextColor(if (running) 0xFFFF8F00.toInt() else 0xFFA5D6A7.toInt())
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
        closeReader()
        super.onDestroy()
    }
}
