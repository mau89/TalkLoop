import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import file_agent as agent


def action(op, path='', query='', content='', start=1):
    return {'action': op, 'path': path, 'query': query, 'content': content, 'start_line': start, 'message': op}


class FileAgentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        for path, text in [('README.md', '# Product\nWidget API\n'),
                           ('core/Widget.kt', 'class Widget\n'),
                           ('app/Main.kt', 'val component = Widget()\n'),
                           ('docs/rules.md', '# Rules\nNever change secrets\n')]:
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        self.path = 'docs/generated/day34/widget-usage.md'
        self.actions = [action('search', query='Widget'), action('read_file', 'core/Widget.kt'),
                        action('read_file', 'app/Main.kt'), action('read_file', 'README.md'),
                        action('write_file', self.path, content='# Widget\nUsed in core/Widget.kt:1 and app/Main.kt:1.\n'),
                        action('diff'), action('finish')]

    def tearDown(self):
        self.temporary.cleanup()

    def prepare(self, output='run', actions=None):
        sequence = iter(actions or self.actions)
        folder = self.root / output
        result = agent.run(self.root, 'Find Widget usage and create a report', folder, 'test', provider=lambda _: next(sequence))
        self.assertEqual(0, result, (folder / 'job.json').read_text())
        return folder, json.loads((folder / 'plan.json').read_text())

    def test_real_multi_file_tools_prepare_diff_then_apply_once_without_touching_sources(self):
        original = (self.root / 'app/Main.kt').read_bytes()
        folder, plan = self.prepare()
        self.assertFalse((self.root / self.path).exists())
        self.assertEqual(3, len(plan['reads']))
        self.assertIn('+++ b/' + self.path, (folder / 'changes.diff').read_text())
        self.assertTrue(agent.apply_plan(self.root, plan))
        content = (self.root / self.path).read_text()
        self.assertIn('core/Widget.kt', content)
        self.assertIn('app/Main.kt', content)
        self.assertIn('Фактические совпадения поиска', content)
        self.assertIn('app/Main.kt:1', content)
        self.assertFalse(agent.apply_plan(self.root, plan))
        self.assertEqual(content, (self.root / self.path).read_text())
        self.assertEqual(original, (self.root / 'app/Main.kt').read_bytes())

    def test_existing_document_is_read_and_updated_with_real_diff(self):
        file = self.root / self.path
        file.parent.mkdir(parents=True)
        file.write_text('# Old document\n')
        actions = [*self.actions[:4], action('read_file', self.path), *self.actions[4:]]
        folder, plan = self.prepare(actions=actions)
        self.assertIn('-# Old document', (folder / 'changes.diff').read_text())
        self.assertTrue(agent.apply_plan(self.root, plan))
        self.assertTrue(file.read_text().startswith('# Widget'))
        trace = json.loads((folder / 'trace.json').read_text())
        repeated = self.root / 'repeat-updated-doc'
        self.assertEqual(0, agent.run(self.root, trace['goal'], repeated, 'repeat', replay=trace))
        self.assertEqual('no_changes', json.loads((repeated / 'job.json').read_text())['state'])
        file.write_text('User changed generated document\n')
        self.assertEqual(1, agent.run(self.root, trace['goal'], self.root / 'foreign-edit', 'foreign', replay=trace))

    def test_source_change_and_concurrent_target_edit_are_never_overwritten(self):
        _, plan = self.prepare()
        target = self.root / self.path
        target.parent.mkdir(parents=True)
        target.write_text('User note\n')
        with self.assertRaises(agent.TaskError): agent.apply_plan(self.root, plan)
        self.assertEqual('User note\n', target.read_text())
        target.unlink()
        (self.root / 'core/Widget.kt').write_text('class UpdatedWidget\n')
        with self.assertRaises(agent.TaskError): agent.apply_plan(self.root, plan)
        self.assertFalse(target.exists())

    def test_no_arbitrary_paths_secrets_code_writes_or_symlink_escape(self):
        workspace = agent.Workspace(self.root)
        for path in ('../outside.md', '/tmp/file.md', '.git/config', 'secrets.properties', 'core/Widget.kt', 'docs/generated/day34/nested/file.md'):
            with self.assertRaises(agent.TaskError): workspace.path(path, output=True)
        external = self.root / 'external'
        external.mkdir()
        parent = self.root / 'docs/generated'
        parent.symlink_to(external, target_is_directory=True)
        with self.assertRaises(agent.TaskError): workspace.path(self.path, output=True)
        (self.root / 'core/alias.kt').symlink_to(self.root / 'README.md')
        subprocess.run(['git', '-C', str(self.root), 'add', 'core/alias.kt'], check=True)
        with self.assertRaises(agent.TaskError): agent.Workspace(self.root).text('core/alias.kt')

    def test_three_distinct_nonempty_reads_are_required_before_writing(self):
        workspace = agent.Workspace(self.root)
        with self.assertRaises(agent.TaskError): workspace.execute(self.actions[4])
        workspace.execute(action('read_file', 'README.md'))
        workspace.execute(action('read_file', 'README.md'))
        with self.assertRaises(agent.TaskError): workspace.execute(action('read_file', 'app/Main.kt', start=500))
        self.assertEqual(1, len(workspace.reads))
        with self.assertRaises(agent.TaskError): workspace.execute(action('finish'))

    def test_search_is_literal_and_reports_large_skipped_files(self):
        (self.root / 'core/Big.kt').write_text('Widget' * 20_000)
        subprocess.run(['git', '-C', str(self.root), 'add', 'core/Big.kt'], check=True)
        workspace = agent.Workspace(self.root)
        result = workspace.execute(action('search', query='Widget()'))
        self.assertEqual(['app/Main.kt'], [hit['path'] for hit in result['matches']])
        self.assertIn('core/Big.kt', result['skippedFiles'])

    def test_replay_is_offline_and_reproduces_diff_but_rejects_changed_read(self):
        folder, _ = self.prepare()
        trace = json.loads((folder / 'trace.json').read_text())
        repeated = self.root / 'replayed'
        def forbidden(_): self.fail('Replay must never call a model')
        self.assertEqual(0, agent.run(self.root, trace['goal'], repeated, 'replay', provider=forbidden, replay=trace))
        self.assertEqual((folder / 'changes.diff').read_text(), (repeated / 'changes.diff').read_text())
        (self.root / 'core/Widget.kt').write_text('changed\n')
        self.assertEqual(1, agent.run(self.root, trace['goal'], self.root / 'stale', 'stale', provider=forbidden, replay=trace))

    def test_replay_preserves_recorded_recoverable_tool_errors(self):
        folder, _ = self.prepare(actions=[action('write_file', self.path, content='# Too early'), *self.actions])
        trace = json.loads((folder / 'trace.json').read_text())
        self.assertIn('error', trace['steps'][0]['result'])
        self.assertEqual(0, agent.run(self.root, trace['goal'], self.root / 'repeat-errors', 'replay', replay=trace))

    def test_tampered_plan_cannot_write_outside_managed_docs(self):
        _, plan = self.prepare()
        changed = copy.deepcopy(plan)
        changed['changes'][0]['path'] = 'README.md'
        with self.assertRaises(agent.TaskError): agent.apply_plan(self.root, changed)

    def test_new_matching_source_invalidates_plan_even_when_read_files_are_unchanged(self):
        _, plan = self.prepare()
        (self.root / 'app/NewUsage.kt').write_text('val newComponent = Widget()\n')
        subprocess.run(['git', '-C', str(self.root), 'add', 'app/NewUsage.kt'], check=True)
        with self.assertRaises(agent.TaskError): agent.apply_plan(self.root, plan)
        self.assertFalse((self.root / self.path).exists())
        changed = copy.deepcopy(plan)
        changed['changes'][0]['content'] = 'tampered'
        with self.assertRaises(agent.TaskError): agent.apply_plan(self.root, changed)


if __name__ == '__main__':
    unittest.main()
