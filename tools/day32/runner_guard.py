#!/usr/bin/env python3
"""Host-side job policy. Install outside the checkout; run before workflow steps."""
import json
import os
from pathlib import Path
import sys

REPOSITORY = 'mau89/TalkLoop'
OWNER = 'mau89'
WORKFLOW = REPOSITORY + '/.github/workflows/ai-review.yml@'


def allowed(environment, event):
    if environment.get('GITHUB_REPOSITORY') != REPOSITORY:
        return False
    if environment.get('GITHUB_ACTOR') != OWNER:
        return False
    if not environment.get('GITHUB_WORKFLOW_REF', '').startswith(WORKFLOW):
        return False
    name = environment.get('GITHUB_EVENT_NAME')
    if name == 'workflow_dispatch':
        return event.get('sender', {}).get('login') == OWNER
    if name != 'pull_request':
        return False
    pr = event.get('pull_request', {})
    return (
        pr.get('user', {}).get('login') == OWNER
        and pr.get('head', {}).get('repo', {}).get('full_name') == REPOSITORY
        and pr.get('base', {}).get('repo', {}).get('full_name') == REPOSITORY
        and event.get('sender', {}).get('login') == OWNER
    )


def main():
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        accepted = allowed(os.environ, event)
    except (KeyError, OSError, ValueError, TypeError, AttributeError):
        accepted = False
    if not accepted:
        print('Job blocked by host policy: only the owner’s same-repository review PRs or manual review runs are allowed.', file=sys.stderr)
        return 1
    print('Host policy accepted this review job.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
