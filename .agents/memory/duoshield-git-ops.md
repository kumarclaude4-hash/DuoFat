---
name: DuoShield git ops blocked
description: git write ops are blocked in the main agent sandbox; use git show for read-only extraction; GIT_PAT is the secret name
---

## Rule
`git add`, `git commit`, `git push`, `git fetch`, and `git reset --hard` all fail in the main agent — the sandbox blocks `.git/` writes. Read-only commands (`git --no-optional-locks status`, `log`, `show`, `diff`, `ls-remote`) still work.

**Why:** Replit sandbox treats `.git/` writes as destructive. Only project_tasks (isolated environments) can do destructive git ops.

**How to apply:**
- Finish all file edits, then tell the user to open the Replit Git panel, commit, and push.
- To apply remote changes without `git reset --hard`: use `git show origin/main:<path>` to read file content and overwrite locally via write tool.
- `git diff --name-status HEAD origin/main` shows what files differ between local and remote (read-only, always works).
- Never attempt `git fetch`, `git reset`, `git add`, `git commit`, or `git push` from the main agent.

## Remote
`https://github.com/kumarclaude4-hash/DuoShield-` (trailing dash — important for GitHub API calls)

## PAT
`GIT_PAT` secret holds the GitHub Personal Access Token (renamed from `GIT`/`GITREPLIT`). Available as `$GIT_PAT` at runtime.
