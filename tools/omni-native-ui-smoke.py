#!/usr/bin/env python3
"""Release-APK UI smoke test. No tunnel is started and no profile credentials are read."""
import os,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE='net.omnigate.app'
OUT=Path('ui-results');OUT.mkdir(exist_ok=True)
def adb(*args):
    try:return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:
        print('ADB failure output:',error.output,flush=True);raise
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
    tree=dump()
    assert any(n.get('package')==PACKAGE for n in tree.iter('node')),'Native app not visible: '+name
    (OUT/(name+'.xml')).write_text(ET.tostring(tree,encoding='unicode'))
    print('Native UI captured:',name,flush=True)
def launch():
    # Launch the app directly; Monkey routes through a launcher that can ANR while
    # the emulator display configuration changes. Never dismiss an Omni ANR.
    def start():
        adb('shell','am','start','-W','-n',PACKAGE+'/com.v2ray.ang.ui.main.MainActivity')
        time.sleep(4)
    start()
    tree=dump()
    if any("Pixel Launcher isn't responding" in t for t,d in labels(tree)):
        (OUT/'launcher-anr.txt').write_text(ET.tostring(tree,encoding='unicode'))
        tap('Close app');start()
    assert adb('shell','pidof',PACKAGE).strip(),'App did not remain running'
    assert not any("isn't responding" in t for t,d in labels(dump())),'Application/emulator ANR obscures the app'

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
    # Put the native app in front before changing display metrics. Resizing an
    # active Pixel Launcher caused an external ANR on the software-rendered AVD.
    adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    adb('logcat','-c');launch()
    # Reference content is 412x892dp; gesture navigation has 24dp OS insets.
    # Two-times raster resolution keeps the software renderer below 1.6MP.
    adb('shell','cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.gestural')
    adb('shell','wm','size','824x1880');adb('shell','wm','density','320')
    adb('shell','settings','put','system','font_scale','1.0');time.sleep(3)
    (OUT/'viewport.txt').write_text(adb('shell','wm','size')+adb('shell','wm','density'))
    (OUT/'window-insets.txt').write_text(adb('shell','dumpsys','window','displays'))
    screenshot('01-home')
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
    for label in ('Copy log','Share log','Clear log','Refresh log'):
        assert find(label) is not None,'Existing log action lost: '+label
    tap('LIVE');assert find('PAUSED') is not None,'Native log pause did not take effect'
    screenshot('19-paused-real-log-slab')
    tap('Refresh log');tap('PAUSED');assert find('LIVE') is not None
    assert adb('shell','pidof',PACKAGE).strip()
    adb('shell','cmd','uimode','night','no');time.sleep(3);screenshot('20-light-log-slab')
    assert find('LIVE') is not None,'Log viewing state lost on mode recreation'
    adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1);menu();reveal('Menu');screenshot('21-light-drawer')
    assert adb('shell','pidof',PACKAGE).strip()
    back_to_main();adb('shell','cmd','uimode','night','yes');time.sleep(2)
    # Empty editors are real native screens, not browser previews or prefilled demo forms.
    editor_labels=('Add [VMess]','Add [VLESS]','Add [Trojan]','Add [Shadowsocks]','Add [SOCKS]','Add [HTTP]','Add [Hysteria2]','Add [WireGuard]','Add [NaiveProxy]','Add [TUIC v5]','Add [ShadowQUIC]','MasterDNS','DNSTT','GUTS','Add [Policy group]','Add [Proxy chain]','Add Aether config manually')
    for i,label in enumerate(editor_labels,22):
        tap('Add configuration');tap(label);time.sleep(.7)
        screenshot(f'{i:02d}-native-editor-'+re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-'))
        assert adb('shell','pidof',PACKAGE).strip(),'Native editor crashed: '+label
        if label=='Add Aether config manually':
            tap('Get new WARP keys…');screenshot('native-aether-keys');assert adb('shell','pidof',PACKAGE).strip(),'WARP keys editor crashed'
        back_to_main()
    # Test-only loopback fixture, entered through the real editor and persisted normally.
    # No tunnel, credentials, successful ping or server metrics are manufactured.
    tap('Add configuration');tap('Add [SOCKS]');time.sleep(1)
    for index,value in enumerate(('CI-LOCAL-SOCKS','127.0.0.1','9')):
        fields=[n for n in dump().iter('node')if n.get('class')=='android.widget.EditText' and n.get('package')==PACKAGE]
        fields.sort(key=lambda n:tuple(map(int,re.findall(r'-?\d+',n.get('bounds',''))))[1::-1])
        assert len(fields)>=3,'Common native editable fields missing'
        bounds=list(map(int,re.findall(r'-?\d+',fields[index].get('bounds',''))))
        adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
        adb('shell','input','keycombination','113','29');adb('shell','input','text',value);time.sleep(.5)
        # The first three fields fit above the IME. Do not send Back based on
        # mInputShown: a hardware-keyboard AVD may report it with no visible IME.
    screenshot('33-real-local-editor');tap('Save');back_to_main()
    assert find('CI-LOCAL-SOCKS') is not None,'Real edited profile did not persist'
    assert find('Start service') is not None,'Fixture unexpectedly started a tunnel'
    screenshot('34-real-server-row')
    tap('Share config');screenshot('35-real-share-dialog');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
    tap('Edit');screenshot('36-real-persisted-editor');back_to_main()
    # Exercise the remaining original drawer destinations and their actual form routes.
    for label,slug in (('Subscriptions','subscriptions'),('Per-app settings','per-app'),('Routing Settings','routing'),('Asset files','assets'),('Check for update','update'),('Backup & Restore','backup'),('About','about')):
        menu();tap(label);time.sleep(1);screenshot('page-'+slug)
        if slug=='subscriptions':
            tap('Add subscription');screenshot('page-subscription-edit');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
        elif slug=='routing':
            tap('Add routing rule');screenshot('page-routing-edit');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
        elif slug=='assets':
            tap('Add asset source');tap('Add URL');screenshot('page-asset-edit');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
        elif slug=='about':
            tap('Translators');screenshot('page-translators');adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(1)
        back_to_main()
    # These private editors normally need an imported raw config / Xray-TUN+routeOnly.
    # On this debug AVD ONLY, root shell opens the genuine existing native activities
    # without manufacturing user preferences or profile data. Not a non-root navigation test.
    result=adb('root');assert 'cannot' not in result.lower(),result
    adb('wait-for-device')
    for slug,component in (('custom','ui.server.ServerCustomConfigActivity'),('app-picker','ui.apppicker.AppPickerActivity')):
        adb('shell','am','start','-W','-n',PACKAGE+'/com.v2ray.ang.'+component)
        time.sleep(2);screenshot('page-'+slug);back_to_main()
    import json
    mapping={'screen-servers':'34-real-server-row','screen-settings':'05-settings','screen-logs':'18-real-log-slab','screen-aether-keys':'native-aether-keys'}
    tool_slugs=('hub','doctor','privacy-center','dns-lab','routing-studio','sync-center','speed-lab','dns-scanner','scanner')
    tool_labels=('Omni Hub','Omni Doctor','Omni Privacy & Backup','Omni DNS Lab','Omni Routing Studio','Omni Sync Center','Omni Speed & Ping Lab','DNS Scanner','Omni Scanner')
    for i,(slug,label)in enumerate(zip(tool_slugs,tool_labels),8):mapping['screen-'+slug]=f'{i:02d}-'+re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-')
    protocol_slugs=('vmess','vless','trojan','shadowsocks','socks','http','hysteria2','wireguard','naive','tuic','shadowquic','masterdns','dnstt','guts','policy-group','proxy-chain','aether')
    for i,(slug,label)in enumerate(zip(protocol_slugs,editor_labels),22):mapping['screen-'+slug]=f'{i:02d}-native-editor-'+re.sub(r'[^a-z0-9]+','-',label.lower()).strip('-')
    for slug in ('subscriptions','per-app','routing','assets','update','backup','about','subscription-edit','routing-edit','asset-edit','translators','custom','app-picker'):mapping['screen-'+slug]='page-'+slug
    assert len(mapping)==43,len(mapping)
    for screen,image in mapping.items():assert(OUT/(image+'.png')).is_file(),screen
    (OUT/'source-43-screen-map.json').write_text(json.dumps(mapping,indent=2))
    print('PASS: 43 genuine native screens captured, appearance persistence/large text/light-dark/log actions, original PBR renderer, real protocol forms and locally persisted SOCKS profile; no VPN started')
finally:
    (OUT/'ui.xml').write_text(ET.tostring(dump(),encoding='unicode'))
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-s','AndroidRuntime:E','OmniReferenceGL:V'))
