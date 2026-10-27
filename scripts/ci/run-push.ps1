param(
    [string]$TargetRepo = "InstallAPK",
    [string[]]$Files,
    [string]$Message = "fix: update repository files",
    [string]$BaseDir = "G:\Mon Drive\05_Dev\InstallAPK"
)

# Configuration Encodage UTF-8 Standard (Antigravity)
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'


& "G:\Mon Drive\05_Dev\InstallAPK\scripts\ci\push-files.ps1" -Files $Files -CommitMessage $Message -RepoName $TargetRepo -BaseDir $BaseDir
