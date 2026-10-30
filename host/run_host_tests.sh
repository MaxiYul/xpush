#!/usr/bin/env bash
# Проверка ядра на ПК: собирает тестовый ISO "под XP", записывает в файл-образ разных размеров
# и независимо проверяет результат (MBR, FAT32, LFN, содержимое, правки SETUPLDR.BIN).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -nowarn -d "$OUT" app/src/main/java/io/xpusb/core/*.java host/HostBuild.java host/BotSim.java
cp -r app/src/main/resources/boot "$OUT/"
python3 host/make_test_iso.py "$OUT/test.iso" "$OUT/manifest.json"
for MB in 200 1024 4000 20000; do
  echo "=== образ ${MB} МБ"
  rm -f "$OUT/d.img"
  java -cp "$OUT" HostBuild "$OUT/test.iso" "$OUT/d.img" "$MB" > "$OUT/log.txt"
  python3 host/verify_fat32.py "$OUT/d.img" "$OUT/manifest.json" app/src/main/resources/boot
done
echo "=== эмуляция USB BOT/SCSI со сбоями"
java -Xmx1g -cp "$OUT" BotSim "$OUT/test.iso" "$OUT/sim.img" 400
python3 host/verify_fat32.py "$OUT/sim.img" "$OUT/manifest.json" app/src/main/resources/boot
echo "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
