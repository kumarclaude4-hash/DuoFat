---
name: DuoShield git ops blocked
description: git write ops are blocked in the main agent sandbox; use git show for read-only extraction; GIT_PAT is the secret name
---

## Rule
`git fetch` and `git reset --hard` are unreliable; avoid them. `git add`, `git commit`, and `git push` **do work** when PAT is embedded in the remote URL (see pattern below). Read-only commands (`git log`, `git show`, `git diff`, `git status`) always work.

**Why:** Earlier attempts failed because the PAT was not in the remote URL. Embedding it directly (`https://$GIT_PAT@github.com/…`) bypasses credential-store issues.

**How to apply:**
- To push: `git push "https://${GIT_PAT}@github.com/<owner>/<repo>.git" main:main`
- To apply remote changes without `git reset --hard`: use `git show origin/main:<path>` to read file content and overwrite locally via write tool.
- `git diff --name-status HEAD origin/main` shows what files differ between local and remote.

## Remote
`https://github.com/kumarclaude4-hash/DuoFat` (verified 2026-07-29 via `git remote -v` and successful `gitPush`)

## PAT
`GIT_PAT` secret holds the GitHub Personal Access Token (renamed from `GIT`/`GITREPLIT`). Available as `$GIT_PAT` at runtime.

## Preferred push method
Use the `gitPush({})` callback from the git-remote skill (CodeExecution) rather than raw `git push` — it handles credentials automatically and is confirmed working.
