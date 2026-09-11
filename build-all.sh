#!/bin/bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

# Java 17 builds 1.20.0 - 1.20.4; Java 21 builds 1.20.5 - 1.21.11 (and can also build the older ones).
# Usage:
#   bash build-all.sh          # build all versions
#   bash build-all.sh 17        # build only Java 17-compatible versions
#   bash build-all.sh 21        # build 1.20.5 - 1.20.6
#   bash build-all.sh 1.21      # build 1.21 - 1.21.11 (Java 21)
V1_21=("v1_21_0" "v1_21_1" "v1_21_2" "v1_21_3" "v1_21_4" "v1_21_5" "v1_21_6" "v1_21_7" "v1_21_8" "v1_21_9" "v1_21_10" "v1_21_11")
case "${1:-all}" in
	17)
		PROJECTS=("v1_20_0" "v1_20_1" "v1_20_2" "v1_20_3" "v1_20_4")
		;;
	21)
		PROJECTS=("v1_20_5" "v1_20_6")
		;;
	1.21)
		PROJECTS=("${V1_21[@]}")
		;;
	all|"")
		PROJECTS=("v1_20_0" "v1_20_1" "v1_20_2" "v1_20_3" "v1_20_4" "v1_20_5" "v1_20_6" "${V1_21[@]}")
		;;
	*)
		echo "Unknown version group: $1"
		echo "Usage: bash build-all.sh [17|21|1.21|all]"
		exit 1
		;;
esac

for p in "${PROJECTS[@]}"; do
	echo ""
	echo "=========================================="
	echo "  Building $p"
	echo "=========================================="

	chmod +x "$ROOT/$p/gradlew"
	"$ROOT/$p/gradlew" -p "$ROOT/$p" build --no-daemon

	echo "Built: $p"
done

echo ""
echo "All builds succeeded."