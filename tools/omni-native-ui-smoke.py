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
def swipe(up=True):
    size=list(map(int,re.findall(r'\d+',adb('shell','wm','size'))[-2:]));w,h=size
    start,end=(85,30)if up else(30,85)
    adb('shell','input','swipe',str(w//2),str(h*start//100),str(w//2),str(h*end//100),'350')

def labels(tree):
    return [(n.attrib.get('text',''),n.attrib.get('content-desc',''))for n in tree.iter('node')]
def find(label):
    for n in dump().iter('node'):
        if label in (n.attrib.get('text'),n.attrib.get('content-desc')):
            return n
    return None
def reveal(label):
    # Drawer scroll is retained when closed: search both directions, not only down.
    w,h=list(map(int,re.findall(r'\d+',adb('shell','wm','size'))[-2:]))
    for up in (True,False):
        for _ in range(8):
            n=find(label)
            if n is not None:
                nums=list(map(int,re.findall(r'-?\d+',n.attrib['bounds'])))
                x,y=(nums[0]+nums[2])//2,(nums[1]+nums[3])//2
                if nums[2]>nums[0] and nums[3]>nums[1] and 0<=x<w and 0<=y<h:
                    return n
            swipe(up);time.sleep(.6)
    raise AssertionError('UI control not found: '+label+'; '+repr(labels(dump())))
def tap(label):
    n=reveal(label);nums=list(map(int,re.findall(r'-?\d+',n.attrib['bounds'])))
    adb('shell','input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2));time.sleep(1)
def screenshot(name):
    with (OUT/(name+'.png')).open('wb')as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,check=True)
def launch():
    adb('shell','monkey','-p',PACKAGE,'-c','android.intent.category.LAUNCHER','1');time.sleep(4)
    assert adb('shell','pidof',PACKAGE).strip(),'App did not remain running'
def menu():tap('Open navigation menu')
def selected(label):
    # Compose exposes the chip label as a TextView child; checked state belongs to
    # its nearest checkable parent. Assert that real accessibility owner, not text.
    reveal(label)
    tree=dump();parents={child:parent for parent in tree.iter()for child in parent}
    for node in tree.iter('node'):
        if label not in(node.get('text'),node.get('content-desc')):continue
        while node is not None:
            if node.get('checkable')=='true':return node.get('checked')=='true'
            if node.get('selected')=='true':return True
            node=parents.get(node)
    return False

try:
    # The fresh emulator has no user-granted notification permission. Grant only
    # that permission so the OS prompt cannot obscure the app's UI controls.
    adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    adb('logcat','-c');launch();screenshot('01-home')
    menu();screenshot('02-drawer');tap('Blue');screenshot('03-blue-theme')
    assert selected('Blue'),'Theme chip does not expose its selected state'
    tap('Off');assert selected('Off'),'Effects chip did not change'
    # Abrupt restart checks persisted appearance, not only the in-memory composition.
    adb('shell','am','force-stop',PACKAGE);launch();menu()
    assert selected('Blue'),'Theme did not persist across process restart'
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
