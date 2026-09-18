$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
$root = 'c:\Users\16024\Desktop\agent-platform'
$jar = "$root\agent-platform-core\target\agent-platform-core-1.0.0-SNAPSHOT.jar"
$port = 18105
# 装到临时目录：不污染用户真实的 data/skins
$surveyDir = "$env:TEMP\skin-survey\skins"
New-Item -ItemType Directory -Force -Path $surveyDir | Out-Null

$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList '--enable-preview', '-Xms128m', '-Xmx2g', '-jar', $jar, '--spring.profiles.active=embedded', "--server.port=$port", "--agent-platform.skins.dir=$surveyDir" -RedirectStandardOutput "$env:TEMP\ap-survey.log" -RedirectStandardError "$env:TEMP\ap-survey.err" -PassThru -WorkingDirectory $root

$base = "http://127.0.0.1:$port"
$H = @{ 'X-Tenant-Id' = 'default' }
$up = $false
for ($i = 0; $i -lt 70; $i++) {
    Start-Sleep -Seconds 1
    try { $r = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2; if ($r.status) { Write-Host "HEALTH=$($r.status)"; $up = $true; break } } catch { }
}
if (-not $up) { Write-Host 'SERVICE_NOT_UP'; Get-Content "$env:TEMP\ap-survey.log" -Tail 25; Stop-Process -Id $proc.Id -Force; exit 1 }

function PostJson($u, $o) {
    $json = $o | ConvertTo-Json -Depth 14
    return Invoke-RestMethod -Method Post -Uri $u -Headers $H -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 300
}

Write-Host ''
Write-Host '=== read catalog ==='
$rd = PostJson "$base/api/v1/skins/market/read" @{ url = 'https://kingofsoysauce.github.io/dsh-skin-market/' }
$all = @($rd.data.skins)
Write-Host ("  " + $all.Count + " skins in market")

# ---- 选样本：先跑 6 个看趋势（确认方向后再扩到 25）----
$MAX = 6
$picked = New-Object System.Collections.Generic.List[object]
$seen = New-Object System.Collections.Generic.HashSet[string]
function Pick($e, $why) {
    if ($null -eq $e) { return }
    if ($picked.Count -ge $MAX) { return }
    if ($seen.Add($e.id)) { $picked.Add([pscustomobject]@{ entry = $e; why = $why }) }
}

# 1) 必选：两个已知的皮肤（我们已逐行读过它们的 CSS，当基准）
Pick (@($all | Where-Object { $_.id -eq 'small-tailqwq.maid-atelier' })[0]) 'full-ui (known)'
Pick (@($all | Where-Object { $_.id -eq 'small-tailqwq.orca-link' })[0]) 'wallpaper+components (known)'
# 2) 各关键 tag 取一个（按 stars 最优），填到 $MAX 为止
$tagPriority = @('token-theme', 'glass', 'retro', 'animated', 'motion', 'anime', 'pixel',
                 'full-ui', 'local-images', 'dynamic-background', 'composer', 'pet')
foreach ($t in $tagPriority) {
    $e = @($all | Where-Object { $_.tags -contains $t } | Sort-Object -Property stars -Descending | Select-Object -First 1)[0]
    Pick $e ("tag:" + $t)
}

Write-Host ''
Write-Host ("=== installing " + $picked.Count + " sample skins into temp dir ===")
$ok = 0
$fail = 0
foreach ($p in $picked) {
    $e = $p.entry
    try {
        $t0 = Get-Date
        $ins = PostJson "$base/api/v1/skins/market/install" $e
        $sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
        $ok++
        Write-Host ('  OK   {0,-46} files={1,-5} failed={2,-4} {3}s   [{4}]' -f $e.id, $ins.data.files, $ins.data.failed, $sec, $p.why)
    } catch {
        $fail++
        Write-Host ('  FAIL {0,-46} {1}' -f $e.id, $_.Exception.Message)
    }
}
Write-Host ("  installed=" + $ok + " failed=" + $fail)

$bytes = 0
Get-ChildItem $surveyDir -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $bytes += $_.Length }
Write-Host ("  downloaded = " + [math]::Round($bytes / 1MB, 1) + " MB")

Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Write-Host ''
Write-Host '=== run contract survey (node) ==='
$outJson = "$env:TEMP\skin-contract-report.json"
& node "$root\desktop\skin-contract-survey.mjs" $surveyDir $outJson

Write-Host ''
Write-Host 'SURVEY_DONE'
