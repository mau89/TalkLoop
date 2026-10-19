import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import runner_guard


class RunnerGuardTests(unittest.TestCase):
    def setUp(self):
        self.environment = {
            'GITHUB_REPOSITORY': 'mau89/TalkLoop', 'GITHUB_ACTOR': 'mau89',
            'GITHUB_WORKFLOW_REF': 'mau89/TalkLoop/.github/workflows/ai-review.yml@refs/pull/1/merge',
            'GITHUB_EVENT_NAME': 'pull_request',
        }
        self.event = {'sender': {'login': 'mau89'}, 'pull_request': {
            'user': {'login': 'mau89'}, 'head': {'repo': {'full_name': 'mau89/TalkLoop'}},
            'base': {'repo': {'full_name': 'mau89/TalkLoop'}},
        }}

    def test_accepts_owner_pr_in_same_repository(self):
        self.assertTrue(runner_guard.allowed(self.environment, self.event))

    def test_rejects_fork_even_when_owner_reruns_it(self):
        self.event['pull_request']['head']['repo']['full_name'] = 'attacker/TalkLoop'
        self.assertFalse(runner_guard.allowed(self.environment, self.event))

    def test_rejects_other_actors_repositories_and_workflows(self):
        for key, value in [('GITHUB_ACTOR', 'attacker'), ('GITHUB_REPOSITORY', 'attacker/TalkLoop'),
                           ('GITHUB_WORKFLOW_REF', 'mau89/TalkLoop/.github/workflows/other.yml@main'),
                           ('GITHUB_EVENT_NAME', 'pull_request_target'), ('GITHUB_EVENT_NAME', 'workflow_run')]:
            with self.subTest(key=key, value=value):
                environment = {**self.environment, key: value}
                self.assertFalse(runner_guard.allowed(environment, self.event))

    def test_rejects_other_pr_authors_and_event_senders(self):
        for path in ('user', 'sender'):
            event = copy.deepcopy(self.event)
            if path == 'user':
                event['pull_request']['user']['login'] = 'attacker'
            else:
                event['sender']['login'] = 'attacker'
            self.assertFalse(runner_guard.allowed(self.environment, event))

    def test_manual_review_is_owner_only(self):
        self.environment['GITHUB_EVENT_NAME'] = 'workflow_dispatch'
        self.assertTrue(runner_guard.allowed(self.environment, {'sender': {'login': 'mau89'}}))
        self.assertFalse(runner_guard.allowed(self.environment, {'sender': {'login': 'attacker'}}))

    def test_invalid_or_missing_event_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'event.json'
            for content in ('{', 'null', '{}', json.dumps({'pull_request': None})):
                path.write_text(content)
                with patch.dict(os.environ, {**self.environment, 'GITHUB_EVENT_PATH': str(path)}, clear=True):
                    self.assertEqual(1, runner_guard.main())


if __name__ == '__main__':
    unittest.main()
