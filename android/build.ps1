# 免 Gradle 打包：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 依赖：JDK 17、Android SDK build-tools 34、android-34 平台
#
#   $env:JAVA_HOME   = "C:\path\to\jdk-17"
#   $env:ANDROID_HOME= "C:\path\to\android-sdk"
#   powershell -File android\build.ps1

param(
  [string]$Jdk = $env:JAVA_HOME,
  [string]$Sdk = $env:ANDROID_HOME,
  [string]$Out = ""
)

$ErrorActionPreference = "Stop"

if (-not $Jdk) { throw "请先设置 JAVA_HOME，或用 -Jdk 指定 JDK 17 目录" }
if (-not $Sdk) { throw "请先设置 ANDROID_HOME，或用 -Sdk 指定 Android SDK 目录" }

$Here     = $PSScriptRoot
$BT       = Join-Path $Sdk "build-tools\34.0.0"
$Platform = Join-Path $Sdk "platforms\android-34\android.jar"
$Work     = Join-Path $Here "build"
$Assets   = Join-Path $Work "assets"
$Out      = if ($Out) { $Out } else { Join-Path $Here "..\apk\ohjingjing.apk" }

if (-not (Test-Path $Platform)) { throw "找不到 $Platform" }

$env:JAVA_HOME = $Jdk

Write-Host "[0/7] 同步页面"
New-Item -ItemType Directory -Force -Path $Assets | Out-Null
Copy-Item (Join-Path $Here "..\src\index.html") (Join-Path $Assets "index.html") -Force

if (Test-Path $Work) { Get-ChildItem $Work -Exclude assets | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$Work\gen","$Work\classes","$Work\dex" | Out-Null

Write-Host "[1/7] 编译资源"
& "$BT\aapt2.exe" compile --dir (Join-Path $Here "res") -o "$Work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "[2/7] 链接资源与 assets"
& "$BT\aapt2.exe" link -o "$Work\base.apk" -I $Platform `
    --manifest (Join-Path $Here "AndroidManifest.xml") `
    -R "$Work\res.zip" -A $Assets `
    --java "$Work\gen" --auto-add-overlay `
    --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "[3/7] 编译 Java"
$srcs = (Get-ChildItem (Join-Path $Here "java") -Recurse -Filter *.java).FullName
$srcs += (Get-ChildItem "$Work\gen" -Recurse -Filter *.java).FullName
& "$Jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
    -classpath $Platform -d "$Work\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "[4/7] dex"
$classes = (Get-ChildItem "$Work\classes" -Recurse -Filter *.class).FullName
& "$BT\d8.bat" --lib $Platform --min-api 24 --output "$Work\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "[5/7] 打包对齐"
Copy-Item "$Work\dex\classes.dex" "$Work\classes.dex" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$Work\base.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$Work\classes.dex", "classes.dex", "Optimal") | Out-Null
$zip.Dispose()

& "$BT\zipalign.exe" -f 4 "$Work\base.apk" "$Work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "[6/7] 签名"
$ks = Join-Path $Here "release.keystore"
if (-not (Test-Path $ks)) {
    Write-Host "    首次运行，生成自签名证书（口令 ohjingjing）"
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias ohjingjing `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass ohjingjing -keypass ohjingjing `
        -dname "CN=OhJingjing, OU=Dev, O=OhJingjing, L=Beijing, S=Beijing, C=CN" | Out-Null
}
New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:ohjingjing --key-pass pass:ohjingjing `
    --out $Out "$Work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Host "[7/7] 校验"
& "$BT\apksigner.bat" verify $Out
if ($LASTEXITCODE -ne 0) { throw "校验失败" }

Write-Host ""
Write-Host ("完成 -> " + $Out)
Write-Host ("大小 " + [math]::Round((Get-Item $Out).Length / 1KB, 1) + " KB")
