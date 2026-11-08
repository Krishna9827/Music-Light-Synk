# One-shot Fire TV / Android TV setup for Music Light Synk (Windows PowerShell).
#
#   .\scripts\setup-firetv.ps1 -TvIp 192.168.1.50 -Apk .\music-light-synk.apk -HaUrl http://192.168.1.10:8123
#
# Needs adb.exe (Android platform-tools) on PATH and "ADB debugging" enabled on the TV:
#   Fire TV: Settings > My Fire TV > About > click the device name 7x, then
#            Developer options > ADB debugging = ON.
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [string]$Apk = "",
    [string]$HaUrl = "",
    [string]$WebhookId = "music_light_synk",
    [string]$HaToken = "",
    [ValidateSet("auto", "artwork", "screen")][string]$Source = "auto"
)
$ErrorActionPreference = "Stop"

$Pkg = "com.musiclightsynk"
$Listener = "$Pkg/$Pkg.MediaListenerService"
$Receiver = "$Pkg/.AdbCommandReceiver"
$TvHost = if ($TvIp -like "*:*") { $TvIp } else { "${TvIp}:5555" }

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Tv { & adb -s $TvHost @args }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
}

Step "Connecting to $TvHost (accept the 'Allow USB debugging?' prompt on the TV)"
& adb connect $TvHost | Out-Host
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    if ((& adb -s $TvHost get-state 2>$null) -eq "device") { $ok = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ok) { throw "TV did not authorise ADB. Accept the prompt on the TV and re-run." }

if (-not $Apk) {
    foreach ($c in @("music-light-synk.apk", "app\build\outputs\apk\release\app-release.apk")) {
        if (Test-Path $c) { $Apk = $c; break }
    }
}
if ($Apk) {
    Step "Installing $Apk"
    Tv install -r $Apk | Out-Host
}

Step "Granting media-session access (notification listener)"
Tv shell cmd notification allow_listener $Listener 2>$null | Out-Host
$current = (Tv shell settings get secure enabled_notification_listeners).Trim()
if ($current -notlike "*$Listener*") {
    $new = if ($current -eq "null" -or -not $current) { $Listener } else { "${current}:$Listener" }
    Tv shell settings put secure enabled_notification_listeners $new
}

Step "Allowing screen capture without a prompt (fallback when an app has no album art)"
Tv shell appops set $Pkg PROJECT_MEDIA allow | Out-Host
Tv shell appops set $Pkg SYSTEM_ALERT_WINDOW allow | Out-Host

Step "Opening the app once"
Tv shell am start -n "$Pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

if (-not $HaUrl) { $HaUrl = Read-Host "Home Assistant URL (e.g. http://192.168.1.10:8123)" }

Step "Configuring: $HaUrl  webhook=$WebhookId  source=$Source"
$cfg = @("shell", "am", "broadcast", "-f", "0x20", "-n", $Receiver, "-a", "$Pkg.CONFIGURE",
    "--es", "ha_url", $HaUrl, "--es", "webhook_id", $WebhookId, "--es", "source", $Source)
if ($HaToken) { $cfg += @("--es", "token", $HaToken) }
Tv @cfg | Out-Host

if ($Source -ne "artwork") {
    Step "Starting screen capture"
    Tv shell am start -n "$Pkg/.CaptureConsentActivity" | Out-Null
    Start-Sleep -Seconds 2
}

Step "Sending test colours (a rainbow around the edges, purple in the middle)"
Tv shell am broadcast -f 0x20 -n $Receiver -a "$Pkg.TEST" | Out-Host

Step "Status"
Tv shell am broadcast -f 0x20 -n $Receiver -a "$Pkg.STATUS" | Out-Host

Write-Host "`nDone. Play some music on the TV. Live log: adb -s $TvHost logcat -s MusicLightSynk"
