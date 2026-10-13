param(
    [string]$TargetRepo = "InstallAPK",
    [string[]]$Files,
    [string]$Message = "fix: update repository files",
    [string]$BaseDir = "G:\Mon Drive\05_Dev\InstallAPK"
)

& "G:\Mon Drive\05_Dev\InstallAPK\scripts\ci\push-files.ps1" -Files $Files -CommitMessage $Message -RepoName $TargetRepo -BaseDir $BaseDir
