<#
.SYNOPSIS
  Builds the original .NET Framework test applications with Visual Studio's MSBuild and runs their tests,
  to show that what Renova migrates was sound to begin with. Run on Windows.

.DESCRIPTION
  Needs Visual Studio 2019 or 2022 (or the Build Tools) with the ".NET desktop development" and "ASP.NET and
  web development" workloads and the .NET Framework 4.7.2 and 4.8 targeting packs.

.EXAMPLE
  .\Build-Originals.ps1 -Apps C:\renova-test-apps
#>
param(
    [Parameter(Mandatory = $true)] [string] $Apps,
    [string[]] $Names = @('billing-netfx48', 'ledger-vbnet472', 'shop-mvc5', 'desk-winforms-net472', 'notes-wpf-net48', 'till-vb-winforms-net48')
)

$ErrorActionPreference = 'Stop'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw "Visual Studio or its Build Tools are not installed (no vswhere.exe)." }
$msbuild = & $vswhere -latest -products * -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1
$vstest = & $vswhere -latest -products * -find 'Common7\IDE\**\vstest.console.exe' | Select-Object -First 1
if (-not $msbuild) { throw "MSBuild was not found in the Visual Studio installation." }
Write-Host "MSBuild: $msbuild"

$results = @()
foreach ($name in $Names) {
    $dir = Join-Path $Apps $name
    if (-not (Test-Path $dir)) { $results += [pscustomobject]@{ App = $name; Build = 'not found'; Tests = '' }; continue }
    # A solution if there is one, else every project file.
    $targets = @(Get-ChildItem $dir -Filter *.sln)
    if ($targets.Count -eq 0) { $targets = @(Get-ChildItem $dir -Recurse -Include *.csproj, *.vbproj) }
    $built = $true
    foreach ($target in $targets) {
        & $msbuild $target.FullName /t:Restore /p:RestorePackagesConfig=true /v:quiet /nologo | Out-Null
        & $msbuild $target.FullName /p:Configuration=Debug /v:minimal /nologo
        if ($LASTEXITCODE -ne 0) { $built = $false }
    }
    $tests = 'none'
    if ($built -and $vstest) {
        $assemblies = @(Get-ChildItem $dir -Recurse -Filter *.Tests.dll | Where-Object { $_.FullName -match '\\bin\\' -and $_.FullName -notmatch '\\obj\\' })
        if ($assemblies.Count -gt 0) {
            # NUnit tests need its adapter, restored into the packages folder; MSTest is found by itself.
            $adapters = @(Get-ChildItem $dir -Recurse -Filter 'NUnit3.TestAdapter.dll' -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '\\packages\\' } | Select-Object -First 1)
            $arguments = @($assemblies | ForEach-Object { $_.FullName })
            if ($adapters.Count -gt 0) { $arguments += "/TestAdapterPath:$($adapters[0].DirectoryName)" }
            & $vstest @arguments
            $tests = if ($LASTEXITCODE -eq 0) { 'pass' } else { 'FAIL' }
        }
    }
    $results += [pscustomobject]@{ App = $name; Build = $(if ($built) { 'pass' } else { 'FAIL' }); Tests = $tests }
}
$results | Format-Table -AutoSize
if ($results | Where-Object { $_.Build -eq 'FAIL' -or $_.Tests -eq 'FAIL' }) { exit 1 }
