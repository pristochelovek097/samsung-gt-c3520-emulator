#!/bin/sh
cd "$(dirname "$0")"
echo "J2ME PHONE OS"
echo "Открой в браузере: http://127.0.0.1:8848"
echo "Ctrl+C — выйти"
exec python3 launcher.py
