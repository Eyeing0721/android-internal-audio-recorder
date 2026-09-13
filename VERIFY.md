# 验证记录

## 构建环境

```
JDK        17.0.10 (C:\Program Files\Java\jdk-17)
Gradle     8.7
AGP        8.6.1
compileSdk 35 / build-tools 34.0.0（SDK 里 35.0.0 目录不完整，用 34 的产物验证）
minSdk     29
```

构建命令：

```
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
gradle assembleRelease --no-daemon --console=plain
```

结果：

```
BUILD SUCCESSFUL in 2m 1s
45 actionable tasks: 34 executed, 11 up-to-date
```

## 产物

```
app/build/outputs/apk/release/app-release.apk   9.3 MB
```

`aapt2 dump badging`：

```
package: name='dev.eye.internalrec' versionCode='1' versionName='1.0.0'
         compileSdkVersion='35'
sdkVersion:'29'
targetSdkVersion:'35'
uses-permission: android.permission.RECORD_AUDIO
uses-permission: android.permission.FOREGROUND_SERVICE
uses-permission: android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
uses-permission: android.permission.FOREGROUND_SERVICE_MICROPHONE
uses-permission: android.permission.POST_NOTIFICATIONS
uses-permission: android.permission.WAKE_LOCK
application-label:'内录机'
launchable-activity: name='dev.eye.internalrec.MainActivity'
```

注意没有 `native-code` 行 —— 纯 Java，没有 NDK 产物，所以任何 ABI 的机器都能装。

`apksigner verify --verbose --print-certs`：

```
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Signer #1 certificate DN: CN=Internal Audio Recorder, OU=eye-labs, O=eye-labs, L=Tianjin, C=CN
Signer #1 certificate SHA-256 digest: 7b112f2d492d4b3965e28cfb7507bbc43386d07750c8cefda5d30b11119a468e
```

v2 通过就够装了（minSdk 29 起系统只认 v2 以上）。

## 踩过的坑

**XML 注释里不能有 `--`。** 我在 `AndroidManifest.xml` 的注释里写了 adb 的 `--es` 参数，
manifest merger 直接报 `Error parsing AndroidManifest.xml`，但不告诉你哪儿错了。
换成 `-e`（`am broadcast` 的短参数写法，等价）就好了。

标准 XML 解析器的报错更清楚：

```
An XML comment cannot contain '--', and '-' cannot be the last character. Line 34, position 75.
```

**Gradle 的 `Unable to establish loopback connection`。** 在 `gradle.properties` 里写
`org.gradle.jvmargs` 会让 Gradle 认为 JVM 参数和客户端不一致，于是**再 fork 一个单次守护进程**，
而 fork 要走 127.0.0.1 回环——这台机器上回环不通（推测被安全软件挡了），于是直接失败。

去掉 `org.gradle.jvmargs`，改用环境变量 `GRADLE_OPTS` 传，加 `--no-daemon`，
构建就在客户端 JVM 里跑完了，不需要回环。

## 真机实测

设备：**小米 2407FRK8EC（rothko）/ Android 16**，USB 连接，`adb install` 装的上面那个签名 APK。

整条链路：

```
adb install -r InternalRecorder-1.0.0.apk                  -> Success
adb shell appops set dev.eye.internalrec PROJECT_MEDIA allow
adb shell am start -n dev.eye.internalrec/.MainActivity --ez autoArm true -e source capture
```

**投屏授权弹窗没有出现** —— appops 预授权在这台机器上有效。
启动后 App 界面直接进入「录制中」，文件名 `rec-20260914-011457.wav`。
也就是说 `arm → start → stop → pull` 全程零手动点击。

`uiautomator dump` 抓到的界面（确认 UI 真的渲染出来了）：

```
text=录制中              id=dev.eye.internalrec:id/tvStatus   [65,65][359,196]
text=0.0 秒 · 电平 0% · rec-20260914-011457.wav
                         id=dev.eye.internalrec:id/tvDetail   [65,209][981,265]
text=停止并保存          id=dev.eye.internalrec:id/btnToggle  [65,1008][1155,1190]
```

### 录音结果

录了两段。第一段是**手机还没在放任何声音**的时候录的，第二段是在放音之后录的：

```
-rw-rw---- 2261036 2026-09-14 01:15 rec-20260914-011457.wav
-rw-rw---- 6185004 2026-09-14 01:16 rec-20260914-011536.wav
```

```
=== rec-20260914-011457.wav  2.16 MB ===
codec_name=pcm_s16le
sample_rate=48000
channels=2
duration=11.776000
mean_volume: -91.0 dB
max_volume: -91.0 dB

=== rec-20260914-011536.wav  5.92 MB ===
codec_name=pcm_s16le
sample_rate=48000
channels=2
duration=32.341333
mean_volume: -16.1 dB
max_volume: -2.8 dB
```

**两个文件都要看，这才是完整的证据。**

- 第二个 `-16.1 dB / 峰值 -2.8` 说明确实抓到了正在播放的媒体音频（不是噪底）。
- 第一个 `-91.0 dB` 说明**没声音的时候它老老实实是静音**。这一条比上面那条更值钱：
  只测"能录到声音"，一个把麦克风噪底录进去的实现也会通过；有了静音对照才排除掉这种可能。

放音怎么弄的：`am start ... VIEW -d file:///sdcard/Download/tone20.wav` 在这台机器上
弹的是应用选择器（没有默认音频播放器）。改发 `input keyevent 126`（MEDIA_PLAY），
把已加载但暂停的媒体音轨唤起来了：

```
AudioPlaybackConfiguration piid:9231 ... type:android.media.AudioTrack
  u/pid:10287/10854 state:started  attr: usage=USAGE_MEDIA ... 44100 Hz 立体声
```

顺带确定了一件事：**内录抓的是别的 App 的音频轨，跟自己的进程无关**
（播放方是 pid 10854，录制方是 dev.eye.internalrec）。

### 这一轮改掉的东西

`--ez` 和 `-e` 的区别踩了一次：`am start -e autoArm true` 传的是**字符串**，
而代码里读的是 `getBooleanExtra`，结果静默不生效（界面停在待机，也不报错）。
现在 App 两种写法都认，文档里也写明了 `--ez` 才是布尔。

## 还是没验的部分

- **"内录 + 麦克风"这条没跑**。这台机器只测了「仅内录」。同时抓内录和麦克风在不少机型上
  会被系统直接拒绝（Android 的限制），代码里做了退化成只录内录的处理，但那个降级路径没实测。
- **目标 App 声明 `allowAudioPlaybackCapture=false` 时的静音行为**没测（手头没有这类 App）。
- 没测长录音（十几分钟以上）的稳定性，以及录到一半被系统杀进程时的文件可用性
  （WavWriter 是按"边录边写、停止时回填头"设计的，理论上文件还在，但没验过）。

