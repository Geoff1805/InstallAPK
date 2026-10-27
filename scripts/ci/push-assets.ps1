param(
    [string]$CommitMessage = "feat(icon): add modern application icon, adaptive icons and branding"
)

# Configuration Encodage UTF-8 Standard (Antigravity)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'


$ErrorActionPreference = "Stop"

$repoOwner = "Geoff1805"
$repoName = "InstallAPK"
$branch = "main"

$mcpConfigFile = "C:\Users\geoff\.gemini\config\mcp_config.json"
$mcpConfig = Get-Content $mcpConfigFile -Raw | ConvertFrom-Json
$authHeader = $mcpConfig.mcpServers.'github-mcp-server'.headers.Authorization
$token = if ($authHeader -match "Bearer\s+(.+)") { $matches[1] } else { $authHeader }

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept"        = "application/vnd.github+json"
    "User-Agent"    = "Antigravity-GitTree-Pusher"
}

# 1. Get latest commit SHA on main
$refUri = "https://api.github.com/repos/$repoOwner/$repoName/git/refs/heads/$branch"
$refData = Invoke-RestMethod -Uri $refUri -Headers $headers
$latestCommitSha = $refData.object.sha
Write-Host "Latest commit: $latestCommitSha"

# Get commit tree SHA
$commitUri = "https://api.github.com/repos/$repoOwner/$repoName/git/commits/$latestCommitSha"
$commitData = Invoke-RestMethod -Uri $commitUri -Headers $headers
$baseTreeSha = $commitData.tree.sha
Write-Host "Base tree: $baseTreeSha"

# List of files to commit
$filesToCommit = @(
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
    "app/src/main/java/fr/installapk/app/ui/MainScreen.kt",
    "README.md",
    "AGENTS.md",
    "app/src/main/res/mipmap-mdpi/ic_launcher.png",
    "app/src/main/res/mipmap-mdpi/ic_launcher_round.png",
    "app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png",
    "app/src/main/res/mipmap-hdpi/ic_launcher.png",
    "app/src/main/res/mipmap-hdpi/ic_launcher_round.png",
    "app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png",
    "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png",
    "app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png",
    "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png",
    "app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png",
    "app/src/main/res/drawable/app_logo.png",
    "app/src/main/ic_launcher-web.png"
)

$treeItems = @()

foreach ($relPath in $filesToCommit) {
    $localPath = Join-Path "G:\Mon Drive\05_Dev\InstallAPK" $relPath
    if (-not (Test-Path $localPath)) {
        throw "File missing: $localPath"
    }
    
    $bytes = [System.IO.File]::ReadAllBytes($localPath)
    $b64 = [Convert]::ToBase64String($bytes)
    
    $blobBody = @{
        content = $b64
        encoding = "base64"
    } | ConvertTo-Json
    
    $blobUri = "https://api.github.com/repos/$repoOwner/$repoName/git/blobs"
    $blobResp = Invoke-RestMethod -Uri $blobUri -Method Post -Headers $headers -Body $blobBody
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

$treeUri = "https://api.github.com/repos/$repoOwner/$repoName/git/trees"
$treeResp = Invoke-RestMethod -Uri $treeUri -Method Post -Headers $headers -Body $treeBody
$newTreeSha = $treeResp.sha
Write-Host "New tree created: $newTreeSha"

# 3. Create Commit
$newCommitBody = @{
    message = $CommitMessage
    tree    = $newTreeSha
    parents = @($latestCommitSha)
} | ConvertTo-Json

$createCommitUri = "https://api.github.com/repos/$repoOwner/$repoName/git/commits"
$newCommitResp = Invoke-RestMethod -Uri $createCommitUri -Method Post -Headers $headers -Body $newCommitBody
$newCommitSha = $newCommitResp.sha
Write-Host "New commit created: $newCommitSha"

# 4. Update branch ref
$updateRefBody = @{
    sha   = $newCommitSha
    force = $false
} | ConvertTo-Json

$updateRefResp = Invoke-RestMethod -Uri $refUri -Method Patch -Headers $headers -Body $updateRefBody
Write-Host "Branch $branch successfully updated to commit $newCommitSha"
return $newCommitSha
