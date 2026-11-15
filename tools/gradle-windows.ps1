# Shared Windows bootstrap for every Gradle build.
# All environment changes are scoped to the child PowerShell process.
$ErrorActionPreference = 'Stop'
$gradleArguments = $args
$projectRoot = Split-Path $PSScriptRoot -Parent
$scratchRoot = [IO.Path]::GetTempPath()
$profileRoot = $env:USERPROFILE
if (!$profileRoot) { $profileRoot = [Environment]::GetFolderPath('UserProfile') }
if (!$profileRoot) { $profileRoot = $scratchRoot }

function Assert-WritableDirectory([string] $Path) {
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
    $probe = Join-Path $Path ([Guid]::NewGuid().ToString() + '.probe')
    try { [IO.File]::WriteAllText($probe, '') }
    finally { if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe } }
}

try {
    # Gradle cannot snapshot Windows cloud-placeholder DLLs even when their bytes are readable.
    $packagesDesktop = @($gradleArguments | Where-Object { $_ -match '(?:^|:)(?:createDistributable|package[A-Za-z]*)$' }).Count -gt 0
    $vlcBinary = Join-Path $projectRoot 'app/app-resources/windows/vlc/libvlc.dll'
    if ($packagesDesktop -and !$env:SOKOUL_VLC_DIR -and (Test-Path -LiteralPath $vlcBinary)) {
        if ((Get-Item -LiteralPath $vlcBinary).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            $env:SOKOUL_VLC_DIR = node (Join-Path $PSScriptRoot 'materialize-vlc.cjs')
            if ($LASTEXITCODE -ne 0 -or !$env:SOKOUL_VLC_DIR) { throw 'Cannot prepare libVLC build input.' }
        }
    }
    # Use explicit project overrides; inherited JVM defaults can resolve to C:\.
    $env:GRADLE_USER_HOME = if ($env:SOKOUL_GRADLE_HOME) { $env:SOKOUL_GRADLE_HOME } else { Join-Path $scratchRoot 'sokoul-gradle' }
    $env:SOKOUL_BUILD_ROOT = if ($env:SOKOUL_BUILD_ROOT) { $env:SOKOUL_BUILD_ROOT } else { Join-Path $projectRoot 'build-output' }
    $runtimeHome = Join-Path $scratchRoot 'sokoul-gradle-runtime'
    $projectCache = Join-Path $scratchRoot 'sokoul-build/project-cache'
    foreach ($directory in @($env:GRADLE_USER_HOME, $env:SOKOUL_BUILD_ROOT, $runtimeHome, $projectCache)) {
        Assert-WritableDirectory $directory
    }
    $env:ANDROID_USER_HOME = Join-Path $runtimeHome '.android'
    $env:ANDROID_SDK_HOME = $runtimeHome
    Assert-WritableDirectory $env:ANDROID_USER_HOME
    # Preserve the user's signing identity when initializing the isolated Android home.
    $signingKey = Join-Path $profileRoot '.android/debug.keystore'
    if (!(Test-Path -LiteralPath $signingKey)) {
        $signingKey = Join-Path $scratchRoot 'sokoul-android-user/.android/debug.keystore'
    }
    $runtimeKey = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
    if (!(Test-Path -LiteralPath $runtimeKey) -and (Test-Path -LiteralPath $signingKey)) {
        Copy-Item -LiteralPath $signingKey -Destination $runtimeKey
    }
    # An explicit existing store avoids AGP's default-store generation lock.
    # Validation and APK signing still run normally with the same key material.
    if (Test-Path -LiteralPath $runtimeKey) {
        $signingDirectory = Join-Path $runtimeHome 'signing'
        Assert-WritableDirectory $signingDirectory
        $explicitStore = Join-Path $signingDirectory 'debug.keystore'
        if (!(Test-Path -LiteralPath $explicitStore)) {
            Copy-Item -LiteralPath $runtimeKey -Destination $explicitStore
        }
        $env:SOKOUL_DEBUG_KEYSTORE = $explicitStore
    }
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' "-Duser.home=' + $runtimeHome + '"').Trim()
    $env:SOKOUL_GRADLE_BOOTSTRAPPED = '1'

    $properties = Get-Content (Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
    if ($properties -notmatch 'gradle-([0-9.]+)-bin\.zip') { throw 'Cannot determine the pinned Gradle version.' }
    $version = $Matches[1]
    $launcher = $null
    # Prefer an installed distribution of exactly the wrapper version, avoiding a redundant download.
    foreach ($cacheRoot in @($env:GRADLE_USER_HOME, (Join-Path $profileRoot '.gradle'))) {
        $distributionRoot = Join-Path $cacheRoot "wrapper/dists/gradle-$version-bin"
        if (Test-Path -LiteralPath $distributionRoot) {
            foreach ($distribution in Get-ChildItem -LiteralPath $distributionRoot -Directory) {
                $candidate = Join-Path $distribution.FullName "gradle-$version/bin/gradle.bat"
                if (Test-Path -LiteralPath $candidate) { $launcher = $candidate; break }
            }
        }
        if ($launcher) { break }
    }
    if (!$launcher) { $launcher = Join-Path $projectRoot 'gradlew.bat' }
    # Kotlin's separate daemon writes client marker files under LOCALAPPDATA even
    # with an isolated JVM home. Compile in the Gradle JVM in managed Windows sessions.
    & $launcher --project-cache-dir $projectCache '-Pkotlin.compiler.execution.strategy=in-process' @gradleArguments
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('Sokoul Gradle setup: ' + $_.Exception.Message)
    exit 1
}
