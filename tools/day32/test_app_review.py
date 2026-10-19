import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import app_review
import review_pr


def metadata():
    return {'user': {'login': 'mau89'}, 'title': 'Review test',
            'head': {'repo': {'full_name': app_review.REPOSITORY}, 'sha': 'a' * 40},
            'base': {'repo': {'full_name': app_review.REPOSITORY}, 'sha': 'b' * 40}}


class AppReviewTests(unittest.TestCase):
    def test_forks_other_authors_and_invalid_shas_are_rejected(self):
        app_review.validate_pr(metadata())
        cases = []
        other = metadata()
        other['user']['login'] = 'stranger'
        cases.append(other)
        fork = metadata()
        fork['head']['repo']['full_name'] = 'mau89/fork'
        cases.append(fork)
        deleted = metadata()
        deleted['head']['repo'] = None
        cases.append(deleted)
        invalid = metadata()
        invalid['head']['sha'] = '--upload-pack=evil'
        cases.append(invalid)
        for case in cases:
            with self.assertRaises(review_pr.ReviewError):
                app_review.validate_pr(case)

    def test_result_keeps_coverage_and_sources_and_uses_only_pinned_snapshots(self):
        with tempfile.TemporaryDirectory() as folder:
            output, cache = Path(folder) / 'job', Path(folder) / 'cache.git'
            commands = []

            def command(args):
                commands.append(args)
                return json.dumps(metadata()).encode() if args[1] == 'api' else b''

            def reviewer(args, on_progress):
                self.assertEqual('ollama', args[args.index('--provider') + 1])
                self.assertEqual('a' * 40, args[args.index('--head') + 1])
                on_progress('Пакет 1')
                self.assertEqual('running', json.loads((output / 'job.json').read_text())['state'])
                review_pr.write_json(output / 'review.json', {'status': 'reviewed',
                    'review': {'summary': 'Проверено', 'findings': []},
                    'coverage': {'totalFiles': 2, 'reviewedFiles': 1, 'partial': True}})
                review_pr.write_json(output / 'context.json', {'files': [], 'sources': [
                    {'id': 'D1', 'kind': 'docs', 'file': 'README.md', 'startLine': 1, 'endLine': 3, 'text': 'large'}],
                    'manifest': [{'path': 'big.bin', 'excluded': 'binary'}]})
                (output / 'review.md').write_text('# Готово')
                return 0

            with patch.object(app_review, 'run', side_effect=command), patch.object(review_pr, 'main', side_effect=reviewer):
                self.assertEqual(0, app_review.execute(7, output, cache, 'job-id'))
            job = json.loads((output / 'job.json').read_text())
            self.assertEqual('completed', job['state'])
            self.assertEqual('a' * 40, job['headSha'])
            self.assertTrue(job['coverage']['partial'])
            self.assertNotIn('text', job['sources'][0])
            self.assertEqual('binary', job['excluded'][0]['reason'])
            fetch = next(args for args in commands if 'fetch' in args)
            self.assertEqual(['https://github.com/mau89/TalkLoop.git', 'b' * 40, 'a' * 40], fetch[-3:])
            self.assertFalse(any('checkout' in args for args in commands))

    def test_provider_error_remains_error_and_does_not_publish_findings(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)

            def reviewer(args, on_progress):
                review_pr.write_json(output / 'review.json', {'status': 'error', 'error': 'Ollama недоступна'})
                return 1

            with patch.object(app_review, 'run', return_value=json.dumps(metadata()).encode()), \
                    patch.object(review_pr, 'main', side_effect=reviewer):
                self.assertEqual(1, app_review.execute(7, output, output / 'cache', 'job'))
            job = json.loads((output / 'job.json').read_text())
            self.assertEqual('error', job['state'])
            self.assertEqual('Ollama недоступна', job['message'])
            self.assertNotIn('findings', job)

    def test_invalid_input_is_rejected_before_github_or_git(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(app_review, 'run') as run:
            self.assertEqual(1, app_review.execute(0, Path(folder), Path(folder) / 'cache', 'job'))
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
