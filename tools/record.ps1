# agent 控制面：不碰手机就能启停录制。
#
#   tools\record.ps1 arm                  拉起投屏授权（手机上点一次"允许"）
#   tools\record.ps1 start -Name take1    开录
#   tools\record.ps1 stop                 停
#   tools\record.ps1 status               看设备/窗口状态
#   tools\record.ps1 pull -Out D:\rec     把录音拉到本机
#
# 授权只需要一次。之后服务一直挂着那个 MediaProjection，start/stop 随便来。

param(
    [Parameter(Position = 0)]
    [ValidateSet('arm', 'start', 'stop', 'quit', 'status', 'pull', 'log')]
    [string]$Cmd = 'status',

    [string]$Name = "",
    [string]$Source = "capture",   # capture | mic | both
    [string]$Out = ".",
    [int]$Seconds = 0,             # >0 时 start 之后自动 stop
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$PKG = "dev.eye.internalrec"
$ACTION = "dev.eye.internalrec.CONTROL"

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $cand = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $cand) { $adb = $cand } else { throw "找不到 adb，装 platform-tools 或加进 PATH" }
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

function Invoke-Adb {
    param([string[]]$Args, [switch]$Quiet)
    $all = $adbArgs + $Args
    $out = & $adb @all 2>&1
    if (-not $Quiet) { $out | ForEach-Object { Write-Host $_ } }
    return $out
}

function Assert-Device {
    $line = (& $adb @($adbArgs + @("devices")) ) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    if (-not $line) { throw "没有连接的设备。插上手机、开 USB 调试、手机上点允许。" }
    if ($line -match "unauthorized") { throw "设备未授权：看手机屏幕，点『允许 USB 调试』" }
    Write-Host "设备: $line" -ForegroundColor DarkGray
}

function Get-RecordDir { "/sdcard/Android/data/$PKG/files/Recordings" }

switch ($Cmd) {
    'status' {
        Assert-Device
        Write-Host "`n--- 设备 ---"
        Invoke-Adb @("shell", "getprop", "ro.product.model")
        Invoke-Adb @("shell", "getprop", "ro.build.version.release")
        Write-Host "`n--- 装了没 / 版本 ---"
        $r = Invoke-Adb -Args @("shell", "dumpsys", "package", $PKG) -Quiet
        $v = ($r | Select-String -Pattern 'versionName=(\S+)' | Select-Object -First 1)
        if ($v) { Write-Host "已安装 $($v.Matches[0].Groups[1].Value)" }
        else { Write-Host "没装。先 adb install app-release.apk" -ForegroundColor Yellow }
        Write-Host "`n--- 正在录的进程 ---"
        $ps = Invoke-Adb -Args @("shell", "ps", "-A") -Quiet
        if ($ps -match $PKG) { Write-Host "服务在跑" } else { Write-Host "服务没跑（还没 arm，或者 quit 了）" }
    }

    'arm' {
        Assert-Device
        Write-Host "拉起授权流程 —— 请在手机上点『允许』/『立即开始』" -ForegroundColor Yellow
        Invoke-Adb @("shell", "am", "start", "-n", "$PKG/.MainActivity", "-e", "autoArm", "true", "-e", "source", $Source)
        Write-Host "`n授权后服务会一直挂着，之后直接用 start/stop。"
    }

    'start' {
        Assert-Device
        if (-not $Name) { $Name = "rec-" + (Get-Date -Format "yyyyMMdd-HHmmss") }
        Invoke-Adb @("shell", "am", "broadcast", "-a", $ACTION, "-e", "cmd", "start", "-e", "name", $Name, "-e", "source", $Source)
        if ($Seconds -gt 0) {
            Write-Host "录 $Seconds 秒…"
            Start-Sleep -Seconds $Seconds
            Invoke-Adb @("shell", "am", "broadcast", "-a", $ACTION, "-e", "cmd", "stop")
            Write-Host "`n录完，拉文件：tools\record.ps1 pull -Out $Out"
        }
    }

    'stop' {
        Assert-Device
        Invoke-Adb @("shell", "am", "broadcast", "-a", $ACTION, "-e", "cmd", "stop")
    }

    'quit' {
        Assert-Device
        Invoke-Adb @("shell", "am", "broadcast", "-a", $ACTION, "-e", "cmd", "quit")
    }

    'pull' {
        Assert-Device
        New-Item -ItemType Directory -Force -Path $Out | Out-Null
        Invoke-Adb @("pull", (Get-RecordDir), $Out)
        Write-Host "`n--- 拉到的文件 ---"
        Get-ChildItem -Recurse $Out -Filter *.wav | ForEach-Object {
            $sec = [math]::Round(($_.Length - 44) / (48000 * 2 * 2), 1)
            "{0,-40} {1,8:N1} MB  {2,7:N1} 秒" -f $_.Name, ($_.Length / 1MB), $sec
        }
    }

    'log' {
        Assert-Device
        Invoke-Adb @("logcat", "-s", "CaptureService:V", "CaptureEngine:V", "ControlReceiver:V", "*:S")
    }
}
