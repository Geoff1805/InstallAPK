<#
.SYNOPSIS
    Supervise et inspecte les workflows GitHub Actions du dépôt Geoff1805/InstallAPK,
    y compris les sous-erreurs d'étapes (steps) et les annotations.
#>

[CmdletBinding()]
param(
    [string]$CommitSha,
    [string]$Branch = "main",
    [switch]$Watch,
    [switch]$ShowSubErrors = $true,
    [int]$TimeoutSeconds = 600,
    [int]$IntervalSeconds = 15
)

# Configuration Encodage UTF-8 Standard (Antigravity)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'


$repoOwner = "Geoff1805"
$repoName = "InstallAPK"

$token = $env:GITHUB_TOKEN
if (-not $token) {
    $token = $env:GH_TOKEN
}
if (-not $token) {
    try {
        $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
        if ($ghCmd) {
            $token = (& $ghCmd.Source auth token).Trim()
        }
    } catch {}
}
if (-not $token) {
    $mcpConfigFile = "C:\Users\geoff\.gemini\config\mcp_config.json"
    if (Test-Path $mcpConfigFile) {
        try {
            $mcpConfig = Get-Content $mcpConfigFile -Raw | ConvertFrom-Json
            $authHeader = $mcpConfig.mcpServers.'github-mcp-server'.headers.Authorization
            if ($authHeader -match "Bearer\s+(.+)") {
                $token = $matches[1]
            } elseif ($authHeader) {
                $token = $authHeader
            }
        } catch {}
    }
}

if (-not $token) {
    Write-Error "Aucun jeton GitHub trouvé."
    exit 1
}

$headers = @{
    "Authorization" = if ($token -match "^Bearer ") { $token } else { "Bearer $token" }
    "Accept"        = "application/vnd.github+json"
    "User-Agent"    = "InstallAPK-CI-Watcher"
}

function Get-Runs {
    $uri = "https://api.github.com/repos/$repoOwner/$repoName/actions/runs?per_page=5"
    if ($CommitSha) {
        $uri += "&head_sha=$CommitSha"
    } elseif ($Branch) {
        $uri += "&branch=$Branch"
    }
    $response = Invoke-RestMethod -Uri $uri -Headers $headers
    return $response.workflow_runs
}

function Show-RunDetails {
    param($run)
    $statusColor = switch ($run.conclusion) {
        "success"   { "Green" }
        "failure"   { "Red" }
        "cancelled" { "DarkYellow" }
        default     { if ($run.status -eq "in_progress") { "Yellow" } else { "Gray" } }
    }
    $conclusionText = if ($run.conclusion) { $run.conclusion } else { $run.status }
    Write-Host "  -> [$($conclusionText.ToUpper())] $($run.name) (Run ID: $($run.id))" -ForegroundColor $statusColor
    Write-Host "     URL: $($run.html_url)" -ForegroundColor DarkGray

    if ($run.status -eq "completed" -or $run.conclusion -eq "failure") {
        try {
            $jobsResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$repoOwner/$repoName/actions/runs/$($run.id)/jobs" -Headers $headers
            foreach ($job in $jobsResponse.jobs) {
                $failedSteps = $job.steps | Where-Object { $_.conclusion -eq "failure" }
                if ($failedSteps) {
                    Write-Host "     [Sous-erreur Job: $($job.name)]" -ForegroundColor Red
                    foreach ($step in $failedSteps) {
                        Write-Host "       x Étape en échec : $($step.name)" -ForegroundColor Red
                    }
                }
            }
        } catch {}
    }
}

$startTime = Get-Date

do {
    $runs = Get-Runs
    if (-not $runs -or $runs.Count -eq 0) {
        Write-Host "Aucun workflow..." -ForegroundColor DarkGray
    } else {
        $inProgress = $runs | Where-Object { $_.status -in @("in_progress", "queued", "waiting", "requested") }
        $failed = $runs | Where-Object { $_.conclusion -eq "failure" }

        Write-Host "`n--- Statut GitHub Actions ($(Get-Date -Format 'HH:mm:ss')) ---" -ForegroundColor White
        foreach ($r in $runs) {
            Show-RunDetails -run $r
        }

        if (-not $Watch) {
            if ($failed.Count -gt 0) { exit 1 } else { exit 0 }
        }

        if ($inProgress.Count -eq 0) {
            Write-Host "`nTous les workflows sont terminés." -ForegroundColor Cyan
            if ($failed.Count -gt 0) {
                Write-Host "ERREUR : $($failed.Count) workflow(s) en échec !" -ForegroundColor Red
                exit 1
            } else {
                Write-Host "SUCCÈS : Tous les workflows sont au vert !" -ForegroundColor Green
                exit 0
            }
        }
    }

    if ($Watch) {
        $elapsed = ((Get-Date) - $startTime).TotalSeconds
        if ($elapsed -ge $TimeoutSeconds) {
            Write-Error "Délai d'attente ($TimeoutSeconds s) dépassé."
            exit 2
        }
        Write-Host "En attente ($($inProgress.Count) en cours)... Prochain contrôle dans $IntervalSeconds s." -ForegroundColor DarkGray
        Start-Sleep -Seconds $IntervalSeconds
    }
} while ($Watch)
