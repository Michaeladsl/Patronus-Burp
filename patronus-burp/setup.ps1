# setup.ps1
# Run this ONCE before building: .\setup.ps1

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "  Patronus Burp - Build Setup" -ForegroundColor Yellow
Write-Host "  =============================" -ForegroundColor Yellow
Write-Host ""

# -- 1. Check Java -------------------------------------------------------
Write-Host "[1/3] Checking Java..." -ForegroundColor Cyan

try {
    $javaVersion = (& java -version 2>&1) | Select-String "version"
    Write-Host "      Found: $javaVersion" -ForegroundColor Green
} catch {
    Write-Host ""
    Write-Host "  ERROR: Java not found." -ForegroundColor Red
    Write-Host "  Option A - Use Burp bundled JDK (adjust path if needed):" -ForegroundColor Yellow
    Write-Host '  $env:JAVA_HOME = "C:\Program Files\BurpSuitePro\jre"' -ForegroundColor White
    Write-Host '  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"' -ForegroundColor White
    Write-Host ""
    Write-Host "  Option B - Install Java 17+ from: https://adoptium.net" -ForegroundColor White
    Write-Host ""
    exit 1
}

# -- 2. Download gradle-wrapper.jar --------------------------------------
Write-Host "[2/3] Downloading Gradle wrapper JAR..." -ForegroundColor Cyan

$wrapperDir = Join-Path $PSScriptRoot "gradle\wrapper"
$wrapperJar = Join-Path $wrapperDir "gradle-wrapper.jar"

if (Test-Path $wrapperJar) {
    Write-Host "      Already exists, skipping." -ForegroundColor Green
} else {
    New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
    $url = "https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
    try {
        Write-Host "      Downloading from GitHub..." -ForegroundColor Gray
        Invoke-WebRequest -Uri $url -OutFile $wrapperJar -UseBasicParsing
        Write-Host "      Saved to: $wrapperJar" -ForegroundColor Green
    } catch {
        Write-Host ""
        Write-Host "  ERROR: Could not download gradle-wrapper.jar" -ForegroundColor Red
        Write-Host "  Download manually from: $url" -ForegroundColor Yellow
        Write-Host "  Save to: $wrapperJar" -ForegroundColor White
        Write-Host ""
        exit 1
    }
}

# -- 3. Build the JAR ----------------------------------------------------
Write-Host "[3/3] Building patronus-burp.jar..." -ForegroundColor Cyan
Write-Host "      (First run downloads Gradle ~130MB - this is normal)" -ForegroundColor Gray
Write-Host ""

$gradlew = Join-Path $PSScriptRoot "gradlew.bat"
& $gradlew shadowJar

if ($LASTEXITCODE -eq 0) {
    $jar = Join-Path $PSScriptRoot "build\libs\patronus-burp.jar"
    Write-Host ""
    Write-Host "  BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host ""
    Write-Host "  JAR is at: $jar" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Next steps:" -ForegroundColor Yellow
    Write-Host "  1. Open Burp Suite" -ForegroundColor White
    Write-Host "  2. Extensions -> Add -> Java -> Select the JAR above" -ForegroundColor White
    Write-Host "  3. Look for the Patronus tab in Burp top navigation" -ForegroundColor White
    Write-Host ""
} else {
    Write-Host ""
    Write-Host "  BUILD FAILED - see errors above" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Common fixes:" -ForegroundColor Yellow
    Write-Host "  - Make sure Java 17+ is on your PATH" -ForegroundColor White
    Write-Host "  - Check your internet connection" -ForegroundColor White
    Write-Host "  - Try: .\gradlew.bat shadowJar --info  for more detail" -ForegroundColor White
    Write-Host ""
    exit 1
}
