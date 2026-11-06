#!/usr/bin/env python3
"""Release-APK UI smoke test. No tunnel is started and no profile credentials are read."""
import os,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE='net.omnigate.app'
OUT=Path('ui-results');OUT.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
def dump():
    adb('shell','uiautomator','dump','/sdcard/omni-ui.xml')
    data=adb('shell','cat','/sdcard/omni-ui.xml')
    return ET.fromstring(data[data.index('<?xml'):])
def swipe():
    size=list(map(int,re.findall(r'\d+',adb('shell','wm','size'))[-2:]));w,h=size
    adb('shell','input','swipe',str(w//2),str(h*85//100),str(w//2),str(h*30//100),'350')

def labels(tree):
    return [(n.attrib.get('text',''),n.attrib.get('content-desc',''))for n in tree.iter('node')]
def find(label):
    for n in dump().iter('node'):
        if label in (n.attrib.get('text'),n.attrib.get('content-desc')):
            return n
    return None
def tap(label):
    for _ in range(7):
        n=find(label)
        if n is not None:
            nums=list(map(int,re.findall(r'\d+',n.attrib['bounds'])));
            if nums[2]>nums[0] and nums[3]>nums[1]:
                adb('shell','input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2));time.sleep(1);return
        swipe();time.sleep(.6)
    raise AssertionError('UI control not found: '+label+'; '+repr(labels(dump())))
def screenshot(name):
    with (OUT/(name+'.png')).open('wb')as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def launch():
    adb('shell','monkey','-p',PACKAGE,'-c','android.intent.category.LAUNCHER','1');time.sleep(4)
    assert adb('shell','pidof',PACKAGE).strip(),'App did not remain running'
def menu():tap('Open navigation menu')
def selected(label):
    n=find(label)
    return n is not None and (n.attrib.get('checked')=='true'or n.attrib.get('selected')=='true')

try:
    adb('logcat','-c');launch();screenshot('01-home')
    menu();screenshot('02-drawer');tap('Blue');screenshot('03-blue-theme')
    assert selected('Blue'),'Theme chip does not expose its selected state'
    tap('Off');assert selected('Off'),'Effects chip did not change'
    # Abrupt restart checks persisted appearance, not only the in-memory composition.
    adb('shell','am','force-stop',PACKAGE);launch();menu()
    for _ in range(7):
        if find('Blue')is not None:break
        swipe();time.sleep(.6)
    assert selected('Blue'),'Theme did not persist across process restart'
    for _ in range(4):
        if find('Off')is not None:break
        swipe();time.sleep(.6)
    assert selected('Off'),'Effects setting did not persist'
    screenshot('04-restored-appearance')
    adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    menu();tap('Settings');screenshot('05-settings')
    assert adb('shell','pidof',PACKAGE).strip()
    adb('shell','settings','put','system','font_scale','1.3');time.sleep(2)
    screenshot('06-large-text-settings')
    assert adb('shell','pidof',PACKAGE).strip()
    adb('shell','settings','put','system','font_scale','1.0')
    print('PASS: native launch, drawer, theme/effects selection, process-restart persistence, settings navigation')
finally:
    (OUT/'ui.xml').write_text(ET.tostring(dump(),encoding='unicode'))
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-s','AndroidRuntime:E'))
