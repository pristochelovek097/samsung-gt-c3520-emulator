#!/bin/sh
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac -d build/classes $(find src -name '*.java')
cd build/classes
jar cfe ../arm9.jar arm.Main .
echo "Готово: arm-emulator/build/arm9.jar"
