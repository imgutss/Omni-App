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
def back_to_main():
    # Settings has its own nested routes, including UI settings. A single Back
    # need not leave the activity, especially after configuration recreation.
    for _ in range(6):
        if find('Open navigation menu') is not None:return
        adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    raise AssertionError('Back navigation did not return to the server screen: '+repr(labels(dump())))
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
    # Match the reference's 412x892 app viewport, plus 24dp status/navigation insets.
    adb('shell','wm','size','1236x2820');adb('shell','wm','density','480')
    adb('shell','settings','put','system','font_scale','1.0')
    (OUT/'viewport.txt').write_text(adb('shell','wm','size')+adb('shell','wm','density'))
    # The fresh emulator has no user-granted notification permission. Grant only
    # that permission so the OS prompt cannot obscure the app's UI controls.
    adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    adb('logcat','-c');launch();screenshot('01-home')
    menu();reveal('Menu')
    deadline=time.monotonic()+45
    while True:
        gl=adb('logcat','-d','-s','OmniReferenceGL:I')
        assert 'renderer failed' not in gl,gl
        if 'rendered successfully' in gl:break
        assert time.monotonic()<deadline,'Native 3D logo never produced a first frame: '+gl
        time.sleep(.75)
    (OUT/'render-proof.txt').write_text(gl)
    time.sleep(.5);screenshot('02-drawer');tap('Blue');screenshot('03-blue-theme')
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
    adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    back_to_main();menu();tap('Violet');tap('Full');reveal('Menu');screenshot('07-reference-drawer')
    adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    # Real activities only. Merely opening the tools never starts a VPN or a probe.
    for i,label in enumerate(('Omni Hub','Omni Doctor','Omni Privacy & Backup','Omni DNS Lab','Omni Routing Studio','Omni Sync Center','Omni Speed & Ping Lab','DNS Scanner','Omni Scanner'),8):
        menu();tap(label);time.sleep(.8)
        assert adb('shell','pidof',PACKAGE).strip(),'Activity crashed: '+label
        screenshot(f'{i:02d}-'+re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-'))
        if label=='Omni Scanner':
            assert find('WARP / Aether scanner') is not None,'Scanner chooser missing real WARP route'
            assert find('DNS Scanner') is not None,'Scanner chooser missing real DNS route'
            tap('WARP / Aether scanner');screenshot('17-real-warp-tool')
            assert adb('shell','pidof',PACKAGE).strip(),'WARP tool crashed'
            adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
        adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    menu();tap('Logcat');time.sleep(3);screenshot('18-real-log-slab')
    assert find('LIVE') is not None,'Native log polling did not start'
    assert any('Original mesh and PBR shaders rendered successfully' in t for t,d in labels(dump())),'Real renderer log never reached the native viewer'
    for label in ('Copy log','Share log','Clear log','Refresh'):
        assert find(label) is not None,'Existing log action lost: '+label
    tap('LIVE');assert find('PAUSED') is not None,'Native log pause did not take effect'
    screenshot('19-paused-real-log-slab')
    tap('Refresh');tap('PAUSED');assert find('LIVE') is not None
    assert adb('shell','pidof',PACKAGE).strip()
    adb('shell','cmd','uimode','night','no');time.sleep(3);screenshot('20-light-log-slab')
    assert find('LIVE') is not None,'Log viewing state lost on mode recreation'
    adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1);menu();reveal('Menu');screenshot('21-light-drawer')
    assert adb('shell','pidof',PACKAGE).strip()
    print('PASS: native 3D first frame, appearance persistence, large text, ten real tools, WARP chooser route, actual renderer log, live/pause and retained log actions')
finally:
    (OUT/'ui.xml').write_text(ET.tostring(dump(),encoding='unicode'))
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-s','AndroidRuntime:E','OmniReferenceGL:V'))
