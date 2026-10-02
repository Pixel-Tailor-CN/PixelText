#!/usr/bin/env python3
"""真实 minified Release APK 的冷启动、系统弹窗与备份页面冒烟测试。只用全新模拟器合成数据。"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = 'vip.mystery0.pixel.text'
p = argparse.ArgumentParser()
p.add_argument('--apk', required=True)
p.add_argument('--probe', required=True)
p.add_argument('--out', default='artifacts/runtime')
a = p.parse_args()
out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
cases = []

def adb(*args, timeout=45, check=True):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    if check and result.returncode: raise RuntimeError(result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace'))
    return result.stdout.decode(errors='replace').replace('\r','')

def snapshot(name):
    adb('shell','uiautomator','dump','/sdcard/ci-window.xml')
    xml = adb('shell','cat','/sdcard/ci-window.xml')
    (out / f'{name}.xml').write_text(xml)
    png = subprocess.run(['adb','exec-out','screencap','-p'], capture_output=True, check=True, timeout=30).stdout
    (out / f'{name}.png').write_bytes(png)
    return ET.fromstring(xml)

def texts(tree):
    return [n.attrib.get('text','') or n.attrib.get('content-desc','') for n in tree.iter('node')]

def click_text(tree, text):
    for n in tree.iter('node'):
        if text in (n.attrib.get('text',''), n.attrib.get('content-desc','')):
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); time.sleep(2)
            return
    raise AssertionError(f'UI control missing: {text}; {texts(tree)}')

def healthy():
    assert adb('shell','pidof',PKG).strip(), 'app process missing'
    crash = adb('logcat','-b','crash','-d','-v','threadtime')
    (out/'crash-buffer.txt').write_text(crash)
    assert PKG not in crash, 'application crash in logcat crash buffer'
    activity = adb('shell','dumpsys','activity','activities')
    assert PKG in activity, 'app activity missing'
    logs = adb('logcat','-d','-v','threadtime')
    assert not re.search(r'ANR in '+re.escape(PKG),logs), 'application ANR'

def run_case(name, fn):
    start=time.monotonic()
    try:
        fn(); cases.append((name,time.monotonic()-start,None)); print('PASS',name,flush=True)
    except Exception as e:
        cases.append((name,time.monotonic()-start,str(e)))
        try: snapshot('failure-'+name)
        except Exception: pass
        raise

def launch():
    adb('shell','am','force-stop',PKG)
    output=adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
    assert 'Status: ok' in output, output
    time.sleep(8); healthy()
    tree = snapshot('cold-'+str(len(cases)))
    if '需要短信读取权限' in texts(tree):
        click_text(tree, '稍后')
        healthy()
        tree = snapshot('cold-home-'+str(len(cases)))
    assert '需要读取短信权限' in texts(tree), 'no-permission home screen not rendered'
    return tree

try:
    adb('install','-r',a.apk,timeout=120)
    adb('shell','pm','clear',PKG)
    adb('logcat','-c')
    def first():
        tree=launch()
        assert '需要读取短信权限' in texts(tree), 'no-permission home screen not rendered'
        perms=adb('shell','dumpsys','package',PKG)
        (out/'fresh-package.txt').write_text(perms)
        for permission in ['READ_SMS','READ_CONTACTS','READ_PHONE_STATE']:
            assert not re.search(r'android.permission.'+permission+r': granted=true',perms), permission+' unexpectedly granted'
    run_case('fresh_install_no_permissions',first)
    for i in range(1,6):
        run_case(f'repeated_cold_start_{i}',lambda: launch())
    def role_cancel():
        tree=snapshot('before-role'); click_text(tree,'More options')
        tree=snapshot('home-menu'); click_text(tree,'设置默认短信应用')
        tree=snapshot('system-sms-role')
        assert any('permissioncontroller' in n.attrib.get('package','') for n in tree.iter('node')), 'system role sheet missing'
        adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(3);healthy()
        tree=snapshot('role-cancel-return')
        assert '需要读取短信权限' in texts(tree), 'home screen missing after role cancellation'
        click_text(tree,'授予权限');tree=snapshot('sms-permission-rationale')
        click_text(tree,'稍后');healthy()
    run_case('default_sms_system_prompt_cancel_resume',role_cancel)
    def backup_ui():
        adb('shell','am','start','-W','-n',PKG+'/.MainActivity','--ez','extra_open_settings','true');time.sleep(3)
        tree=snapshot('settings')
        for i in range(12):
            if '备份与恢复' in texts(tree): break
            adb('shell','input','swipe','540','2050','540','650','350');time.sleep(1);tree=snapshot(f'settings-scroll-{i}')
        click_text(tree,'备份与恢复')
        tree=snapshot('backup-screen')
        assert '选择备份内容' in texts(tree), 'backup screen did not render'
        assert all(t in texts(tree) for t in ['设置与主题','关键词与白名单','短信及归档、放行状态']), 'backup enum choices missing'
        healthy();adb('shell','input','keyevent','KEYCODE_BACK');time.sleep(2);healthy()
    run_case('backup_screen_enum_choices_back',backup_ui)
    def runtime_probe():
        adb('install','-r',a.probe,timeout=90)
        result=adb('shell','am','instrument','-w','-r','vip.mystery0.pixel.text.runtimeprobe/.RuntimeProbe',timeout=180)
        (out/'instrumentation.txt').write_text(result)
        assert 'PIXELTEXT_RUNTIME_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result, result
        assert 'PIXELTEXT_RUNTIME_FAIL' not in result,result
        expected=['backup_adapter_all_enum_values_json_roundtrip','backup_plain_archive_export_inspect','backup_aes_archive_export_inspect','vcard21_quoted_printable','vcard30_standard_fields_photo','vcard40_unknown_reflective_types','production_arm64_tensorflow_jni_initialization']
        for name in expected: assert name in result, name+' missing'; cases.append((name,0,None))
    run_case('instrument_unmodified_release_dex',runtime_probe)
    run_case('post_probe_cold_start',lambda: launch())
finally:
    (out/'logcat.txt').write_text(adb('logcat','-d','-v','threadtime',check=False))
    suite=ET.Element('testsuite',name='pixeltext-release-runtime',tests=str(len(cases)),failures=str(sum(bool(c[2]) for c in cases)))
    for name,duration,error in cases:
        case=ET.SubElement(suite,'testcase',name=name,time=f'{duration:.3f}')
        if error: ET.SubElement(case,'failure',message=error).text=error
    ET.ElementTree(suite).write(out/'junit.xml',encoding='utf-8',xml_declaration=True)
