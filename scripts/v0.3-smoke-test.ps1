$ErrorActionPreference = "Stop"

$baseUrl = "http://127.0.0.1:18081"

function Assert-Ok($Name, $ScriptBlock) {
  try {
    & $ScriptBlock | Out-Null
    Write-Host "[OK] $Name"
  } catch {
    Write-Error "[FAIL] $Name - $($_.Exception.Message)"
    exit 1
  }
}

Assert-Ok "GET /local-api/health" { Invoke-RestMethod "$baseUrl/local-api/health" }
Assert-Ok "GET /local-api/runtime" { Invoke-RestMethod "$baseUrl/local-api/runtime" }
Assert-Ok "GET /local-api/assets" { Invoke-RestMethod "$baseUrl/local-api/assets" }
Assert-Ok "GET /local-api/commands" { Invoke-RestMethod "$baseUrl/local-api/commands" }
Assert-Ok "POST /local-api/commands/reload" { Invoke-RestMethod -Method Post "$baseUrl/local-api/commands/reload" }
Assert-Ok "GET /local-api/actions" { Invoke-RestMethod "$baseUrl/local-api/actions" }
Assert-Ok "POST /local-api/actions/test" {
  Invoke-RestMethod -Method Post "$baseUrl/local-api/actions/test" -ContentType "application/json" -Body '{"actionCode":"idle"}'
}
Assert-Ok "POST /local-api/actions/clear" { Invoke-RestMethod -Method Post "$baseUrl/local-api/actions/clear" }
Assert-Ok "Console page" {
  $r = Invoke-WebRequest "$baseUrl/console/index.html" -UseBasicParsing
  if ($r.StatusCode -ne 200) { throw "Console status $($r.StatusCode)" }
}
Assert-Ok "Renderer page" {
  $r = Invoke-WebRequest "$baseUrl/renderer/index.html" -UseBasicParsing
  if ($r.StatusCode -ne 200) { throw "Renderer status $($r.StatusCode)" }
}

Write-Host "V0.3 smoke test passed."
