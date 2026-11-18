#!/usr/bin/env python3
"""Validate the active graphics/icon sources and their inclusion in the Android build."""
from pathlib import Path
import json, struct, wave, zipfile, subprocess, sys, re, hashlib
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets';graphics=root/'graphics';icons=root/'app-icons';checks=0
def check(ok,message):
 global checks
 checks+=1
 if not ok:raise AssertionError(message)
def png(path):
 b=path.read_bytes();check(b[:8]==b'\x89PNG\r\n\x1a\n',f'Not a PNG: {path.relative_to(root)}')
 return b,struct.unpack('>II',b[16:24])
c=json.loads((assets/'content/catalog.json').read_text())
for folder in ['app/src/main/java','app/src/main/assets/content','app-icons']:
 for p in (root/folder).rglob('*'):
  if p.is_file() and p.suffix in ['.kt','.xml','.json']:
   check(not re.search(r'[\u0400-\u04ff]',p.read_text()),f'Cyrillic game text: {p.relative_to(root)}')
for key,size in dict(roles=4,rooms=18,enemies=9,weapons=6,mods=12,supplies=6,artifacts=8,events=12,echoes=18).items():
 check(len(c[key])==size,f'Wrong count: {key}');check(len({r['id'] for r in c[key]})==size,f'Duplicate IDs: {key}')
art=['menu-station','crew-portraits','field-atlas','crew-directions','station-atlas','artifact-atlas']
for name in art:
 b,(w,h)=png(graphics/(name+'.png'));check(w>100 and h>100,f'Tiny image: {name}')
 if name in ['field-atlas','crew-directions','artifact-atlas']:check(b[25] in (4,6),f'Missing transparency: {name}')
_,(w,h)=png(icons/'drawable/app_icon.png');check(w==h and w>=192,'Launcher icon must be square and at least 192 pixels')
check((graphics/'fonts/NotoSans-Regular.ttf').is_file(),'Missing game font')
check((graphics/'fonts/OFL.txt').is_file(),'Missing font license')
check(not list(assets.rglob('*.png')),'Keep bitmap sources in graphics/ or app-icons/')
used={'click','move','shot','reload','interact','ability','supply','hit','round','emergency','extract','warning','upgrade','menu','mission','ambience-0','ambience-1','ambience-2'}
sounds=list((assets/'audio').glob('*.wav'));check({p.stem for p in sounds}==used,'Missing or unused audio files')
for p in sounds:
 with wave.open(str(p)) as f:check(f.getnchannels()==1 and f.getframerate()==22050 and f.getnframes()>100,f'Bad sound: {p.name}')
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
check('android.permission.INTERNET' not in manifest,'Unexpected internet permission')
check('android:screenOrientation="sensorPortrait"' in manifest,'Activity must use portrait orientation')
check('android:icon="@drawable/app_icon"' in manifest and 'android:roundIcon="@drawable/app_icon"' in manifest,'Launcher icon references changed')
subprocess.run([sys.executable,str(root/'Tools/generate_catalog.py'),'--check'],check=True)
apk=root/'Builds/Riftward-debug.apk'
if apk.exists():
 with zipfile.ZipFile(apk) as z:
  names=z.namelist()
  check('classes.dex' in names,'Missing game code')
  check(not any(p.endswith('.so') for p in names),'Unexpected native engine library')
  for name in art:
   check(z.read('assets/'+name+'.png')==(graphics/(name+'.png')).read_bytes(),f'APK image is out of date: {name}')
  check('res/drawable/app_icon.png' in names,'Launcher icon missing from APK')
  check(z.read('assets/fonts/NotoSans-Regular.ttf')==(graphics/'fonts/NotoSans-Regular.ttf').read_bytes(),'APK font is out of date')
  check(z.read('assets/content/catalog.json')==(assets/'content/catalog.json').read_bytes(),'APK catalog is out of date')
  check({Path(p).stem for p in names if p.startswith('assets/audio/') and p.endswith('.wav')}==used,'APK audio files are out of date')
 check(apk.with_suffix('.apk.sha256').read_text().split()[0]==hashlib.sha256(apk.read_bytes()).hexdigest(),'APK checksum is out of date')
screenshots=list((root/'screenshots').glob('*.png'))
if screenshots:
 check(len(screenshots)==8,'Expected 8 game stage screenshots')
 for p in screenshots:check(struct.unpack('>II',p.read_bytes()[16:24])==(1080,2400),f'Expected Medium Phone screenshot: {p.name}')
print(f'ASSETS PASS: {checks} checks; 6 game images, 1 launcher icon, {len(sounds)} audio files')
