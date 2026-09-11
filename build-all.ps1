$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Java 17 builds 1.20.0 - 1.20.4; Java 21 builds 1.20.5 - 1.21.11 (and can also build the older ones).
# Usage:
#   .\build-all.ps1          # build all versions
#   .\build-all.ps1 17       # build only Java 17-compatible versions
#   .\build-all.ps1 21       # build 1.20.5 - 1.20.6
#   .\build-all.ps1 1.21     # build 1.21 - 1.21.11 (Java 21)
$group = if ($args.Count -gt 0) { $args[0] } else { "all" }

$v1_21 = @("v1_21_0", "v1_21_1", "v1_21_2", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_7", "v1_21_8", "v1_21_9", "v1_21_10", "v1_21_11")

switch ($group) {
	"17"   { $projects = @("v1_20_0", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4") }
	"21"   { $projects = @("v1_20_5", "v1_20_6") }
	"1.21" { $projects = $v1_21 }
	"all"  { $projects = @("v1_20_0", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4", "v1_20_5", "v1_20_6") + $v1_21 }
	default {
		Write-Host "Unknown version group: $group" -ForegroundColor Red
		Write-Host "Usage: .\build-all.ps1 [17|21|1.21|all]"
		exit 1
	}
}

foreach ($p in $projects) {
	Write-Host ""
	Write-Host "==========================================" -ForegroundColor Cyan
	Write-Host "  Building $p" -ForegroundColor Cyan
	Write-Host "==========================================" -ForegroundColor Cyan

	& "$root\$p\gradlew.bat" -p "$root\$p" build --no-daemon
	if ($LASTEXITCODE -ne 0) {
		Write-Host "BUILD FAILED for $p" -ForegroundColor Red
		exit $LASTEXITCODE
	}

	$jar = Get-ChildItem -Path "$root\$p\build\libs" -Filter "*.jar" |
		Where-Object { $_.Name -notlike "*-sources.jar" } |
		Select-Object -First 1

	if ($jar) {
		Write-Host "Built: $($jar.FullName)" -ForegroundColor Green
	}
}

Write-Host ""
Write-Host "All builds succeeded." -ForegroundColor Green
