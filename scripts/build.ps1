param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$previousLocation = Get-Location
$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME','ANDROID_USER_HOME','PATH')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Set-Location -LiteralPath $projectRoot
$buildRoot = $projectRoot
$mappedDrive = $null
# aapt2 on Windows cannot reliably read non-ASCII SDK/resource paths.
if ($projectRoot -match '[^\x00-\x7F]') {
    foreach ($letter in @('T','U','V','W','X','Y','Z')) {
        if (-not [System.IO.Directory]::Exists("${letter}:\")) {
            $mappedDrive = "${letter}:"
            & subst.exe $mappedDrive $projectRoot
            if ($LASTEXITCODE -ne 0) { throw '无法创建临时构建盘符。' }
            $buildRoot = "${letter}:\"
            break
        }
    }
    if (-not $mappedDrive) { throw '没有可用的临时盘符，请将项目移到英文路径。' }
}
$result = 1
try {
    Set-Location -LiteralPath $buildRoot
    $jdk = Get-ChildItem -LiteralPath (Join-Path $buildRoot '.tools\java') -Directory | Select-Object -First 1
    if (-not $jdk) { throw '未找到本地 JDK。请使用 Android Studio，或先运行 setup-tools.ps1。' }
    $env:JAVA_HOME = $jdk.FullName
    $env:ANDROID_HOME = Join-Path $buildRoot '.tools\sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $buildRoot '.tools\gradle-cache'
    $env:ANDROID_USER_HOME = Join-Path $buildRoot '.tools\android-user'
    $env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
    & (Join-Path $buildRoot '.tools\gradle-8.11.1\bin\gradle.bat') @Tasks --console=plain
    $result = $LASTEXITCODE
    if ($result -eq 0 -and $Tasks -contains 'assembleDebug') {
        $dist = Join-Path $projectRoot 'dist'
        New-Item -ItemType Directory -Force -Path $dist | Out-Null
        $apk = Join-Path $dist '简课.apk'
        Copy-Item -LiteralPath (Join-Path $buildRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
        $checksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText((Join-Path $dist 'SHA256.txt'), "$checksum  简课.apk`n", [Text.UTF8Encoding]::new($false))
    }
} finally {
    Set-Location -LiteralPath $projectRoot
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if ($mappedDrive) { & subst.exe $mappedDrive /D }
    Set-Location -LiteralPath $previousLocation.Path
}
exit $result
