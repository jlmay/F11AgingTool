# T10 SDK Android 集成参考文档

> 基于 Decard 读卡器 SDK (`dc_reader_release_*.aar`)，在 Android 工程中调用读卡器功能。
> 适用场景：身份证读取、非接触式 CPU 卡、接触式 CPU 卡等硬件读卡操作。

---

## 目录

1. [所需文件](#1-所需文件)
2. [文件放置位置](#2-文件放置位置)
3. [构建配置](#3-构建配置)
4. [核心调用方式](#4-核心调用方式)
5. [功能函数详解](#5-功能函数详解)
   - [5.1 连接/打开读卡器](#51-连接打开读卡器)
   - [5.2 关闭读卡器](#52-关闭读卡器)
   - [5.3 身份证读取](#53-身份证读取)
   - [5.4 非接触式 CPU 卡](#54-非接触式-cpu-卡)
   - [5.5 接触式 CPU 卡](#55-接触式-cpu-卡)
6. [返回结果约定](#6-返回结果约定)
7. [状态模型类](#7-状态模型类)
8. [常见问题与注意事项](#8-常见问题与注意事项)

---

## 1. 所需文件

| 文件 | 说明 |
|------|------|
| `dc_reader_release_<版本>.aar` | Decard 读卡器 SDK，包含了 Java 封装层、JNI 层及各平台 `.so` 库 |
| — | SDK 内部包含：`classes.jar`（主要 SDK）、`DecardApi.jar`（设备控制）、`ScanManager_*.jar`（扫码）、`javalib.jar`（电源控制） |

AAR 内部已包含以下原生库（通过 JNI 调用）：

```
jni/arm64-v8a/
    libdcrf32.so       ← 核心读卡器驱动（RF、CPU、身份证）
    libreadcard.so     ← 读卡辅助
    libc++_shared.so   ← C++ 运行时
    libiconv.so        ← 编码转换
    libusb-1.0.so      ← USB Host 通信
    libauth.so         ← 授权验证
    libmarsxlog.so     ← 日志
    libwlt2bmp.so      ← 指纹转 BMP
jni/armeabi-v7a/       ← 同上，32位 ARM 版本
```

> Android 项目无需额外拷贝或加载 `.so` 库，Gradle 会自动从 AAR 中提取匹配 ABI 的库。

---

## 2. 文件放置位置

将 AAR 文件放置在模块的 `libs/` 目录下：

```
app/
├── libs/
│   └── dc_reader_release_20260302133638.aar    ← AAR 文件
├── src/
│   └── main/
│       ├── java/
│       │   └── com/yourpackage/
│       │       └── YourActivity.kt             ← 调用 SDK 的代码
│       ├── AndroidManifest.xml
│       └── ...
└── build.gradle.kts                              ← 构建配置
```

---

## 3. 构建配置

### Gradle (Kotlin DSL) — `app/build.gradle.kts`

```kotlin
dependencies {
    // 其他依赖...
    implementation(files("libs/dc_reader_release_20260302133638.aar"))
}
```

### Gradle (Groovy DSL) — `app/build.gradle`

```groovy
dependencies {
    implementation files('libs\\dc_reader_release_20240221112325.aar')
}
```

### NDK ABI 过滤（可选）

若只需 32 位：

```groovy
android {
    defaultConfig {
        ndk {
            abiFilters 'armeabi-v7a'
        }
    }
}
```

> **注意**：不设置 `abiFilters` 则 arm64-v8a 和 armeabi-v7a 都会被打入 APK。

### AndroidManifest.xml 所需权限

```xml
<uses-permission android:name="android.permission.USB_PERMISSION" />
<!-- 读卡器通过 USB Host 协议通信，Android 会自动处理 -->
```

> USB Host 模式无需显式声明 `<uses-feature>`，但设备需支持 USB Host。实际项目中 AndroidManifest 可能不需要额外权限——SDK 内部通过 `UsbManager` 请求权限。

---

## 4. 核心调用方式

所有 SDK 函数均为 **`BasicOper` 类的静态方法**，入口类：

```kotlin
import com.decard.NDKMethod.BasicOper
```

调用模式：

```kotlin
val result: String = BasicOper.dc_xxx(param1, param2)
```

**返回值约定**：大部分函数返回 `String`，格式为 `"状态码|数据"`（详见第 6 节）。

---

## 5. 功能函数详解

### 5.1 连接/打开读卡器

#### 函数原型

```kotlin
// 请求 USB 权限（Android USB Host 模式必需）
fun dc_AUSB_ReqPermission(context: Context): Boolean

// 打开读卡器设备
fun dc_open(deviceType: String, context: Context?, portValue: String, baudRate: Int): Int
```

#### 参数说明

| 参数 | 类型 | 说明 |
|------|------|------|
| `deviceType` | String | 设备类型：`"AUSB"`（USB）、`"COM"`（串口）、`"BLUETOOTH"` / `"BLE"`（蓝牙） |
| `context` | Context? | Android 上下文 |
| `portValue` | String | 串口路径（USB/蓝牙传空串 `""`），例如 `"/dev/ttyS0"` |
| `baudRate` | Int | 波特率（USB/蓝牙传 `0`，串口如 115200） |

#### 返回说明

- **> 0**：成功，返回值为设备句柄（handle）
- **<= 0**：失败

#### 完整调用示例

```kotlin
// 1. 请求 USB 权限
BasicOper.dc_AUSB_ReqPermission(this)

// 2. 等待权限授予（建议 500ms 以上）
Thread.sleep(500)

// 3. 打开 USB 读卡器
val devHandle = BasicOper.dc_open("AUSB", this, "", 0)
if (devHandle > 0) {
    // 连接成功
    BasicOper.dc_beep(5)  // 可选：蜂鸣提示
} else {
    // 连接失败，code = devHandle
}
```

#### 其他连接方式（参考 Demo）

| 方式 | 调用 |
|------|------|
| USB | `dc_open("AUSB", context, "", 0)` |
| 串口 | `dc_open("COM", null, "/dev/ttyS0", 115200)` |
| 蓝牙 | `dc_open("BLUETOOTH", context, macAddress, 0)` |

> 串口连接时 `context` 可传 `null`。

---

### 5.2 关闭读卡器

#### 函数原型

```kotlin
fun dc_exit(): Int
```

#### 调用示例

```kotlin
if (devHandle > 0) {
    BasicOper.dc_exit()
    devHandle = -1
}
```

> 建议在 `onDestroy()` 或测试结束时调用。

---

### 5.3 身份证读取

#### 涉及函数

```kotlin
// (1) 获取身份证序列号（先于读信息调用）
fun dc_get_idsnr(): String?

// (2) 读取身份证全部信息（推荐，返回封装对象）
fun dc_SamAReadCardInfo(type: Int): IDCard?

// 替代方案：读取身份证原始信息
fun dc_get_i_d_raw_info(): IDCard?
fun dc_get_i_d_raw_string(): String?
```

#### 完整调用流程

```kotlin
// 第1步：读取序列号（确认卡片已就位）
val idSnr = BasicOper.dc_get_idsnr()
val idSnrParts = idSnr?.split("\\|".toRegex())
if (idSnrParts != null && idSnrParts.size >= 2 && idSnrParts[0] == "0000") {
    val serialNumber = idSnrParts[1]  // 序列号
}

// 第2步：读取身份证信息
val idCard: IDCard? = BasicOper.dc_SamAReadCardInfo(1)
if (idCard != null) {
    val name = idCard.name              // 姓名
    val idNum = idCard.id               // 身份证号
    val sex = idCard.sex                // 性别
    val nation = idCard.nation          // 民族
    val birthday = idCard.birthday      // 出生日期
    val address = idCard.address        // 地址
    val office = idCard.office          // 签发机关
    val startTime = idCard.startTime    // 有效期起始
    val endTime = idCard.endTime        // 有效期截止
    val photoData = idCard.photoData    // 照片（byte[]，可转 Bitmap）
}
```

#### 完整 Java 调用示例（含照片显示）

```java
/** 点击按钮读取身份证信息并显示照片 */
public void read_idcard(View view) {
    IDCard idCard = BasicOper.dc_SamAReadCardInfo(1);
    if (idCard != null) {
        // 显示文本信息
        myAddTextview("姓名：" + idCard.getName());
        myAddTextview("性别：" + idCard.getSex());
        myAddTextview("民族：" + idCard.getNation());
        myAddTextview("出生日期：" + idCard.getBirthday());
        myAddTextview("地址：" + idCard.getAddress());
        myAddTextview("身份证号：" + idCard.getId());
        myAddTextview("发证机关：" + idCard.getOffice());
        myAddTextview("有效期：" + idCard.getEndTime());

        // 显示照片到 ImageView
        ImageView imageView = (ImageView) findViewById(R.id.imageView_iccard);
        imageView.setImageBitmap(
            BitmapFactory.decodeByteArray(idCard.getPhotoData(), 0, idCard.getPhotoData().length)
        );
        imageView.setVisibility(View.VISIBLE);
    } else {
        myAddTextview("读身份证失败！");
    }
}
```

#### IDCard 实体类字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String? | 姓名 |
| `sex` | String? | 性别 |
| `nation` | String? | 民族 |
| `birthday` | String? | 出生日期 |
| `address` | String? | 住址 |
| `id` | String? | 身份证号码 |
| `office` | String? | 签发机关 |
| `startTime` | String? | 有效期起 |
| `endTime` | String? | 有效期止 |
| `photoData` | ByteArray? | 照片数据（可 `BitmapFactory.decodeByteArray` 转 Bitmap） |
| `fingerprintData` | ByteArray? | 指纹数据（部分设备支持） |

> `IDCard` 实现了 `Parcelable` 和 `Serializable`，可在 Activity/Fragment 间传递。

#### 照片转 Bitmap 并显示

```kotlin
// 转 Bitmap
val bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)

// 显示到 ImageView
imageView.setImageBitmap(bitmap)
imageView.visibility = View.VISIBLE
```

```java
// 转 Bitmap
Bitmap bitmap = BitmapFactory.decodeByteArray(idCard.getPhotoData(), 0, idCard.getPhotoData().length);

// 显示到 ImageView
ImageView imageView = findViewById(R.id.imageView_iccard);
imageView.setImageBitmap(bitmap);
imageView.setVisibility(View.VISIBLE);
```

> **注意**：`photoData` 可能为 `null`，使用前建议判空处理。完整调用流程见上方 [Java 示例](#完整-java-调用示例含照片显示)。

---

### 5.4 非接触式 CPU 卡

#### 完整调用流程（5 步）

```kotlin
import com.decard.NDKMethod.BasicOper

// ─── 第1步：配置卡类型（可选，非致命） ───
val configRet = BasicOper.dc_config_card(0x00)
// 0x00 = Type A (ISO 14443A)
// 0x01 = Type B (ISO 14443B)

// ─── 第2步：射频复位 ───
val resetRf = BasicOper.dc_reset()
// 返回 "0000|xxx" 成功

// ─── 第3步：寻卡 ───
// Type A:
val findRet = BasicOper.dc_card_n_hex(0x01)     // 寻卡（防冲突+选择）
// Type B:
val findRetB = BasicOper.dc_request_b_hex(0x00, 0x00)
BasicOper.dc_attrib(findRetB.split("\\|")[1], 0x00)
val cardB = BasicOper.dc_card_b_hex()

// ─── 第4步：非接触式 CPU 卡复位 ───
val resetRet = BasicOper.dc_pro_resetInt_hex()
// 返回 ATR（Answer To Reset）字符串

// ─── 第5步：发送 APDU 指令 ───
// 取随机数 APDU: 00 84 00 00 08
val randRet = BasicOper.dc_procommandInt_hex("0084000008", 7)
// 参数1: APDU 指令 Hex 字符串（不含空格）
// 参数2: Le（期望返回长度）
// 返回: "0000|随机数字符串"

// ─── 可选：检查卡片是否在场 ───
val exist = BasicOper.dc_card_exist()

// ─── 最后：下电（释放卡片） ───
BasicOper.dc_pro_halt()
```

#### 函数速查表

| 函数 | 说明 | 参数 | 返回 |
|------|------|------|------|
| `dc_config_card(type)` | 配置射频卡类型 | `0x00`=Type A, `0x01`=Type B | `"0000\|..."` |
| `dc_reset()` | 射频复位 | 无 | `"0000\|..."` |
| `dc_card_n_hex(mode)` | Type A 寻卡 | `0x01`=寻卡 | `"0000\|卡序列号"` |
| `dc_request_b_hex(reqCode, param)` | Type B 寻卡请求 | — | `"0000\|数据"` |
| `dc_attrib(data, param)` | Type B 属性设置 | 上一步返回的数据 | `"0000\|..."` |
| `dc_card_b_hex()` | Type B 选卡 | 无 | `"0000\|..."` |
| `dc_pro_resetInt_hex()` | 非接触 CPU 卡复位 | 无 | `"0000\|ATR"` |
| `dc_procommandInt_hex(apdu, le)` | 发送 APDU（封装方式） | `String apdu`, `int le` | `"0000\|响应数据"` |
| `dc_pro_commandsource_int(apdu, le)` | 发送 APDU（原始方式） | 同上 | `"0000\|响应数据"` |
| `dc_card_exist()` | 查询卡片是否在场 | 无 | `"0000\|状态"` |
| `dc_pro_halt()` | 下电（释放卡片） | 无 | `"0000\|..."` |

#### APDU 示例

```kotlin
// 取随机数
"0084000008"

// 读取二进制文件
"00B0000000"

// 内部认证
"00880000XX数据"

// 外部认证
"00820000XX数据"
```

---

### 5.5 接触式 CPU 卡

#### 函数原型

```kotlin
// 选择卡座（多卡座设备，0-5路）
fun dc_setcpu(slotNumber: Int): Int

// 设置接触式CPU卡参数
fun dc_setcpupara(ic: Int, model: Int, sn: Int): String?

// 检测卡片是否在位
fun dc_card_status(): String?

// 接触式CPU卡复位（获取 ATR）
fun dc_cpureset_hex(): String?

// 发送 APDU 指令
fun dc_cpuapduInt_hex(apdu: String): String?

// 接触式CPU卡下电
fun dc_cpudown(): String?
```

#### 完整调用流程

```kotlin
// ─── 第1步：选择卡座（多卡座设备） ───
BasicOper.dc_setcpu(slotNumber)  // slotNumber: 0-5

// ─── 第2步：设置参数（通常只需调用一次） ───
val paraRet = BasicOper.dc_setcpupara(0, 0x00, 0x5C)
// 返回 "0000|..." 表示成功

// ─── 第3步：检测卡片是否在位（可选） ───
val status = BasicOper.dc_card_status()
val parts = status.split("\\|")
if (parts[0] == "0000") {
    // 卡片存在
}

// ─── 第4步：接触式 CPU 卡复位 ───
val resetRet = BasicOper.dc_cpureset_hex()
val resetParts = resetRet?.split("\\|")
if (resetParts != null && resetParts.size >= 2 && resetParts[0] == "0000") {
    val atr = resetParts[1]  // ATR 字符串
}

// ─── 第5步：发送 APDU 指令（取随机数） ───
val randRet = BasicOper.dc_cpuapduInt_hex("0084000008")
val randParts = randRet?.split("\\|")
if (randParts != null && randParts[0] == "0000") {
    val randomData = randParts[1]
}

// ─── 最后：下电 ───
BasicOper.dc_cpudown()
```

#### 函数速查表

| 函数 | 说明 | 参数 | 返回 |
|------|------|------|------|
| `dc_setcpu(slot)` | 选择接触式卡座 | `Int` 卡座号（0-5） | `Int`（状态码） |
| `dc_setcpupara(ic, model, sn)` | 设置接触式CPU卡参数 | 见注 | `"0000\|..."` |
| `dc_card_status()` | 检测卡片是否在位 | 无 | `"0000\|..."` |
| `dc_cpureset_hex()` | 接触式CPU卡复位 | 无 | `"0000\|ATR"` |
| `dc_cpuapduInt_hex(apdu)` | 发送 APDU 指令 | `String` hex APDU | `"0000\|响应"` |
| `dc_cpudown()` | 接触式CPU卡下电 | 无 | `"0000\|..."` |

> **`dc_setcpupara` 参数说明**：`ic=0`（IC 卡类型 0），`model=0x00`（协议类型 T=0），`sn=0x5C`（时钟速率）。具体取值需参考读卡器硬件手册。

---

## 6. 返回结果约定

### 字符串格式

绝大多数 `BasicOper` 函数返回 **管道分隔字符串**（`|`）：

```
"状态码|具体数据"
```

| 状态码 | 含义 |
|--------|------|
| `0000` | 成功 |
| 其他（如 `1001`、`1017`、`2002` 等） | 错误码，需查 SDK 错误码表 |

### 解析辅助函数

```kotlin
/**
 * 判断 SDK 返回是否成功
 */
fun isSuccess(result: String?): Boolean {
    return result?.split("\\|".toRegex())?.get(0) == "0000"
}

/**
 * 提取返回数据部分（去掉状态码前缀）
 */
fun extractData(result: String?): String? {
    if (result == null) return null
    val parts = result.split("\\|".toRegex())
    return if (parts.size >= 2 && parts[0] == "0000") parts[1] else null
}
```

### 返回值空值注意

部分函数（如 `dc_get_idsnr()`、`dc_cpureset_hex()`）返回值声明为 `String?`，可能返回 `null`，调用前需判空。

---

## 7. 状态模型类

### IDCard（`com.decard.entitys.IDCard`）

```kotlin
package com.decard.entitys

class IDCard : Parcelable, Serializable {
    // 中国大陆身份证字段
    var name: String? = null           // 姓名
    var sex: String? = null            // 性别
    var nation: String? = null         // 民族
    var birthday: String? = null       // 出生日期（如 19900101）
    var address: String? = null        // 地址
    var id: String? = null             // 身份证号码（18位）
    var office: String? = null         // 签发机关
    var startTime: String? = null      // 有效期起
    var endTime: String? = null        // 有效期止
    var photoData: ByteArray? = null   // 照片数据
    var fingerprintData: ByteArray? = null  // 指纹数据

    // 其他字段（外国人/港澳台）
    var foreign_name: String? = null
    var foreign_sex: String? = null
    var foreign_id: String? = null
    var hongkong_macao_taiwan_nameStr: String? = null
    // ...
}
```

---

## 8. 常见问题与注意事项

### 8.1 USB 权限

- `dc_AUSB_ReqPermission()` 必须在 `dc_open("AUSB", ...)` **之前**调用。
- 调用后系统会弹出 USB 权限对话框，建议加 `Thread.sleep(500)` 等待权限授予完成。
- 若用户拒绝授权，读卡器无法打开。

### 8.2 线程安全

- **所有 `BasicOper` 调用必须在非 UI 线程执行**（Android 不允许在主线程做 USB 通信）。
- 建议使用单独的子线程或线程池执行：

```kotlin
val executor = Executors.newSingleThreadExecutor()
executor.submit {
    // 所有 BasicOper 调用在此
}
```

### 8.3 每轮操作间释放卡片

非接触 CPU 卡操作完成后，建议调用 `BasicOper.dc_pro_halt()` 下电释放卡片，否则下一轮寻卡可能失败。

### 8.4 连续操作的时序

读卡器操作需要适当的时间间隔，尤其身份证读取（`dc_SamAReadCardInfo`）可能需要 1-2 秒才能完成。建议在循环读卡场景中加入 `Thread.sleep(1000)` 避免过快重试。

### 8.5 `dc_config_card` 的可选性

`dc_config_card(0x00/0x01)` 用于配置射频卡类型，在部分读卡器型号上该调用会失败，但不影响后续操作。建议将其视为非致命步骤：

```kotlin
try {
    val ret = BasicOper.dc_config_card(0x00)
    // 忽略失败
} catch (e: Exception) {
    // 继续执行后续步骤
}
```

### 8.6 多卡座设备

接触式 CPU 卡操作前需要调用 `dc_setcpu(slot)` 选择对应卡座。F11-N 底座单路卡座一般使用 `slot=0`。

### 8.7 句柄管理

- `devHandle` 是打开设备返回的 Int 值，所有后续操作都依赖此句柄有效。
- 调用 `dc_exit()` 后 `devHandle` 失效，应将 `devHandle` 置为 -1。
- 可复用 `devHandle > 0` 判断读卡器是否已连接。

---

## 附录：项目文件清单参考

```
app/
├── libs/
│   └── dc_reader_release_20260302133638.aar
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── decard/
│                   └── f11aging/
│                       └── MainActivity.kt    ← SDK 调用入口
└── build.gradle.kts                            ← 添加 files("libs/...aar")
```

> 本参考文档基于 `dc_reader_release_20260302133638.aar`（2026-03-02）及参考工程 `BaseLibraryDemo` 整理。不同版本 SDK 的函数签名和返回格式可能有微调，请以实际 AAR 中的 API 为准。
