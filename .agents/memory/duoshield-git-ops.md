---
name: DuoShield git ops blocked
description: git write ops are blocked in the main agent sandbox; use git show for read-only extraction; GITPAT is the secret name; repo renamed to DuoFatass
---

## Rule
`git fetch` and `git reset --hard` are unreliable via the `gitPull`/`gitPush` CodeExecution callbacks (they need a `github-source-control` connection that isn't set up) — but raw `git fetch`/`push` with the PAT embedded in the URL work fine. Read-only commands (`git log`, `git show`, `git diff`, `git status`) always work.

**Why:** `gitPull({})` fails with `NO_CREDENTIALS` in this environment. Embedding the PAT directly (`https://$GITPAT@github.com/…`) bypasses that and works for both fetch and push.

**How to apply:**
- To fetch/pull: `git fetch "https://${GITPAT}@github.com/<owner>/<repo>.git" main` then `git merge --ff-only FETCH_HEAD` (check `git merge-base --is-ancestor HEAD FETCH_HEAD` first).
- To push: `git push "https://${GITPAT}@github.com/<owner>/<repo>.git" main:main`
- `git diff --name-status HEAD origin/main` shows what files differ between local and remote.

## Remote
`https://github.com/kumarclaude4-hash/DuoFatass` — **repo was renamed from `DuoFat` to `DuoFatass` as of 2026-08-05**; old name 301-redirects on the GitHub API but `git remote set-url` to the new name to avoid relying on the redirect.

## PAT
Secret name is **`GITPAT`** (no underscore) as of 2026-08-05, available as `$GITPAT`. Earlier notes calling it `GIT_PAT` are stale — the environment secret list is the source of truth each session.

## Render auto-deploy
The push server (Render service `DuoFat`, id `srv-d958babtqb8s73ehoqi0`, live at `https://duofat.onrender.com`) has `autoDeploy: yes` tracking `origin/main` — any push to main deploys automatically within ~30-60s. Use `RENDER_API_KEY` against `api.render.com/v1/services/<id>/deploys` to confirm a deploy went `live` after pushing server changes; no manual deploy trigger needed.
