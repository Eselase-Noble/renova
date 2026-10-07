<#
.SYNOPSIS
  Runs an original ASP.NET MVC application in IIS Express beside its migrated ASP.NET Core version and compares
  their answers to the same requests. Run on Windows.

.DESCRIPTION
  The original is built with Visual Studio's MSBuild and served by IIS Express; the migrated project is started
  with "dotnet run". Each path is requested from both, and the status code, the content type and the body are
  compared. Bodies are compared after removing what differs on every request (anti-forgery tokens) and runs of
  white space. Results go to the console and to a CSV file.

  Needs Visual Studio (or the Build Tools) with the "ASP.NET and web development" workload, IIS Express, and
  the .NET SDK the migrated project targets.

.EXAMPLE
  .\Compare-AspNet.ps1 -Original C:\renova-test-apps\shop-mvc5 -Migrated C:\renova-out\shop -WebProject Shop.Web
#>
param(
    [Parameter(Mandatory = $true)] [string] $Original,
    [Parameter(Mandatory = $true)] [string] $Migrated,
    [Parameter(Mandatory = $true)] [string] $WebProject,
    [string[]] $Paths = @('/', '/Products', '/category/food', '/Products/Details/1', '/Products/Details/99', '/Products/Details',
                          '/Products/Create', '/api/stock', '/api/stock/3', '/api/stock/99', '/Content/site.css'),
    [int] $OriginalPort = 5080,
    [int] $MigratedPort = 5081,
    [string] $Report = 'aspnet-comparison.csv'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw "Visual Studio or its Build Tools are not installed (no vswhere.exe)." }
$msbuild = & $vswhere -latest -products * -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1
$iisExpress = Join-Path $env:ProgramFiles 'IIS Express\iisexpress.exe'
if (-not (Test-Path $iisExpress)) { throw "IIS Express is not installed ($iisExpress)." }

$originalWeb = (Resolve-Path (Join-Path $Original $WebProject)).Path
$migratedWeb = (Resolve-Path (Join-Path $Migrated $WebProject)).Path

Write-Host "Building the original with $msbuild"
$solution = Get-ChildItem $Original -Filter *.sln | Select-Object -First 1
& $msbuild $solution.FullName /t:Restore /p:RestorePackagesConfig=true /v:quiet /nologo | Out-Null
& $msbuild $solution.FullName /p:Configuration=Debug /v:minimal /nologo
if ($LASTEXITCODE -ne 0) { throw "The original application does not build." }

Write-Host "Building the migrated application"
& dotnet build $migratedWeb --nologo -v:q
if ($LASTEXITCODE -ne 0) { throw "The migrated application does not build." }

function Normalize([string] $body) {
    $text = $body -replace 'name="__RequestVerificationToken"[^>]*value="[^"]*"', 'name="__RequestVerificationToken" value=""'
    return ($text -replace '\s+', ' ').Trim()
}

function Wait-For([System.Net.Http.HttpClient] $client, [string] $url) {
    for ($i = 0; $i -lt 60; $i++) {
        try { $client.GetAsync($url).GetAwaiter().GetResult() | Out-Null; return } catch { Start-Sleep -Seconds 1 }
    }
    throw "Nothing answered at $url within a minute."
}

$original = $null
$migrated = $null
try {
    $original = Start-Process -FilePath $iisExpress -ArgumentList "/path:`"$originalWeb`"", "/port:$OriginalPort" -PassThru -WindowStyle Hidden
    $migrated = Start-Process -FilePath 'dotnet' -ArgumentList 'run', '--no-build', '--project', "`"$migratedWeb`"", '--urls', "http://localhost:$MigratedPort" -PassThru -WindowStyle Hidden

    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    Wait-For $client "http://localhost:$OriginalPort/"
    Wait-For $client "http://localhost:$MigratedPort/"

    $rows = @()
    foreach ($path in $Paths) {
        $a = $client.GetAsync("http://localhost:$OriginalPort$path").GetAwaiter().GetResult()
        $b = $client.GetAsync("http://localhost:$MigratedPort$path").GetAwaiter().GetResult()
        $bodyA = Normalize ($a.Content.ReadAsStringAsync().GetAwaiter().GetResult())
        $bodyB = Normalize ($b.Content.ReadAsStringAsync().GetAwaiter().GetResult())
        $typeA = if ($a.Content.Headers.ContentType) { $a.Content.Headers.ContentType.MediaType } else { '' }
        $typeB = if ($b.Content.Headers.ContentType) { $b.Content.Headers.ContentType.MediaType } else { '' }
        $statusSame = [int]$a.StatusCode -eq [int]$b.StatusCode
        # The body of an error page is the server's own and differs between IIS and Kestrel; only its status counts.
        $bodySame = ([int]$a.StatusCode -ge 400) -or ($bodyA -eq $bodyB)
        $where = ''
        if (-not $bodySame) {
            $n = [Math]::Min($bodyA.Length, $bodyB.Length)
            $at = 0
            while ($at -lt $n -and $bodyA[$at] -eq $bodyB[$at]) { $at++ }
            $from = [Math]::Max(0, $at - 30)
            $where = "at character ${at}: '" + $bodyA.Substring($from, [Math]::Min(70, $bodyA.Length - $from)) + "' became '" + $bodyB.Substring($from, [Math]::Min(70, $bodyB.Length - $from)) + "'"
        }
        $rows += [pscustomobject]@{
            Path     = $path
            Original = [int]$a.StatusCode
            Migrated = [int]$b.StatusCode
            Type     = $(if ($typeA -eq $typeB) { $typeA } else { "$typeA -> $typeB" })
            Result   = $(if ($statusSame -and $bodySame -and $typeA -eq $typeB) { 'same' } else { 'DIFFERENT' })
            Detail   = $where
        }
    }
    $rows | Format-Table Path, Original, Migrated, Type, Result -AutoSize
    $rows | Where-Object { $_.Detail } | ForEach-Object { Write-Host "$($_.Path): $($_.Detail)" }
    $rows | Export-Csv -Path $Report -NoTypeInformation
    $different = @($rows | Where-Object { $_.Result -ne 'same' }).Count
    Write-Host "$($rows.Count - $different) of $($rows.Count) request(s) answered the same. Details: $Report"
    if ($different -gt 0) { exit 1 }
}
finally {
    foreach ($process in @($original, $migrated)) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    # dotnet run starts the application as a child process of its own.
    Get-CimInstance Win32_Process -Filter "Name = 'dotnet.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*localhost:$MigratedPort*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
