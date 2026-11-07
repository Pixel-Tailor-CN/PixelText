#!/usr/bin/env python3
"""从最终 Release mapping 生成旁路 instrumentation 配置，不更改应用 DEX 或 keep 规则。"""
import json
import re
import sys
from pathlib import Path

mapping, dest = map(Path, sys.argv[1:3])
classes = {}
current = None
for line in mapping.read_text().splitlines():
    if not line.startswith((' ', '#')) and ' -> ' in line:
        original, renamed = line[:-1].split(' -> ')
        current = classes[original] = {'class': renamed, 'members': {}}
    elif current is not None and line.startswith(' ') and ' -> ' in line:
        original, renamed = line.strip().split(' -> ')
        original = re.sub(r'^\d+:\d+:', '', original)
        original = re.sub(r':\d+(?::\d+)?$', '', original)
        current['members'].setdefault(original, set()).add(renamed)

def cls(name):
    return classes[name]['class']

def member(name, signature):
    values = classes[name]['members'][signature]
    if len(values) != 1:
        raise ValueError(f'ambiguous member {name}.{signature}: {values}')
    return next(iter(values))

root = 'vip.mystery0.pixel.text.'
config = {}
for key, name in {
    'parser': root + 'domain.parser.mms.MmsContactParser',
    'contact': root + 'domain.model.mms.MmsContactModel',
    'value': root + 'domain.model.mms.MmsContactValue',
    'unit': 'kotlin.Unit',
    'emptyContext': 'kotlin.coroutines.EmptyCoroutineContext',
    'tflite': 'org.tensorflow.lite.TensorFlowLite',
}.items():
    config[key] = cls(name)
config['parse'] = member(root + 'domain.parser.mms.MmsContactParser', 'java.util.List parse(java.lang.String,kotlin.jvm.functions.Function0)')
config['tfliteInit'] = member('org.tensorflow.lite.TensorFlowLite', 'void init()')
config['unitInstance'] = member('kotlin.Unit', 'kotlin.Unit INSTANCE')
config['emptyContextInstance'] = member('kotlin.coroutines.EmptyCoroutineContext', 'kotlin.coroutines.EmptyCoroutineContext INSTANCE')
for name, typ in {'name':'java.lang.String', 'phones':'java.util.List', 'emails':'java.util.List', 'addresses':'java.util.List', 'photoBytes':'byte[]', 'importWarning':'java.lang.String'}.items():
    config['contact_' + name] = member(root + 'domain.model.mms.MmsContactModel', typ + ' ' + name)
for name in ['value','label']:
    config['value_' + name] = member(root + 'domain.model.mms.MmsContactValue', 'java.lang.String ' + name)
dest.mkdir(parents=True, exist_ok=True)
(dest / 'mapping.json').write_text(json.dumps(config, indent=2))
print(json.dumps(config, indent=2))
