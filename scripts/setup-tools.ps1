$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$previousLocation = Get-Location
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME

function Install-Zip([string]$Url, [string]$FileName, [string]$Destination) {
    $archive = Join-Path $projectRoot ('.tools\' + $FileName)
    Invoke-WebRequest -Uri $Url -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $Destination -Force
    Remove-Item -LiteralPath $archive -Force
}

try {
    Set-Location -LiteralPath $projectRoot
    New-Item -ItemType Directory -Force -Path '.tools' | Out-Null
    if (-not (Test-Path -LiteralPath '.tools\java')) {
        Install-Zip 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'jdk.zip' '.tools\java'
    }
    if (-not (Test-Path -LiteralPath '.tools\gradle-8.11.1')) {
        Install-Zip 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' 'gradle.zip' '.tools'
    }
    if (-not (Test-Path -LiteralPath '.tools\sdk\cmdline-tools\latest')) {
        Install-Zip 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'cmdline.zip' '.tools\sdk\cmdline-tools'
        Rename-Item -LiteralPath '.tools\sdk\cmdline-tools\cmdline-tools' -NewName 'latest'
    }
    $env:JAVA_HOME = (Get-ChildItem -LiteralPath '.tools\java' -Directory | Select-Object -First 1).FullName
    $env:ANDROID_HOME = Join-Path $projectRoot '.tools\sdk'
    1..50 | ForEach-Object { 'y' } | & '.\.tools\sdk\cmdline-tools\latest\bin\sdkmanager.bat' --sdk_root=$env:ANDROID_HOME 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK 安装未完成。' }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
    Set-Location -LiteralPath $previousLocation.Path
}
