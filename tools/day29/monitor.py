#!/usr/bin/env python3
"""Sample Ollama allocation and process RSS once/second; never call a cloud API."""
import argparse
import datetime
import json
import pathlib
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
parser.add_argument('--stop-file', required=True)
args = parser.parse_args()
output = pathlib.Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
stop = pathlib.Path(args.stop_file)
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
samples = []
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
try:
    while not stop.exists():
        sample = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        try:
            with http.open('http://127.0.0.1:11434/api/ps', timeout=3) as response:
                sample['models'] = json.load(response).get('models', [])
            rows = subprocess.check_output(['ps', '-axo', 'pid=,rss=,comm='], text=True)
            sample['ollamaProcesses'] = [
                {'pid': int(parts[0]), 'rssKiB': int(parts[1]), 'command': parts[2]}
                for row in rows.splitlines()
                if len(parts := row.strip().split(None, 2)) == 3
                and ('/ollama' in parts[2].lower() or 'ollama runner' in parts[2].lower())
            ]
        except Exception as error:
            sample['error'] = str(error)
        samples.append(sample)
        time.sleep(1)
finally:
    report = {'startedAt': started, 'endedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'sampleIntervalSeconds': 1, 'samples': samples,
              'methodology': 'Sampled /api/ps size_vram is allocated model memory, not peak system memory. RSS sums Ollama processes only, KiB from ps; macOS unified memory overlaps, do not add RSS to size_vram. Sampling may miss brief peaks. Other processes and energy are not measured.'}
    temporary = output.with_suffix('.tmp')
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    temporary.replace(output)
