#!/bin/sh
# Сборка собственного J2ME-ядра OwnJ2ME (без внешних зависимостей).
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
find src -name '*.java' > /tmp/ownj2me-sources.txt
javac -d build/classes @/tmp/ownj2me-sources.txt
cd build/classes
jar cfe ../ownj2me.jar dev.ownj2me.Emulator .
echo "Готово: own-emulator/build/ownj2me.jar"
