
## 我的安卓 APK 编译参考（Win10 + JDK 17.0.14）

### 🔍 当前环境（已验证）

powershell

java -version                  # OpenJDK 17.0.14 Temurin
javac -version                 # javac 17.0.14
echo $env:JAVA_HOME            # D:\jdk-17.0.14.7-hotspot\
(Get-Command java).Source      # D:\jdk-17.0.14.7-hotspot\bin\java.exe

✅ 完整 JDK（有 `javac`），不是 JRE  
✅ `JAVA_HOME` 已设好  
✅ JDK 17 可直接兼容 AGP 8.x 和 9.x

---

### 1. 新项目到手第一步：命令行编译

进入项目根目录，直接跑：

powershell

./gradlew assembleDebug

一次过 → 环境完美，后续随便用 IDE。  
如果报错 → 优先检查 Gradle 是不是捡到了不完整的 Java 运行时（见下一条）。

---

### 2. 锁死 Gradle 使用的 JDK（防 IDE 自带 JRE 干扰）

在项目根目录的 `gradle.properties` 中加一行：

properties

org.gradle.java.home=D:/jdk-17.0.14.7-hotspot

> 路径用 **正斜杠** `/`，不要用单反斜杠。  
> 这样不管 VS Code / Android Studio 自带什么 JRE，编译时都会强制用你这个完整 JDK。

---

### 3. `gradle.properties` 推荐配置（直接复制）

properties

org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.java.home=D:/jdk-17.0.14.7-hotspot
# 大项目可开启并行和缓存，编译更快
# org.gradle.parallel=true
# org.gradle.caching=true

---

### 4. 确保 Android SDK 就位

检查环境变量（没有就加上）：

- `ANDROID_HOME` → SDK 路径（一般 `C:\Users\jlmay\AppData\Local\Android\Sdk`）
    
- `PATH` 里追加：
    
    - `%ANDROID_HOME%\platform-tools`
        
    - `%ANDROID_HOME%\cmdline-tools\latest\bin`
        

如果不想设环境变量，也可以在项目根目录新建 `local.properties`：

properties

sdk.dir=C:\\Users\\jlmay\\AppData\\Local\\Android\\Sdk

---

### 5. 版本搭配（我的 JDK 17 完全够用）

|组件|我用什么|要求|
|---|---|---|
|JDK|**Temurin 17.0.14**|AGP 8.x / 9.x 都需要 JDK ≥ 17|
|AGP|跟项目走|新项目一般 8.x 或 9.x|
|Gradle|项目自带 wrapper|无需单独安装|

不需要升级 JDK 21，现在的 17 足以应对绝大多数新老项目。

---

### 6. 如果 IDE 捣乱，临时覆盖的方法

**临时指定 JDK 编译：**

powershell

$env:JAVA_HOME="D:\jdk-17.0.14.7-hotspot"; ./gradlew assembleDebug

或

powershell

./gradlew assembleDebug -Dorg.gradle.java.home="D:/jdk-17.0.14.7-hotspot"

**根本解决**还是前面第 2 条，在 `gradle.properties` 里写死路径。

---

### 📌 我的编译心法

> 我有一份完整的 Temurin JDK 17 放在 `D:\jdk-17.0.14.7-hotspot`，`JAVA_HOME` 就指向它。  
> 每个项目的 `gradle.properties` 里我都会加上 `org.gradle.java.home` 锁死这个路径，  
> 新项目到手第一时间 `./gradlew assembleDebug`，能过就不再担心 Java 环境问题。

---

以后你换电脑或重装 JDK，只需要把文档里的路径和版本号一替换，流程完全不变。现在这套环境已经是“开箱即用”的状态了。