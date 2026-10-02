#!/usr/bin/env python3
"""仅全新模拟器/合成数据：MMS 回调后的 Room→Receiver→Worker、入口与草稿 UI。绝不发送。"""
import argparse
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = 'vip.mystery0.pixel.text'
p = argparse.ArgumentParser()
p.add_argument('--apk', required=True)
p.add_argument('--probe', required=True)
p.add_argument('--out', default='artifacts/mms-runtime')
a = p.parse_args()
out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
cases = []

def adb(*args, timeout=45, check=True):
    r = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    text = r.stdout.decode(errors='replace').replace('\r', '')
    if check and r.returncode:
        raise RuntimeError(text + r.stderr.decode(errors='replace'))
    return text

def shell(*args, **kwargs):
    return adb('shell', shlex.join(args), **kwargs)

def snapshot(name):
    shell('uiautomator', 'dump', '/sdcard/mms-ci-window.xml')
    xml = shell('cat', '/sdcard/mms-ci-window.xml')
    (out / f'{name}.xml').write_text(xml)
    png = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, check=True, timeout=30).stdout
    (out / f'{name}.png').write_bytes(png)
    return ET.fromstring(xml)

def texts(tree):
    return [n.attrib.get('text', '') or n.attrib.get('content-desc', '') for n in tree.iter('node')]

def click(tree, text):
    for n in tree.iter('node'):
        if text in (n.attrib.get('text', ''), n.attrib.get('content-desc', '')):
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.attrib['bounds']))
            shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2)); time.sleep(2)
            return
    raise AssertionError(f'UI control missing: {text}; {texts(tree)}')

def healthy():
    assert shell('pidof', PKG).strip(), 'app process missing'
    crash = adb('logcat', '-b', 'crash', '-d')
    assert PKG not in crash, crash

def launch(action, uri=None, body=None):
    args = ['am', 'start', '-W', '-n', PKG+'/.ComposeSmsActivity', '-a', action]
    if uri: args += ['-d', uri]
    if body is not None: args += ['--es', 'android.intent.extra.TEXT', body, '-t', 'text/plain']
    result = shell(*args); assert 'Status: ok' in result, result
    time.sleep(4); healthy()

def case(name, fn):
    start = time.monotonic()
    try:
        fn(); cases.append((name, time.monotonic()-start, None)); print('PASS', name, flush=True)
    except Exception as e:
        cases.append((name, time.monotonic()-start, str(e)))
        try: snapshot('failure-'+name)
        except Exception: pass
        raise

try:
    # 先证明目标是受控虚拟机；不对任何真实手机操作应用/权限。
    assert os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'), 'requires explicit emulator serial'
    assert shell('getprop', 'ro.boot.qemu').strip() == '1', 'requires Android emulator'
    assert 'arm64-v8a' in shell('getprop', 'ro.product.cpu.abilist'), 'missing ARM64 translation'
    adb('install', '-r', a.apk, timeout=120)
    shell('am', 'force-stop', PKG); shell('pm', 'clear', PKG)
    shell('pm', 'revoke', PKG, 'android.permission.SEND_SMS', check=False)
    shell('appops', 'set', PKG, 'SEND_SMS', 'deny')
    permissions = shell('dumpsys', 'package', PKG)
    assert not re.search(r'android.permission.SEND_SMS: granted=true', permissions), 'SEND_SMS unexpectedly granted'
    appops = shell('appops', 'get', PKG, 'SEND_SMS')
    assert 'deny' in appops, appops
    (out/'send-prevention-before.txt').write_text(appops)
    adb('logcat', '-c')
    adb('install', '-r', a.probe, timeout=90)

    def runtime():
        result = shell('am', 'instrument', '-w', '-r', 'vip.mystery0.pixel.text.mmsruntimeprobe/.MmsRuntimeProbe', timeout=180)
        (out/'instrumentation.txt').write_text(result)
        assert 'PIXELTEXT_MMS_RUNTIME_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result, result
        assert 'PIXELTEXT_MMS_RUNTIME_FAIL' not in result, result
        for name in ['send_sms_permission_and_appop_blocked', 'manifest_receiver_room_worker_platform_only_and_duplicate',
                     'unknown_late_success', 'restart_dispatch_fence_and_deleted_timeout',
                     'matching_mmsc_rejection_overrides_platform_ok', 'mutable_pendingintent_preserves_identity_and_response', 'partial_success_does_not_retry',
                     'same_attempt_distinct_late_success_kept', 'unassociated_callback_rejected_no_submission_states',
                     'malformed_external_parcel_safe_ui_and_durable_block']:
            assert name in result, name+' missing'
    case('synthetic_post_dispatch_receiver_room_worker', runtime)

    def restart_callback():
        shell('am', 'force-stop', PKG)
        result = shell('am', 'instrument', '-w', '-r', '-e', 'phase', 'after_restart',
                       'vip.mystery0.pixel.text.mmsruntimeprobe/.MmsRuntimeProbe', timeout=120)
        (out/'restart-instrumentation.txt').write_text(result)
        assert 'PIXELTEXT_MMS_RESTART_PASS' in result and 'INSTRUMENTATION_CODE: -1' in result, result
        assert 'unknown_late_callback_after_real_process_restart' in result, result
    case('late_callback_after_real_process_restart', restart_callback)

    def text_share():
        launch('android.intent.action.SEND', body='synthetic plain share')
        tree = snapshot('plain-share-recipient')
        assert '选择收件人' in texts(tree) and '编辑彩信 · 单人' not in texts(tree), texts(tree)
        shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)
    case('plain_text_share_remains_sms', text_share)

    def invalid_recipient():
        launch('android.intent.action.SENDTO', 'mmsto:12025550123,12025550124?body=synthetic')
        tree = snapshot('multiple-recipient-block')
        values = texts(tree)
        assert '编辑彩信 · 单人' in values, values
        assert any('仅支持单人' in t for t in values), values
        assert '12025550123,12025550124' not in values, 'raw recipient list was adopted'
        shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)
    case('multiple_recipient_requires_unique_confirmation', invalid_recipient)

    def text_transfer():
        launch('android.intent.action.SENDTO', 'smsto:+12025550123?body=synthetic%20transfer')
        tree = snapshot('sms-before-attachment')
        assert 'synthetic transfer' in texts(tree), texts(tree)
        click(tree, '编辑单人彩信')
        tree = snapshot('mms-transferred-body')
        assert '编辑彩信 · 单人' in texts(tree) and 'synthetic transfer' in texts(tree), texts(tree)
        # 两次配置变更后恢复原方向，避免只验证 ViewModel 内存而没经历 Activity 重建。
        shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        shell('settings', 'put', 'system', 'user_rotation', '1'); time.sleep(2)
        shell('settings', 'put', 'system', 'user_rotation', '0'); time.sleep(3)
        tree = snapshot('mms-after-rotation')
        assert 'synthetic transfer' in texts(tree), texts(tree)
        click(tree, '删除草稿')
        tree = snapshot('mms-discard-confirmation'); click(tree, '删除')
        tree = snapshot('sms-after-mms-discard')
        assert 'synthetic transfer' in texts(tree), 'discard cleared original SMS input'
        assert '编辑彩信 · 单人' not in texts(tree), 'composer did not close after discard'
        healthy()
    case('attachment_body_transfer_rotation_and_discard', text_transfer)
    final_ops = shell('appops', 'get', PKG, 'SEND_SMS')
    assert 'deny' in final_ops, final_ops
    assert not re.search(r'(?:^|\s)(?:time|rejectTime)=', final_ops), 'unexpected SMS app-op attempt'
    (out/'send-prevention-after.txt').write_text(final_ops)
finally:
    (out/'logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime', check=False))
    suite = ET.Element('testsuite', name='pixeltext-mms-runtime', tests=str(len(cases)), failures=str(sum(bool(c[2]) for c in cases)))
    for name, duration, error in cases:
        node = ET.SubElement(suite, 'testcase', name=name, time=f'{duration:.3f}')
        if error: ET.SubElement(node, 'failure', message=error).text = error
    ET.ElementTree(suite).write(out/'junit.xml', encoding='utf-8', xml_declaration=True)
