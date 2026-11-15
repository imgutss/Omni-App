#!/usr/bin/env bash
# Official Chromium NaiveProxy Android executables, pinned release and SHA256 per ABI.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 - "$ROOT_DIR" "${1:-$ROOT_DIR/libs-naive}" <<'PY'
import sys,json,hashlib,urllib.request,zipfile,io,os
from pathlib import Path
root,output=map(Path,sys.argv[1:])
for abi,asset in json.loads((root/'third_party/naiveproxy/assets.json').read_text()).items():
    with urllib.request.urlopen(asset['url'],timeout=120) as response: data=response.read()
    assert hashlib.sha256(data).hexdigest()==asset['sha256'], 'NaiveProxy archive checksum mismatch: '+abi
    archive=zipfile.ZipFile(io.BytesIO(data))
    binary=archive.read('lib/'+abi+'/libnaive.so')
    assert binary[:4]==b'\x7fELF'
    dest=output/abi/'libnaive.so';dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_bytes(binary);dest.chmod(0o755)
    print(abi,hashlib.sha256(binary).hexdigest(),flush=True)
PY
