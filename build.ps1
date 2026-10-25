# 一条命令构建 debug APK：右键「使用 PowerShell 运行」，或在 PowerShell 里执行 .\build.ps1
#
# 环境优先级：
#   1. 已经设好的 JAVA_HOME / ANDROID_HOME（Android Studio 装过就有）
#   2. 项目里的便携工具链 .toolchain/jdk、.toolchain/sdk、.toolchain/gradle（见 README）
#
# 用法：
#   .\build.ps1            # 构建 debug
#   .\build.ps1 -Clean     # 先 clean 再构建
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$portable = Join-Path $root '.toolchain'

# ---- JDK
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    $jdk = Get-ChildItem (Join-Path $portable 'jdk') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk*' } | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) { throw "找不到 JDK：设置 JAVA_HOME，或把 JDK 放到 .toolchain\jdk\ 下" }

# ---- Android SDK
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    if (Test-Path (Join-Path $portable 'sdk')) { $env:ANDROID_HOME = Join-Path $portable 'sdk' }
}
if (-not $env:ANDROID_HOME) { throw "找不到 Android SDK：设置 ANDROID_HOME，或把 SDK 放到 .toolchain\sdk\ 下" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

# ---- Gradle：优先用项目自带的 wrapper；wrapper 缺 jar 时退回 .toolchain 里的 Gradle
$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradle)) {
    $gradle = (Get-ChildItem (Join-Path $portable 'gradle') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 |
        ForEach-Object { Join-Path $_.FullName 'bin\gradle.bat' })
}
if (-not $gradle) { throw "找不到 Gradle：项目里没有 gradlew.bat，.toolchain\gradle 也是空的" }

$tasks = if ($Clean) { @('clean', 'assembleDebug') } else { @('assembleDebug') }
& $gradle -p $root @tasks --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host ""
Write-Host "构建成功：$apk" -ForegroundColor Green
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (Test-Path $adb) { Write-Host "装到手机：$adb install -r `"$apk`"" }
