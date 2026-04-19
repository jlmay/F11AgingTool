package com.decard.f11aging

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.decard.NDKMethod.BasicOper
import com.decard.entitys.IDCard
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F11-N 底座老化测试工具 — 主界面
 *
 * 测试项：身份证、非接触CPU卡、接触CPU卡、以太网PING、扫码
 * 老化模式：普通循环 / 每次重启 / 开机自动
 */
class MainActivity : AppCompatActivity() {

    // ── UI 控件 ───────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbModeNormal: RadioButton
    private lateinit var rbModeReboot: RadioButton
    private lateinit var rbModeAuto: RadioButton
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

    // ── 状态 ─────────────────────────────────────────────────
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var devHandle: Int = -1
    private var currentCount = 0
    private var successCount = 0
    private var failCount = 0

    // 模式3：等待断电标志
    private val PREF_WAITING_POWEROFF = "auto_waiting_poweroff"
    // 模式3：剩余次数（0 = 无限）
    private val PREF_INFINITE = "auto_infinite"

    // 日志文件路径
    private val logDir by lazy {
        File(getExternalFilesDir(null), "logs").also { it.mkdirs() }
    }
    private var currentLogFile: File? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // ── 开机自动模式标志 ──────────────────────────────────────
    private val PREFS_NAME = "f11aging_prefs"
    private val PREF_AUTO_MODE = "auto_mode_enabled"
    private val PREF_REMAINING = "auto_mode_remaining"
    private val PREF_TOTAL = "auto_mode_total"
    private val PREF_DELAY = "auto_mode_delay"
    private val PREF_PING_HOST = "auto_ping_host"
    private val PREF_PING_COUNT = "auto_ping_count"
    private val PREF_CB_IDCARD = "auto_cb_idcard"
    private val PREF_CB_CONTACTLESS = "auto_cb_contactless"
    private val PREF_CB_CONTACT = "auto_cb_contact"
    private val PREF_CB_ETHERNET = "auto_cb_ethernet"
    private val PREF_CB_BARCODE = "auto_cb_barcode"
    private val PREF_BARCODE_REF = "auto_barcode_ref"

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
        // 动态写入版本号，格式：F11-N 底座老化测试工具 v1.0
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        tvTitle.text = "F11-N 底座老化测试工具 v$versionName"
        rgMode = findViewById(R.id.rgMode)
        rbModeNormal = findViewById(R.id.rbModeNormal)
        rbModeReboot = findViewById(R.id.rbModeReboot)
        rbModeAuto = findViewById(R.id.rbModeAuto)
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

    private fun savePrefs(isAutoMode: Boolean = false, infinite: Boolean = false, total: Int = 0) {
        val realTotal = etTotalCount.text.toString().toIntOrNull() ?: total
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_AUTO_MODE, isAutoMode)
            putBoolean(PREF_INFINITE, infinite)
            putInt(PREF_REMAINING, realTotal)
            putInt(PREF_TOTAL, realTotal)
            putInt(PREF_DELAY, etStartDelay.text.toString().toIntOrNull() ?: 0)
            putString(PREF_PING_HOST, etPingHost.text.toString())
            putString(PREF_PING_COUNT, etPingCount.text.toString())
            putBoolean(PREF_CB_IDCARD, cbIdCard.isChecked)
            putBoolean(PREF_CB_CONTACTLESS, cbContactlessCard.isChecked)
            putBoolean(PREF_CB_CONTACT, cbContactCard.isChecked)
            putBoolean(PREF_CB_ETHERNET, cbEthernet.isChecked)
            putBoolean(PREF_CB_BARCODE, cbBarcode.isChecked)
            putString(PREF_BARCODE_REF, etBarcodeRef.text.toString())
            apply()
        }
    }

    // ── 开机自动模式检查 ──────────────────────────────────────
    private fun checkAutoStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_AUTO_MODE, false)) return

        val remaining = prefs.getInt(PREF_REMAINING, 0)
        val infinite = prefs.getBoolean(PREF_INFINITE, false)
        val total = prefs.getInt(PREF_TOTAL, 100)
        val delay = prefs.getInt(PREF_DELAY, 0)

        // 非无限模式且剩余为0，表示任务已完成
        if (!infinite && remaining <= 0) {
            prefs.edit().putBoolean(PREF_AUTO_MODE, false).apply()
            appendLog("[自动] 所有测试已完成，退出自动模式")
            return
        }

        val remainText = if (infinite) "∞" else "$remaining/$total"
        appendLog("[自动] 开机检测到自动模式，剩余 $remainText 次，延时 ${delay}ms 后开始本轮测试...")

        // 恢复 UI 配置
        rbModeAuto.isChecked = true
        etTotalCount.setText(if (infinite) "0" else remaining.toString())
        etStartDelay.setText(delay.toString())
        cbIdCard.isChecked = prefs.getBoolean(PREF_CB_IDCARD, true)
        cbContactlessCard.isChecked = prefs.getBoolean(PREF_CB_CONTACTLESS, true)
        cbContactCard.isChecked = prefs.getBoolean(PREF_CB_CONTACT, false)
        cbEthernet.isChecked = prefs.getBoolean(PREF_CB_ETHERNET, true)
        cbBarcode.isChecked = prefs.getBoolean(PREF_CB_BARCODE, false)
        etBarcodeRef.setText(prefs.getString(PREF_BARCODE_REF, ""))
        etPingHost.setText(prefs.getString(PREF_PING_HOST, "www.baidu.com"))
        etPingCount.setText(prefs.getString(PREF_PING_COUNT, "10"))

        // 延时后执行单轮（模式3每次开机只跑一轮）
        handler.postDelayed({ startOnceForAutoMode() }, delay.toLong())
    }

    // ── 开始/停止测试 ────────────────────────────────────────
    private fun startTesting() {
        if (isRunning.get()) return

        val isAutoMode = rbModeAuto.isChecked
        val inputCount = etTotalCount.text.toString().toIntOrNull() ?: 0
        // 模式3下，次数=0 表示无限循环；其他模式必须 > 0
        val infinite = isAutoMode && inputCount <= 0
        val total = if (infinite) Int.MAX_VALUE else inputCount

        if (!infinite && total <= 0) { toast("请设置有效的测试次数"); return }
        val hasTasks = cbIdCard.isChecked || cbContactlessCard.isChecked ||
                cbContactCard.isChecked || cbEthernet.isChecked || cbBarcode.isChecked
        if (!hasTasks) { toast("请至少勾选一个测试项"); return }
        if (cbBarcode.isChecked && etBarcodeRef.text.toString().isBlank()) {
            toast("请先扫描样本二维码再开始测试"); return
        }

        // 保存配置（含模式3无限标志）
        savePrefs(isAutoMode = isAutoMode, infinite = infinite, total = inputCount)

        // 初始化计数
        currentCount = 0; successCount = 0; failCount = 0
        updateCountUI()
        tvTotalCount.text = if (infinite) "∞" else total.toString()

        val ts = sdfFile.format(Date())
        currentLogFile = File(logDir, "aging_${ts}.txt")
        appendLog("====== 测试开始 [$ts] ======")
        appendLog("总次数=${if (infinite) "∞" else total} | 模式=${getModeName()} | 延时=${etStartDelay.text}ms")

        isRunning.set(true)
        setUIRunning(true)

        executor.submit { openReader() }
        val delay = etStartDelay.text.toString().toLongOrNull() ?: 0L
        executor.submit {
            if (delay > 0) {
                appendLog("等待启动延时 ${delay}ms ...")
                Thread.sleep(delay)
            }
            if (isAutoMode) {
                // 模式3：只跑一轮，然后进入等待断电
                runSingleRoundAndWait(roundIndex = 1, remaining = if (infinite) -1 else total - 1)
            } else {
                runTestLoop(total)
            }
        }
    }

    /**
     * 模式3专用：开机后由 checkAutoStart 调用，执行一轮测试后等待断电
     * 不重置计数器（断电前保留累计数据）
     */
    private fun startOnceForAutoMode() {
        if (isRunning.get()) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val infinite = prefs.getBoolean(PREF_INFINITE, false)
        val remaining = prefs.getInt(PREF_REMAINING, 0)

        val hasTasks = cbIdCard.isChecked || cbContactlessCard.isChecked ||
                cbContactCard.isChecked || cbEthernet.isChecked || cbBarcode.isChecked
        if (!hasTasks) { appendLog("[自动] 没有勾选测试项，取消自动运行"); return }

        // 不重置计数，只更新 UI 总数显示
        tvTotalCount.text = if (infinite) "∞" else remaining.toString()

        isRunning.set(true)
        setUIRunning(true)

        executor.submit { openReader() }
        val delay = etStartDelay.text.toString().toLongOrNull() ?: 0L
        executor.submit {
            if (delay > 0) Thread.sleep(delay)
            runSingleRoundAndWait(roundIndex = currentCount + 1,
                remaining = if (infinite) -1 else remaining - 1)
        }
    }

    private fun stopTesting() {
        isRunning.set(false)
        // 模式3手动停止，清除自动循环标志
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO_MODE, false)
            .putBoolean(PREF_WAITING_POWEROFF, false)
            .apply()
        appendLog("====== 测试已手动停止 ======")
        handler.post { setUIRunning(false) }
    }

    // ── 主测试循环（模式1/2用）────────────────────────────────
    private fun runTestLoop(total: Int) {
        val runMode = getRunMode()

        for (i in 1..total) {
            if (!isRunning.get()) break
            appendLog("\n─── 第 $i / $total 轮 ───")
            val result = runOnce()
            currentCount = i
            if (result) successCount++ else failCount++
            handler.post { updateCountUI() }

            // 重启模式：每轮后重启（非最后一轮）
            if (runMode == 2 && i < total) {
                if (!isRunning.get()) break
                appendLog("[模式2] 本轮完成，准备重启系统...")
                saveResumeState(total - i)
                Thread.sleep(2000)
                rebootDevice()
                return
            }
        }

        if (isRunning.get()) {
            appendLog("\n====== 全部测试完成 ======")
            appendLog("成功: $successCount  失败: $failCount  总计: $currentCount")
        }
        closeReader()
        isRunning.set(false)
        handler.post { setUIRunning(false) }
    }

    /**
     * 模式3专用：执行一轮测试，完成后进入"等待断电"状态
     * @param roundIndex  当前是第几轮（用于日志显示）
     * @param remaining   本轮完成后剩余次数（-1 = 无限）
     */
    private fun runSingleRoundAndWait(roundIndex: Int, remaining: Int) {
        if (!isRunning.get()) return

        val roundLabel = if (remaining < 0) "第 $roundIndex 轮 (∞)" else "第 $roundIndex 轮，完成后剩余 $remaining 次"
        appendLog("\n─── $roundLabel ───")

        val result = runOnce()
        currentCount++
        if (result) successCount++ else failCount++
        handler.post { updateCountUI() }

        if (!isRunning.get()) {
            // 手动停止了，不进入等待状态
            closeReader()
            isRunning.set(false)
            handler.post { setUIRunning(false) }
            return
        }

        // 更新剩余次数到 prefs
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val infinite = remaining < 0
        if (!infinite) {
            prefs.edit().putInt(PREF_REMAINING, remaining).apply()
            if (remaining <= 0) {
                // 有限模式，次数耗尽，结束
                appendLog("\n====== 全部测试完成 ======")
                appendLog("成功: $successCount  失败: $failCount  总计: $currentCount")
                prefs.edit().putBoolean(PREF_AUTO_MODE, false).apply()
                closeReader()
                isRunning.set(false)
                handler.post { setUIRunning(false) }
                return
            }
        }

        // 关闭读卡器，进入等待断电状态
        closeReader()
        isRunning.set(false)
        appendLog("\n[模式3] 本轮完成 ✓  请断电重启设备继续下一轮测试")
        appendLog("[模式3] 剩余次数: ${if (infinite) "∞" else remaining}  — 手动点击【停止】可退出自动模式")
        handler.post {
            setUIRunning(false)
            tvStatus.text = "● 等待断电"
            tvStatus.setTextColor(0xFF42A5F5.toInt()) // 蓝色
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

    /**
     * 测试项1：身份证读取
     * 成功条件：读出姓名和号码
     */
    private fun testIdCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            // 读取身份证信息（1=内置SAM模块）
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

    /**
     * 测试项2：非接触CPU卡
     * 成功条件：寻卡 + 复位 + 取随机数 全部成功
     */
    private fun testContactlessCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            // 寻卡
            val findRet = BasicOper.dc_card_hex(0x01) // 非接类型
            if (findRet.isNullOrBlank() || isError(findRet)) {
                appendLog("  寻卡失败: $findRet")
                return false
            }
            appendLog("  寻卡: $findRet")
            // 复位
            val resetRet = BasicOper.dc_pro_resethex()
            if (resetRet.isNullOrBlank() || isError(resetRet)) {
                appendLog("  复位失败: $resetRet")
                return false
            }
            appendLog("  复位ATR: $resetRet")
            // 取随机数 (APDU: 0084000008)
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

    /**
     * 测试项3：接触CPU卡
     * 成功条件：复位 + 取随机数 成功
     */
    private fun testContactCard(): Boolean {
        return try {
            if (devHandle <= 0) return false
            // 接触卡复位
            val resetRet = BasicOper.dc_cpureset_hex()
            if (resetRet.isNullOrBlank() || isError(resetRet)) {
                appendLog("  接触卡复位失败: $resetRet")
                return false
            }
            appendLog("  复位ATR: $resetRet")
            // 取随机数
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

    /**
     * 测试项4：以太网 PING 测试
     * 成功条件：连续 N 次 PING 全部成功
     */
    private fun testEthernet(): Boolean {
        return try {
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

    /**
     * 测试项5：扫码测试
     * 成功条件：扫到的数据与样本码匹配
     * 说明：扫码头通过HID模拟键盘，扫码数据自动填入 etBarcodeInput
     */
    private fun testBarcode(): Boolean {
        return try {
            val ref = etBarcodeRef.text.toString().trim()
            if (ref.isBlank()) {
                appendLog("  未设置样本二维码")
                return false
            }
            // 清空扫码输入框，等待扫码
            var scanned = ""
            handler.post { etBarcodeRef.requestFocus() }
            Thread.sleep(3000) // 等待操作员扫码
            handler.post { scanned = etBarcodeRef.text.toString().trim() }
            Thread.sleep(100)
            appendLog("  扫码结果: $scanned | 样本: $ref")
            scanned == ref
        } catch (e: Exception) {
            appendLog("  扫码测试异常: ${e.message}")
            false
        }
    }

    // ── 重启/自动模式辅助 ─────────────────────────────────────

    private fun saveResumeState(remaining: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_AUTO_MODE, true) // 重启后继续
            putBoolean(PREF_INFINITE, false) // 模式2不走无限逻辑
            putInt(PREF_REMAINING, remaining)
            putInt(PREF_DELAY, etStartDelay.text.toString().toIntOrNull() ?: 0)
            putString(PREF_PING_HOST, etPingHost.text.toString())
            putString(PREF_PING_COUNT, etPingCount.text.toString())
            putBoolean(PREF_CB_IDCARD, cbIdCard.isChecked)
            putBoolean(PREF_CB_CONTACTLESS, cbContactlessCard.isChecked)
            putBoolean(PREF_CB_CONTACT, cbContactCard.isChecked)
            putBoolean(PREF_CB_ETHERNET, cbEthernet.isChecked)
            putBoolean(PREF_CB_BARCODE, cbBarcode.isChecked)
            putString(PREF_BARCODE_REF, etBarcodeRef.text.toString())
            apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun rebootDevice() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.reboot(null)
        } catch (e: Exception) {
            appendLog("[模式2] 重启失败（需系统权限）: ${e.message}")
            appendLog("[模式2] 请手动重启设备")
        }
    }

    // ── 日志相关 ──────────────────────────────────────────────

    fun appendLog(msg: String) {
        val line = "[${sdf.format(Date())}] $msg"
        // 写入文件
        try { currentLogFile?.appendText(line + "\n") } catch (_: Exception) {}
        // 更新 UI
        handler.post {
            tvLog.append(line + "\n")
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
        // 多选状态
        val checked = BooleanArray(files.size) { false }

        AlertDialog.Builder(this)
            .setTitle("历史日志（勾选后可删除）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("查看") { _, _ ->
                // 查看第一个选中的文件
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

    private fun getRunMode(): Int {
        var m = 1
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            m = when {
                rbModeNormal.isChecked -> 1
                rbModeReboot.isChecked -> 2
                else -> 3
            }
            latch.countDown()
        }
        latch.await()
        return m
    }

    private fun getModeName(): String = when {
        rbModeNormal.isChecked -> "普通循环"
        rbModeReboot.isChecked -> "每次重启"
        else -> "开机自动"
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
