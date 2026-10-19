#!/usr/bin/env python3
"""Read a trusted GitHub PR into a separate bare cache and run the existing reviewer.

Used only by the local Ktor server. GitHub credentials stay on the Mac. The PR's
Python scripts, checkout, hooks and build commands are never executed.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

import review_pr

REPOSITORY = 'mau89/TalkLoop'
MODEL = 'qwen3.5:9b-q8_0'


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False) + '\n', encoding='utf-8')
    temporary.replace(path)


def run(command, timeout=120):
    try:
        return subprocess.run(command, check=True, capture_output=True, timeout=timeout).stdout
    except FileNotFoundError:
        raise review_pr.ReviewError('На Mac не найдены Git или GitHub CLI (gh).') from None
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        # Do not expose gh stderr, credential helpers or token-bearing error bodies to the app.
        raise review_pr.ReviewError('Не удалось получить PR из GitHub. Проверьте номер PR, сеть и вход gh auth login на Mac.') from None


def validate_pr(data):
    if (data.get('user', {}).get('login') != 'mau89'
            or (data.get('head', {}).get('repo') or {}).get('full_name') != REPOSITORY
            or (data.get('base', {}).get('repo') or {}).get('full_name') != REPOSITORY):
        raise review_pr.ReviewError('На личном Mac разрешены только PR владельца mau89 из mau89/TalkLoop, без fork.')
    for side in ('base', 'head'):
        if not re.fullmatch(r'[0-9a-f]{40}', data[side].get('sha', '')):
            raise review_pr.ReviewError('GitHub вернул неверную версию PR.')


def execute(pr_number, output, cache, job_id):
    output.mkdir(parents=True, exist_ok=True)
    job = {'id': job_id, 'prNumber': pr_number, 'state': 'running',
           'message': 'Получаем PR из GitHub…', 'model': MODEL}

    def update(**fields):
        job.update(fields)
        atomic_json(output / 'job.json', job)

    update()
    try:
        if not 1 <= pr_number <= 999_999_999:
            raise review_pr.ReviewError('Неверный номер PR.')
        gh = shutil.which('gh') or '/opt/homebrew/bin/gh'
        data = json.loads(run([gh, 'api', f'repos/{REPOSITORY}/pulls/{pr_number}']))
        validate_pr(data)
        base, head = data['base']['sha'], data['head']['sha']
        update(title=data['title'], url=f'https://github.com/{REPOSITORY}/pull/{pr_number}',
               baseSha=base, headSha=head, message='Загружаем версии кода и документации…')
        if not cache.exists():
            run(['git', 'init', '--bare', str(cache)])
        run(['git', '-C', str(cache), 'fetch', '--no-tags', '--no-write-fetch-head',
             f'https://github.com/{REPOSITORY}.git', base, head])

        def progress(message):
            update(message=message)

        result = review_pr.main(['--repo', str(cache), '--base', base, '--head', head,
                                 '--provider', 'ollama', '--model', MODEL, '--output', str(output)],
                                on_progress=progress)
        report = json.loads((output / 'review.json').read_text(encoding='utf-8'))
        if result != 0:
            raise review_pr.ReviewError(report.get('error', 'Модель не смогла завершить ревью.'))
        context = json.loads((output / 'context.json').read_text(encoding='utf-8'))
        review = report.get('review') or {}
        files = {item['path']: item for item in context['files']}
        findings = [{**finding, 'displayFile': files[finding['file']]['oldPath']
                     if finding['side'] == 'old' else finding['file']}
                    for finding in review.get('findings', [])]
        messages = {'reviewed': 'Ревью готово.', 'no_changes': 'В PR нет изменений.',
                    'not_reviewed': 'Нет поддерживаемых файлов; анализ модели не выполнялся.'}
        update(state='completed', message=messages[report['status']], resultStatus=report['status'],
               summary=review.get('summary', ''), findings=findings,
               coverage=report['coverage'],
               sources=[{key: source[key] for key in ('id', 'kind', 'file', 'startLine', 'endLine')}
                        for source in context['sources']],
               excluded=[{'path': item['path'], 'reason': item['excluded']}
                         for item in context['manifest'] if item['excluded']],
               markdown=(output / 'review.md').read_text(encoding='utf-8'))
        return 0
    except (review_pr.ReviewError, OSError, ValueError, KeyError, TypeError) as error:
        message = str(error) if isinstance(error, review_pr.ReviewError) else 'Не удалось прочитать данные ревью. Подробности на Mac.'
        update(state='error', message=message)
        return 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pr', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--job-id', required=True)
    args = parser.parse_args()
    raise SystemExit(execute(args.pr, args.output.resolve(), args.cache.resolve(), args.job_id))
