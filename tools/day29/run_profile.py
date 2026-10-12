#!/usr/bin/env python3
"""Warm up once, measure the unchanged RAG runner and collect local memory samples."""
import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--profile', choices=['baseline', 'parameters', 'optimized'], required=True)
parser.add_argument('--model', default='qwen3.5:9b')
parser.add_argument('--repeats', type=int, default=3)
parser.add_argument('--questions', default='q04,q07,q10')
parser.add_argument('--label', required=True)
parser.add_argument('--dialogue', action='store_true')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
folder = root / 'day29-data'
folder.mkdir(exist_ok=True)
stop = folder / f'{args.label}.stop'
stop.unlink(missing_ok=True)
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(path, body=None):
    request = urllib.request.Request('http://127.0.0.1:11434/api/'+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    with http.open(request, timeout=300) as response:
        return json.load(response)
# Leave only the measured model resident. A different context causes a reload on warm-up.
for model in api('ps')['models']:
    if model['name'] != args.model:
        api('generate', {'model': model['name'], 'keep_alive': 0})
context = 32768 if args.profile == 'baseline' else 24576
options = {'num_ctx': context, 'num_predict': 16, 'temperature': 0}
warmup = api('chat', {'model': args.model, 'stream': False, 'think': False,
    'messages': [{'role':'user','content':'Ответь одним словом: готов.'}], 'options': options})
metadata = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'profile': args.profile, 'model': args.model, 'version': api('version'), 'warmup': warmup,
    'modelInfo': api('show', {'model':args.model}), 'loadedBefore': api('ps')}
(folder / f'{args.label}-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
monitor = subprocess.Popen([sys.executable, str(root/'tools/day29/monitor.py'),
    '--output', str(folder/f'{args.label}-resources.json'), '--stop-file', str(stop)])
command = ['./gradlew', ':cli:day29', '--offline', '--console=plain', '-q',
    '--args='+f'--profile {args.profile} --model {args.model} --repeats {args.repeats} --questions {args.questions} --output day29-data/{args.label}.json'+(' --dialogue' if args.dialogue else '')]
try:
    with (folder/f'{args.label}.log').open('w') as log:
        process = subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
        result = process.wait()
finally:
    stop.touch()
    monitor.wait(timeout=10)
metadata['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
metadata['exitCode'] = result
metadata['loadedAfter'] = api('ps')
(folder/f'{args.label}-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
# A nonzero benchmark result is retained and reported, never treated as a valid answer.
sys.exit(result)
