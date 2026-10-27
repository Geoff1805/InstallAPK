param(
    [string[]]$Files,
    [string]$CommitMessage = "fix: update repository files",
    [string]$RepoName = "InstallAPK",
    [string]$BaseDir = "G:\Mon Drive\05_Dev\InstallAPK"
)

# Configuration Encodage UTF-8 Standard (Antigravity)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'


$ErrorActionPreference = "Stop"

$repoOwner = "Geoff1805"
$branch = "main"

$token = $null
try {
    $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($ghCmd) {
        $token = (& $ghCmd.Source auth token).Trim()
    }
} catch {}

if ([string]::IsNullOrWhiteSpace($token)) {
    $mcpConfigFile = "C:\Users\geoff\.gemini\config\mcp_config.json"
    $mcpConfig = Get-Content $mcpConfigFile -Raw | ConvertFrom-Json
    $authHeader = $mcpConfig.mcpServers.'github-mcp-server'.headers.Authorization
    $token = if ($authHeader -match "Bearer\s+(.+)") { $matches[1] } else { $authHeader }
}

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept"        = "application/vnd.github+json"
    "User-Agent"    = "Antigravity-GitTree-Pusher"
}

function Invoke-GitHubApi {
    param(
        [string]$Method = "GET",
        [string]$Uri,
        [string]$JsonBody = $null
    )
    if ($Method -eq "GET") {
        return (Invoke-RestMethod -Uri $Uri -Headers $headers)
    }
    
    $tempJson = [System.IO.Path]::GetTempFileName()
    try {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($tempJson, $JsonBody, $utf8NoBom)
        $resp = & curl.exe -s -X $Method `
            -H "Authorization: Bearer $token" `
            -H "Accept: application/vnd.github+json" `
            -H "User-Agent: Antigravity-GitTree-Pusher" `
            -H "Content-Type: application/json" `
            --data-binary "@$tempJson" `
            $Uri
        
        $obj = $resp | ConvertFrom-Json
        if ($null -ne $obj.message -and $null -eq $obj.sha) {
            throw "GitHub API Error on ${Method} ${Uri}: $($obj.message)"
        }
        return $obj
    } finally {
        if (Test-Path $tempJson) { Remove-Item $tempJson -Force }
    }
}

# 1. Get latest commit SHA on main
$refUri = "https://api.github.com/repos/$repoOwner/$RepoName/git/refs/heads/$branch"
$refData = Invoke-GitHubApi -Method "GET" -Uri $refUri
$latestCommitSha = $refData.object.sha
Write-Host "Latest commit: $latestCommitSha"

# Get commit tree SHA
$commitUri = "https://api.github.com/repos/$repoOwner/$RepoName/git/commits/$latestCommitSha"
$commitData = Invoke-GitHubApi -Method "GET" -Uri $commitUri
$baseTreeSha = $commitData.tree.sha
Write-Host "Base tree: $baseTreeSha"

$treeItems = @()

$normalizedFiles = @()
foreach ($item in $Files) {
    if ($null -ne $item) {
        $parts = $item -split ','
        foreach ($p in $parts) {
            $cleaned = $p.Trim().Trim('"').Trim("'")
            if ($cleaned.Length -gt 0) {
                $normalizedFiles += $cleaned
            }
        }
    }
}

foreach ($relPath in $normalizedFiles) {
    $localPath = Join-Path $BaseDir $relPath
    if (-not (Test-Path $localPath)) {
        throw "File missing: $localPath"
    }
    
    $bytes = [System.IO.File]::ReadAllBytes($localPath)
    $b64 = [Convert]::ToBase64String($bytes)
    
    $blobBody = @{
        content = $b64
        encoding = "base64"
    } | ConvertTo-Json
    
    $blobUri = "https://api.github.com/repos/$repoOwner/$RepoName/git/blobs"
    $blobResp = Invoke-GitHubApi -Method "POST" -Uri $blobUri -JsonBody $blobBody
    $blobSha = $blobResp.sha
    
    $treeItems += @{
        path = $relPath.Replace("\", "/")
        mode = "100644"
        type = "blob"
        sha  = $blobSha
    }
    Write-Host "Uploaded blob for $relPath -> $blobSha"
}

# 2. Create Tree
$treeBody = @{
    base_tree = $baseTreeSha
    tree      = $treeItems
} | ConvertTo-Json -Depth 5

$treeUri = "https://api.github.com/repos/$repoOwner/$RepoName/git/trees"
$treeResp = Invoke-GitHubApi -Method "POST" -Uri $treeUri -JsonBody $treeBody
$newTreeSha = $treeResp.sha
Write-Host "New tree created: $newTreeSha"

# 3. Create Commit
$newCommitBody = @{
    message = $CommitMessage
    tree    = $newTreeSha
    parents = @($latestCommitSha)
} | ConvertTo-Json

$createCommitUri = "https://api.github.com/repos/$repoOwner/$RepoName/git/commits"
$newCommitResp = Invoke-GitHubApi -Method "POST" -Uri $createCommitUri -JsonBody $newCommitBody
$newCommitSha = $newCommitResp.sha
Write-Host "New commit created: $newCommitSha"

# 4. Update branch ref
$updateRefBody = @{
    sha   = $newCommitSha
    force = $false
} | ConvertTo-Json

$updateRefResp = Invoke-GitHubApi -Method "PATCH" -Uri $refUri -JsonBody $updateRefBody
Write-Host "Branch $branch successfully updated to commit $newCommitSha"
return $newCommitSha
