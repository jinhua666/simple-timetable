param([int]$Port = 16384)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$previousLocation = Get-Location
$adb = Join-Path $projectRoot '.tools\sdk\platform-tools\adb.exe'
$device = "127.0.0.1:$Port"
$appId = 'cn.zxy.timetable.importtest'
$testAppId = "$appId.test"
try {
    Set-Location -LiteralPath $projectRoot
    & $adb connect $device
    if ($LASTEXITCODE -ne 0) { throw '无法连接 MuMu，请检查实例端口。' }
    foreach ($apk in @('app\build\outputs\apk\debug\app-debug.apk', 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        if (-not (Test-Path -LiteralPath $apk)) { throw '请先构建应用和设备测试包，命令见 README。' }
        & $adb -s $device install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "安装失败：$apk" }
    }
    $output = & $adb -s $device shell am instrument -w -r "$testAppId/androidx.test.runner.AndroidJUnitRunner" 2>&1
    $logs = Join-Path $projectRoot '.tools\logs'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $output | Tee-Object -FilePath (Join-Path $logs 'device-tests.log')
    if (($output -join "`n") -notmatch 'OK \(6 tests\)') { throw '设备测试未全部通过，请查看 .tools\logs\device-tests.log。' }
    & $adb -s $device uninstall $testAppId
    & $adb -s $device shell am start -n "$appId/cn.zxy.timetable.MainActivity"
} finally {
    Set-Location -LiteralPath $previousLocation.Path
}
