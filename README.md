# android-internal-audio-recorder

安卓内录机。**不 root、不要虚拟声卡**，直接录「手机里其他 App 正在播放的声音」，输出 WAV。

做这个是因为我自己的需求：想录一段 App 里的语音做素材，结果挨个试下来全不通——

- `adb shell screenrecord` **根本不支持录音频**（只有画面）
- `scrcpy --audio-source=output` 在小米 HyperOS 上连视频流都收不到，录出来 0 字节
- 电脑麦克风对着手机外放录，录到的是环境噪音
- 小米自带录屏要 MediaProjection 授权弹窗，adb 点不到

安卓 10（API 29）起系统本身就给了 `AudioPlaybackCapture` 这条路，只是没有现成的干净工具。所以自己写一个。

## 装

从 Releases 下载 APK，或者自己构建。

第一次会要两个授权：**录音权限** 和 **屏幕录制（投屏）授权**。第二个是系统要求的——`AudioPlaybackCapture` 必须挂在一个 `MediaProjection` 上，哪怕你根本不录画面。

装的机型上如果提示"未知来源"，全部允许就行。

## 用

打开 App：

1. 选音源：**仅内录** / **仅麦克风** / **内录+麦克风**
2. 点「开始录制」→ 系统弹投屏授权 → 允许
3. 再点一次开始录；录完点「停止并保存」
4. 文件在 `Android/data/dev.eye.internalrec/files/Recordings/`

用 adb 拿出来（不用 root）：

```
adb pull /sdcard/Android/data/dev.eye.internalrec/files/Recordings/
```

## 给 agent 用（不用碰手机）

授权只要**点一次**。之后启停全靠广播，服务会一直挂着那个 `MediaProjection`：

```powershell
tools\record.ps1 arm                  # 拉起授权流程（手机上点一次"允许"）
tools\record.ps1 start -Name take1    # 开录
Start-Sleep -Seconds 20
tools\record.ps1 stop                 # 停
tools\record.ps1 pull -Out D:\rec     # 拉文件
```

或者直接 adb：

```
adb shell am broadcast -a dev.eye.internalrec.CONTROL -e cmd start -e name take1
adb shell am broadcast -a dev.eye.internalrec.CONTROL -e cmd stop
adb shell am broadcast -a dev.eye.internalrec.CONTROL -e cmd quit
```

`arm` 那步没法纯广播完成——Android 10 起后台启动 Activity 会被系统拦，投屏授权弹窗必须有个前台界面。所以：

```
adb shell am start -n dev.eye.internalrec/.MainActivity --ez autoArm true
```

（`--ez` 是布尔，`-e` 是字符串。App 两个都认，但 `--ez` 才是对的写法。）

部分机型可以试试用 appops 预授权，跳过弹窗。**实测小米 2407FRK8EC / Android 16 上这条有效**，
弹窗根本不出现，整条链零手动点击：

```
adb shell appops set dev.eye.internalrec PROJECT_MEDIA allow
```

## 参数

| | |
|---|---|
| 格式 | WAV，PCM 16-bit |
| 采样率 | 48000 Hz |
| 声道 | 内录=立体声，仅麦克风=单声道 |
| 存放 | `getExternalFilesDir()/Recordings`（adb 可直接 pull，不用 root） |
| 最低版本 | Android 10（API 29），因为 `AudioPlaybackCapture` 从这版才有 |

录的是 WAV 不是 M4A，图的是稳：**边录边写，停止时才回填头部的长度字段**。就算录到一半被系统杀掉、没走到停止流程，文件里已经写进去的音频也还在（只是长度字段不对，拿 ffmpeg 能修）。

## 已知的边界

- **App 可以拒绝被录**。目标应用如果把 `allowAudioPlaybackCapture` 设成 false（有些银行、DRM 播放器会），录出来就是静音。这不是 bug，是系统的隐私开关，绕不过去。
- **电话、部分系统声音录不到**。系统按 `AudioAttributes` 的 usage 过滤，通话那类不走内录。
- **"内录+麦克风"不一定起得来**。Android 对同一个 App 同时抓内录和麦克风有限制，不少机型会直接拒绝。代码里试了，起不来会退化成只录内录，不会假装成功。
- 非 root，所以录不了系统级混音里那些标记为不可捕获的流。

## 真机实测

小米 2407FRK8EC / Android 16，`adb install` 后走完整链路（结果在 `VERIFY.md`）：

| 文件 | 时长 | mean_volume | 情况 |
|---|---|---|---|
| `rec-20260914-011457.wav` | 11.8s | **-91.0 dB** | 手机没在放声音时录的 → 静音 |
| `rec-20260914-011536.wav` | 32.3s | **-16.1 dB**（峰值 -2.8） | 有音乐在放 → **录到了真声音** |

两个文件都是 `pcm_s16le / 48000 Hz / 立体声`。

第一个静音文件其实是这套验证里最有用的一个：**它证明录音不是"总能录到点什么"**，
没声音的时候老老实实是 -91dB。只测"能录到声音"会漏掉这种伪造式通过。

另外实测确认：`adb shell appops set dev.eye.internalrec PROJECT_MEDIA allow` 在这台机器上
**能让投屏授权弹窗完全不出现**，所以 `arm → start → stop → pull` 整条链零手动点击。

## 自己构建

需要 JDK 17 + Android SDK（platform 35 / build-tools 34 以上）。仓库里带了 Gradle wrapper 的配置，但没带 wrapper 的 jar，用系统 Gradle 8.7 也行：

```
gradle assembleRelease
```

签名密钥自己生成，放在项目根目录的 `keystore.properties`（已 gitignore）：

```properties
storeFile=release.keystore
storePassword=...
keyAlias=internalrec
keyPassword=...
```

生成密钥：

```
keytool -genkeypair -v -keystore release.keystore -alias internalrec `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Internal Audio Recorder, O=eye-labs, C=CN"
```

没有 `keystore.properties` 时构建不会挂，只是不打签名（产物没法直接装）。

## 为什么是 Java 不是 Kotlin

这个 App 一共 6 个类，用不上 Kotlin 的语法糖；不带 Kotlin 插件能让构建依赖少一大截，纯 Java + 几个 AndroidX 库，拉下来就能编。Material 3 的控件照用，界面不吃亏。

## License

MIT
