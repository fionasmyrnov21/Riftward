#!/usr/bin/env bash
set -euo pipefail
riftward_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$riftward_root"
source "$riftward_root/Tools/java-env.sh"
riftward_python="${PYTHON:-python3}"
if [[ -x /usr/bin/python3 ]]; then riftward_python=/usr/bin/python3; fi
"$riftward_python" Tools/generate_catalog.py
./gradlew :app:assembleDebug --console=plain "$@"
mkdir -p Builds
cp app/build/outputs/apk/debug/app-debug.apk Builds/Riftward-debug.apk
"$riftward_python" - <<'PY'
from pathlib import Path
import hashlib
apk = Path('Builds/Riftward-debug.apk')
apk.with_suffix('.apk.sha256').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
PY
echo 'APK: Builds/Riftward-debug.apk'
