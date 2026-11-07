#!/usr/bin/env python3
"""在模拟器中验证最终混淆 Release 的 vCard 解析和原生库初始化。"""
import argparse
import os
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED = [
    'vcard21_quoted_printable',
    'vcard30_standard_fields_photo',
    'vcard40_unknown_reflective_types',
    'production_arm64_tensorflow_jni_initialization',
]


def adb(*args, timeout=45, check=True):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    output = result.stdout.decode(errors='replace').replace('\r', '')
    if check and result.returncode:
        raise RuntimeError(output + result.stderr.decode(errors='replace'))
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', required=True)
    parser.add_argument('--probe', required=True)
    parser.add_argument('--out', default='artifacts/runtime')
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    error = None
    try:
        # 只允许操作 CI 模拟器，避免把检测 APK 安装到真实设备。
        if not os.environ.get('ANDROID_SERIAL', '').startswith('emulator-'):
            raise RuntimeError('requires explicit emulator serial')
        if adb('shell', 'getprop', 'ro.boot.qemu').strip() != '1':
            raise RuntimeError('requires Android emulator')
        adb('install', '-r', args.apk, timeout=120)
        adb('install', '-r', args.probe, timeout=90)
        result = adb('shell', 'am', 'instrument', '-w', '-r',
                     'vip.mystery0.pixel.text.runtimeprobe/.RuntimeProbe', timeout=180)
        (out / 'instrumentation.txt').write_text(result, encoding='utf-8')
        if ('PIXELTEXT_RUNTIME_PASS' not in result or
                'INSTRUMENTATION_CODE: -1' not in result or
                'PIXELTEXT_RUNTIME_FAIL' in result):
            raise RuntimeError('release runtime probe failed: ' + result)
        for name in EXPECTED:
            if name not in result:
                raise RuntimeError('missing runtime check name=' + name)
            print('runtime check passed name=' + name, flush=True)
    except Exception as exc:
        error = str(exc)
        raise
    finally:
        suite = ET.Element('testsuite', name='pixeltext-release-runtime',
                           tests='1', failures=str(int(error is not None)))
        case = ET.SubElement(suite, 'testcase', name='minified_vcard_and_native',
                             time=f'{time.monotonic() - start:.3f}')
        if error is not None:
            ET.SubElement(case, 'failure', message=error).text = error
        ET.ElementTree(suite).write(out / 'junit.xml', encoding='utf-8',
                                    xml_declaration=True)


if __name__ == '__main__':
    main()
