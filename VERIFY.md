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

## 没验的部分

**没在真机上跑过。** 写这个的时候测试手机没连着（`adb devices` 是空的），所以
"装上去、授权、录出来有声音"这条链**没有实测证据**。

已验的只到：能构建、能签名、manifest 里的权限/组件/最低版本都对。

真机验证要做的事（`tools/record.ps1` 都包好了）：

```
tools\record.ps1 status
tools\record.ps1 arm
tools\record.ps1 start -Name t1 -Seconds 15
tools\record.ps1 pull -Out D:\rec
ffprobe D:\rec\Recordings\t1.wav          # 期望 pcm_s16le / 48000 Hz / 立体声
ffmpeg -i t1.wav -af volumedetect -f null -   # 期望 mean_volume 不是 -91dB（静音）
```

最后那步是关键：**能录出文件不等于录到了声音**，一定要看音量。
