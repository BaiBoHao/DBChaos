param(
    [ValidateSet("build", "preview-help", "build-and-help")]
    [string]$Command = "build",
    [string]$JarName = "DBChaos-0.0.1"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$TargetDir = Join-Path $ProjectRoot "target\win-build"
$ClassesDir = Join-Path $TargetDir "classes"
$FatJarDir = Join-Path $TargetDir "fat-jar"
$OutputJar = Join-Path $ProjectRoot "target\$JarName.jar"
$RootCopyJar = Join-Path $ProjectRoot "$JarName.jar"
$ClassPath = "$ClassesDir;$ProjectRoot\resources;$ProjectRoot\lib\*"

function Require-Tool {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required tool: $Name"
    }
}

function Prepare-Dirs {
    Remove-Item -Recurse -Force $ClassesDir, $FatJarDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $ClassesDir, $FatJarDir, (Split-Path -Parent $OutputJar) | Out-Null
}

function Compile-Sources {
    $sources = Get-ChildItem -Path (Join-Path $ProjectRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    if (-not $sources -or $sources.Count -eq 0) {
        throw "No Java sources found under src/"
    }

    & javac -encoding UTF-8 -cp "$ProjectRoot\lib\*" -d $ClassesDir $sources
    Copy-Item -Path (Join-Path $ProjectRoot "resources\*") -Destination $ClassesDir -Recurse -Force
}

function Expand-Dependencies {
    Copy-Item -Path (Join-Path $ClassesDir "*") -Destination $FatJarDir -Recurse -Force
    $deps = Get-ChildItem -Path (Join-Path $ProjectRoot "lib") -Filter *.jar -File
    foreach ($dep in $deps) {
        Push-Location $FatJarDir
        try {
            & jar xf $dep.FullName
        } finally {
            Pop-Location
        }
    }

    $metaInf = Join-Path $FatJarDir "META-INF"
    if (Test-Path $metaInf) {
        Get-ChildItem -Path $metaInf -Recurse -File -Include *.SF, *.DSA, *.RSA | Remove-Item -Force
    }
}

function Package-Jar {
    Remove-Item -Force $OutputJar, $RootCopyJar -ErrorAction SilentlyContinue
    Push-Location $FatJarDir
    try {
        & jar cfe $OutputJar chaos.Main .
    } finally {
        Pop-Location
    }
    Copy-Item -Path $OutputJar -Destination $RootCopyJar -Force
}

function Show-PreviewHelp {
    & java -cp $ClassPath chaos.Main --help
}

function Build-Jar {
    Prepare-Dirs
    Compile-Sources
    Expand-Dependencies
    Package-Jar
    Write-Host "Build completed."
    Write-Host "Output jar: $OutputJar"
    Write-Host "Root copy : $RootCopyJar"
}

Require-Tool javac
Require-Tool jar

switch ($Command) {
    "build" {
        Build-Jar
    }
    "preview-help" {
        Prepare-Dirs
        Compile-Sources
        Show-PreviewHelp
    }
    "build-and-help" {
        Build-Jar
        & java -jar $OutputJar --help
    }
}
