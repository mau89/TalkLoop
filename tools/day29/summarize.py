#!/usr/bin/env python3
"""Compute descriptive statistics without replacing manual quality review."""
import hashlib
import json
import pathlib
import statistics

root = pathlib.Path(__file__).resolve().parents[2]
folder = root / 'day29-data'
labels = ['pilot-baseline', 'pilot-parameters', 'baseline', 'optimized-q4', 'optimized-q8',
          'holdout-baseline', 'holdout-optimized-q4', 'holdout-optimized-q8', 'dialogue-optimized-q8']
summary = {'stages': {}, 'methodology': 'Medians are descriptive, small fixed-order sample, not a statistical claim. Quality must be manually reviewed; automatic grades are unchanged. RSS and model allocation overlap on unified memory.'}
normalize = lambda text: ' '.join(text.split())
for label in labels:
    path = folder / (label+'.json')
    if not path.exists():
        continue
    report = json.loads(path.read_text())
    runs = report['runs']
    completed = [run for run in runs if run.get('answer') and not run.get('error')]
    quotes = 0
    invalid_quotes = []
    for run in completed:
        answer = run['answer']
        sources = {source['chunkId']:source for source in answer['sources']}
        for claim in (answer.get('evidence') or {}).get('claims', []):
            for quote in claim['citations']:
                quotes += 1
                source = sources.get(quote['chunkId'])
                if not source or normalize(quote['quote']) not in normalize(source['text']):
                    invalid_quotes.append([run['questionId'], run['repeat'], quote['chunkId']])
    def median(records):
        return statistics.median([r['durationMs'] for r in records]) if records else None
    stage = {'attempts': len(runs), 'completed': len(completed),
             'errors': sum(bool(r.get('error')) for r in runs),
             'answered': sum((r['answer'].get('evidence') or {}).get('status') == 'answered' for r in completed),
             'medianAllMs': median(runs), 'medianCompletedMs': median(completed),
             'publishedQuotesChecked': quotes, 'invalidPublishedQuotes': invalid_quotes,
             'reportSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
             'byQuestion': {qid: {'medianAllMs':median([r for r in runs if r['questionId']==qid]),
                 'medianCompletedMs':median([r for r in completed if r['questionId']==qid])}
                 for qid in dict.fromkeys(r['questionId'] for r in runs)}}
    resource_path = folder / (label+'-resources.json')
    if resource_path.exists():
        samples = json.loads(resource_path.read_text())['samples']
        memory = [model['size_vram'] for sample in samples for model in sample.get('models', [])]
        rss = [sum(p['rssKiB'] for p in sample.get('ollamaProcesses', [])) for sample in samples if 'ollamaProcesses' in sample]
        stage['resources'] = {'samples':len(samples), 'errors':sum('error' in s for s in samples),
            'maxModelAllocatedBytes':max(memory, default=0),
            'medianModelAllocatedBytes':statistics.median(memory) if memory else None,
            'maxOllamaRssKiB':max(rss, default=0)}
    summary['stages'][label] = stage
(folder/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
for label, stage in summary['stages'].items():
    print(label, stage)
