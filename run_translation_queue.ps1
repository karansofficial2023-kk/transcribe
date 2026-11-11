[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$InputManifest,

    [Parameter(Mandatory = $true)]
    [string]$OutputDir,

    [string]$GradeLevel = '10th standard',
    [string]$AcademicLevel = 'secondary',
    [string]$TargetLanguages = 'hi'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($TargetLanguages)) {
    throw '-TargetLanguages is required, for example -TargetLanguages "hi".'
}

$projectRoot = $PSScriptRoot
$manifestPath = (Resolve-Path -LiteralPath $InputManifest).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputDir)
$runId = [guid]::NewGuid().ToString('N')
$runRoot = Join-Path $projectRoot ("work\queue_{0}" -f $runId)
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
    TRANSCRIBE_INPUT_FILE = ''
    TRANSCRIBE_INPUT_MANIFEST = $manifestPath
    TRANSCRIBE_INPUT_FOLDER = [System.IO.Path]::GetDirectoryName($manifestPath)
    TRANSCRIBE_WATCH_FOLDER = 'false'
    TRANSCRIBE_OUTPUT_DIR = $outputPath
    TRANSCRIBE_GRADE_LEVEL = $GradeLevel
    TRANSCRIBE_ACADEMIC_LEVEL = $AcademicLevel
    TRANSCRIBE_MATERIALS_DIR = $materialsDir
    TRANSCRIBE_TEMP_DIR = $tempDir
    TRANSCRIBE_EXIT_WHEN_COMPLETE = 'true'
    STORYBOARD_TARGET_LANGUAGES = $TargetLanguages
    TRANSCRIBE_AUDIO_TRANSLATION_ONLY = 'true'
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
        throw "Transcribe translation queue failed with exit code $LASTEXITCODE. Check its per-video status files in $outputPath."
    }
}
finally {
    Set-Location -LiteralPath $previousLocation
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}

Write-Host "Transcribe translation queue complete. Contracts are in: $outputPath"
