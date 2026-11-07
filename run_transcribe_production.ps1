[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$InputVideo,

    [Parameter(Mandatory = $true)]
    [string]$OutputDir,

    [string]$GradeLevel = '5th standard',
    [string]$AcademicLevel = 'primary'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$inputPath = (Resolve-Path -LiteralPath $InputVideo).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputDir)
$runId = [guid]::NewGuid().ToString('N')
$runRoot = Join-Path $projectRoot ("work\manual_{0}" -f $runId)
$materialsDir = Join-Path $runRoot 'materials'
$tempDir = Join-Path $runRoot 'temp'
$null = New-Item -ItemType Directory -Path $outputPath, $materialsDir, $tempDir -Force
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome) -or -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    $javaHome = 'C:\Program Files\Java\jdk-17'
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw 'Java 17 is required. Install it or set JAVA_HOME before running Transcribe.'
}

$overrides = @{
    TRANSCRIBE_INPUT_FILE = $inputPath
    TRANSCRIBE_INPUT_FOLDER = [System.IO.Path]::GetDirectoryName($inputPath)
    TRANSCRIBE_WATCH_FOLDER = 'false'
    TRANSCRIBE_OUTPUT_DIR = $outputPath
    TRANSCRIBE_GRADE_LEVEL = $GradeLevel
    TRANSCRIBE_ACADEMIC_LEVEL = $AcademicLevel
    TRANSCRIBE_MATERIALS_DIR = $materialsDir
    TRANSCRIBE_TEMP_DIR = $tempDir
    TRANSCRIBE_EXIT_WHEN_COMPLETE = 'true'
    JAVA_HOME = $javaHome
}
$previous = @{}
foreach ($name in $overrides.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$previousLocation = Get-Location

try {
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, [string]$overrides[$name], 'Process')
    }
    Set-Location -LiteralPath $projectRoot
    & (Join-Path $projectRoot 'mvnw.cmd') '-Dmaven.test.skip=true' 'spring-boot:run'
    if ($LASTEXITCODE -ne 0) {
        throw "Transcribe production run failed with exit code $LASTEXITCODE."
    }
}
finally {
    Set-Location -LiteralPath $previousLocation
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}

$baseName = [System.IO.Path]::GetFileNameWithoutExtension($inputPath)
Write-Host "Transcribe production complete. Contract: $(Join-Path $outputPath ($baseName + '_contract.json'))"
