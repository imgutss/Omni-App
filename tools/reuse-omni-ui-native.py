#!/usr/bin/env python3
"""Reuse audited native bits ONLY for a UI-only release; fail closed on engine/source changes."""
from pathlib import Path
import hashlib,json,re,subprocess,tempfile,urllib.request,zipfile

root=Path(__file__).resolve().parents[1]
pin=json.loads((root/'tools/omni-ui-native-base.json').read_text())
protected=['AndroidLibXrayLite','aether','hev-socks5-tunnel','third_party','guts','compile-*.sh',
 'V2rayNG/gradle/libs.versions.toml','V2rayNG/app/src/main/AndroidManifest.xml',
 'V2rayNG/app/src/main/java/com/v2ray/ang/core','V2rayNG/app/src/main/java/com/v2ray/ang/service',
 'V2rayNG/app/src/main/java/com/v2ray/ang/handler','V2rayNG/app/src/main/java/com/v2ray/ang/dto',
 'V2rayNG/app/src/main/java/com/v2ray/ang/fmt','V2rayNG/app/src/main/java/com/v2ray/ang/root',
 'V2rayNG/app/src/main/java/com/v2ray/ang/contracts']
subprocess.run(['git','diff','--exit-code',pin['commit'],'HEAD','--',*protected],cwd=root,check=True)
# Only three exact public branding URLs may change in AppConfig. Every other
# configuration byte remains compared to the pinned native-source baseline.
from omni_branding_guard import same_native_app_config
app_config='V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt'
baseline_config=subprocess.check_output(['git','show',pin['commit']+':'+app_config],cwd=root,text=True)
assert same_native_app_config(baseline_config,(root/app_config).read_text()),'Native AppConfig change: rebuild native inputs'

# A dependency declared directly in the app Gradle file can own JNI too. Only the
# release version fields may differ; all dependency/build configuration stays pinned.
gradle='V2rayNG/app/build.gradle.kts'
def normalize_gradle(text):
    return re.sub(r'(?m)^\s*version(?:Code|Name)\s*=.*$', '', text)
old=subprocess.check_output(['git','show',pin['commit']+':'+gradle],cwd=root,text=True)
assert normalize_gradle(old)==normalize_gradle((root/gradle).read_text()),'Non-version app Gradle change: rebuild all native inputs'
subprocess.run(['git','diff','--exit-code',pin['commit'],'HEAD','--','V2rayNG/build.gradle.kts',
 'V2rayNG/settings.gradle.kts','V2rayNG/gradle.properties','V2rayNG/gradle/wrapper'],cwd=root,check=True)
assert len(pin['sha256'])==4,'All four baseline ABI artifacts must be pinned'
owned={'libaether.so','libcottendns.so','libdnstt.so','libguts.so','libhev-socks5-tunnel.so',
 'libhevsockstun.so','liblyrebird.so','libmasterdns.so','libnaive.so','libnoizdns.so',
 'libpsiphon-tunnel-core.so','libshadowquic.so','libsingbox.so','libslipstream.so','libsoar.so','libvaydns.so'}
audit={}
with tempfile.TemporaryDirectory(prefix='omni-native-')as temp:
 for name,expected in pin['sha256'].items():
  target=Path(temp)/name
  repository=pin.get('repository','imgutss/omni-android')
  assert repository in {'imgutss/Omni-App','imgutss/omni-android'},'Unexpected native artifact owner'
  urllib.request.urlretrieve('https://github.com/'+repository+'/releases/download/'+pin['release']+'/'+name,target)
  assert hashlib.file_digest(target.open('rb'),'sha256').hexdigest()==expected,'Baseline APK checksum mismatch: '+name
  with zipfile.ZipFile(target)as apk:
   for member in apk.namelist():
    parts=member.split('/')
    if len(parts)!=3 or parts[0]!='lib' or parts[2]not in owned:continue
    binary=apk.read(member);assert binary[:4]==b'\x7fELF',member
    dest=root/'V2rayNG/app/libs'/parts[1]/parts[2];dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(binary)
    audit[member]=hashlib.sha256(binary).hexdigest()
  print('Verified and restored',name,flush=True)
(root/'native-ui-reuse-audit.json').write_text(json.dumps({'sourceCommit':pin['commit'],'release':pin['release'],'libraries':audit},indent=2))
print('Native libraries match the hash-pinned '+pin['release']+' baseline byte-for-byte; no core upgrade.')
