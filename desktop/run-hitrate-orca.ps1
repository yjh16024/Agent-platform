$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
$root = 'c:\Users\16024\Desktop\agent-platform'
$jar = "$root\agent-platform-core\target\agent-platform-core-1.0.0-SNAPSHOT.jar"
$port = 18112
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList '--enable-preview', '-Xms128m', '-Xmx1g', '-jar', $jar, '--spring.profiles.active=embedded', "--server.port=$port" -RedirectStandardOutput "$env:TEMP\ap-ho.log" -RedirectStandardError "$env:TEMP\ap-ho.err" -PassThru -WorkingDirectory $root

$base = "http://127.0.0.1:$port"
$H = @{ 'X-Tenant-Id' = 'default' }
$up = $false
for ($i = 0; $i -lt 70; $i++) {
    Start-Sleep -Seconds 1
    try { $r = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2; if ($r.status) { Write-Host "HEALTH=$($r.status)"; $up = $true; break } } catch { }
}
if (-not $up) { Write-Host 'SERVICE_NOT_UP'; Get-Content "$env:TEMP\ap-ho.log" -Tail 25; Stop-Process -Id $proc.Id -Force; exit 1 }

function PostJson($u, $o) {
    $json = $o | ConvertTo-Json -Depth 14
    return Invoke-RestMethod -Method Post -Uri $u -Headers $H -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 300
}

$ID = 'small-tailqwq.orca-link'
Write-Host ''
Write-Host "=== install $ID (hostDep 14%, CSS only for DSH) ==="
$rd = PostJson "$base/api/v1/skins/market/read" @{ url = 'https://kingofsoysauce.github.io/dsh-skin-market/' }
$entry = @($rd.data.skins | Where-Object { $_.id -eq $ID })[0]
$ins = PostJson "$base/api/v1/skins/market/install" $entry
Write-Host ("  files=" + $ins.data.files + " failed=" + $ins.data.failed)
$b = Invoke-RestMethod -Uri "$base/api/v1/skins/bundle?id=$ID" -Headers $H -TimeoutSec 120
Write-Host ("  bundle = " + $b.data.path + "  (" + $b.data.size + " bytes)")
Write-Host ("  contains __ModuleLoader__ = " + $b.data.text.Contains('__ModuleLoader__'))

$electron = "$root\desktop\node_modules\electron\dist\electron.exe"
$env:AP_PORT = "$port"
$env:AP_SKIN_ID = $ID
$env:AP_SKIN_DIR = "$root\data\skins\$ID"
$out = "$env:TEMP\skin-hitrate-orca.json"
$env:AP_OUT = $out
Remove-Item $out -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=== running hit-rate harness (headless) ==='
& $electron "$root\desktop\skin-hitrate-harness.cjs" 2>&1 | Out-String | Write-Host
Start-Sleep -Seconds 3
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

if (Test-Path $out) {
    $rep = Get-Content $out -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host ''
    Write-Host '================================================================'
    Write-Host ("  SKIN = " + $ID + "    bodyAttr = " + $rep.bodyAttr)
    Write-Host ("  css  = " + (@($rep.cssFiles) -join ', ') + "    selectors = " + $rep.selectorCount)
    Write-Host '================================================================'
    Write-Host ''
    Write-Host ("  cssFrom = " + $rep.cssFrom + "   bundle = " + $rep.bundlePath + "   cssFiles = " + (@($rep.cssFiles) -join ', '))
    Write-Host ''
    Write-Host '  >>> SKIN-OWN NODES: did the skin actually create what its own CSS targets? <<<'
    Write-Host ''
    Write-Host ('    {0,-14} {1,16} {2,8}' -f 'route', 'skinOwn hit/tot', 'pct')
    foreach ($s in @($rep.summary)) {
        Write-Host ('    {0,-14} {1,16} {2,8}' -f $s.route, ($s.skinOwnHits.ToString() + '/' + $s.skinOwnTotal), ($s.skinOwnPct.ToString() + '%'))
    }
    Write-Host ''
    Write-Host '      --- skin-own selectors NOT satisfied (top 12, /#/chat) ---'
    foreach ($s in @($rep.summary)) {
        if ($s.route -ne '/#/chat') { continue }
        @($s.skinOwnMissedTop) | Select-Object -First 12 | ForEach-Object { Write-Host ("        " + $_) }
    }
    Write-Host ''
    Write-Host '  >>> HOST CONTRACT COVERAGE: PAGE x STATE MATRIX (only selectors that really depend on the host) <<<'
    Write-Host ''
    Write-Host ('    {0,-14} {1,7} {2,13} {3,13} {4,15} {5,7}' -f 'route', 'els', 'light/hero', 'dark/hero', 'dark/active', 'skinJs')
    foreach ($s in @($rep.summary)) {
        Write-Host ('    {0,-14} {1,7} {2,13} {3,13} {4,15} {5,7}' -f `
                $s.route, $s.elements, `
            ($s.hostDepHits.ToString() + '/' + $s.hostDepTotal + ' ' + $s.hostDepPct + '%'), `
            ($s.darkHits.ToString() + '/' + $s.hostDepTotal + ' ' + $s.darkPct + '%'), `
            ($s.darkActiveHits.ToString() + '/' + $s.hostDepTotal + ' ' + $s.darkActivePct + '%'), `
            $(if ($s.skinJsOk) { 'ok' } else { 'FAIL' }))
    }
    Write-Host ''
    foreach ($s in @($rep.summary)) {
        if (-not $s.byKind) { continue }
        Write-Host ("  --- " + $s.route + " ---")
        foreach ($p in $s.byKind.PSObject.Properties) {
            $k = $p.Name
            $tot = $p.Value
            $hit = 0
            if ($s.byKindHit -and ($s.byKindHit.PSObject.Properties.Name -contains $k)) { $hit = $s.byKindHit.$k }
            $dhit = 0
            if ($s.byKindDarkHit -and ($s.byKindDarkHit.PSObject.Properties.Name -contains $k)) { $dhit = $s.byKindDarkHit.$k }
            Write-Host ('      {0,-20} light {1,4}  dark {2,4}  / {3,-4}' -f $k, $hit, $dhit, $tot)
        }
        Write-Host '      still missed in DARK (top 10):'
        @($s.hostMissedDarkTop) | Select-Object -First 10 | ForEach-Object { Write-Host ("        " + $_) }
        Write-Host ''
    }
    if ($rep.routes.PSObject.Properties.Name -contains '/#/chat') {
        $c = $rep.routes.'/#/chat'
        if ($c.skinJs) {
            Write-Host '  --- skin JS on /#/chat ---'
            Write-Host ("  bundle      = " + $c.skinJs.bundlePath + "  (" + $c.skinJs.bundleSize + " bytes)")
            Write-Host ("  registeredId= " + $c.skinJs.registeredId)
            Write-Host ("  loaded = " + $c.skinJs.loaded + "  applied = " + $c.skinJs.applied + "  disposers = " + $c.skinJs.disposerCount)
            Write-Host ("  effectLabel = " + $c.skinJs.effectLabel)
            Write-Host ("  deps req    = " + ((@($c.skinJs.deps) | Select-Object -Unique) -join ', '))
            Write-Host ("  errors      = " + ((@($c.skinJs.errors)) -join ' | '))
        }
        if ($c.domAfter) { Write-Host ("  body attrs  = " + ((@($c.domAfter.bodyAttrs)) -join ', ')) }
        Write-Host ''
        Write-Host '  >>> scene.ts conversation-root probe (决定 hero/active 场景切换能否工作) <<<'
        if ($c.sceneRoot.found) {
            Write-Host ("      FOUND  phase=" + $c.sceneRoot.phase + "  tag=" + $c.sceneRoot.tag)
        } else {
            Write-Host ("      NOT FOUND   phases=[" + ((@($c.sceneRoot.phases)) -join ', ') + "]  scrollCount=" + $c.sceneRoot.scrollCount)
        }
        Write-Host ''
        Write-Host '  >>> SKIN-CREATED DOM + HOST STRUCTURE PROBE (on /#/chat) <<<'
        if ($c.markers) {
            foreach ($p in $c.markers.PSObject.Properties) {
                Write-Host ('      {0,-18} {1}' -f $p.Name, $p.Value)
            }
        }
        $ba = @($c.domAfter.bodyAttrs)
        $sceneAttr = 'ABSENT'
        foreach ($a in $ba) { if ($a -like 'data-orca-scene*') { $sceneAttr = 'SET' } }
        Write-Host ("      skin's own scene mirror (body[data-orca-scene]) = " + $sceneAttr)
    }
    if ($rep.fatal) { Write-Host ("  FATAL: " + $rep.fatal) }
} else { Write-Host '!! no report' }
Write-Host ''
Write-Host 'HITRATE_ORCA_DONE'
