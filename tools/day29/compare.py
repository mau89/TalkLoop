#!/usr/bin/env python3
"""Run profiles sequentially: competing inference requests would spoil timing."""
import pathlib
import argparse
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[2]
stages = [
    ('baseline', 'qwen3.5:9b', 'baseline', '3', 'q04,q07,q10'),
    ('optimized', 'qwen3.5:9b', 'optimized-q4', '3', 'q04,q07,q10'),
    ('optimized', 'qwen3.5:9b-q8_0', 'optimized-q8', '3', 'q04,q07,q10'),
    ('baseline', 'qwen3.5:9b', 'holdout-baseline', '1', 'q01,q03,q08'),
    ('optimized', 'qwen3.5:9b', 'holdout-optimized-q4', '1', 'q01,q03,q08'),
    ('optimized', 'qwen3.5:9b-q8_0', 'holdout-optimized-q8', '1', 'q01,q03,q08'),
    ('optimized', 'qwen3.5:9b-q8_0', 'dialogue-optimized-q8', '1', 'q04,q07,q10'),
]
failed = []
parser = argparse.ArgumentParser()
parser.add_argument('--start-at', choices=[stage[2] for stage in stages], default='baseline')
args = parser.parse_args()
stages = stages[next(i for i, stage in enumerate(stages) if stage[2] == args.start_at):]
for profile, model, label, repeats, questions in stages:
    print('\nSTART '+label, flush=True)
    result = subprocess.run([sys.executable, str(root/'tools/day29/run_profile.py'),
        '--profile', profile, '--model', model, '--label', label,
        '--repeats', repeats, '--questions', questions] + (['--dialogue'] if label.startswith('dialogue-') else []), cwd=root)
    if result.returncode:
        failed.append(label)
    print('FINISH '+label+' exit='+str(result.returncode), flush=True)
print('Completed all stages; stages containing errors: '+str(failed), flush=True)
sys.exit(bool(failed))
