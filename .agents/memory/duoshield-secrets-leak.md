---
name: DuoShield secrets leak lesson
description: Replit env vars (userenv.shared) are written to .replit which is tracked by git — use the Secrets tab instead.
---

# Replit Secrets vs Environment Variables

**Why:** During this project, two GitHub PATs were accidentally committed to git history because they were added via Replit's "Environment Variables" UI (stored in `.replit` under `[userenv.shared]`), not the Secrets tab.

## The Rule

| Where stored | What Replit does | In git? |
|---|---|---|
| **Secrets tab** (lock icon 🔒) | Encrypted, never written to `.replit` | ❌ Never |
| **Environment Variables** tab | Written to `.replit` as plain text | ✅ Always |

**Always use the Secrets tab for tokens, API keys, and credentials.**

## What happened
- User added `GitHub pat` and `Gitpat` as environment variables
- Replit wrote them to `.replit` lines 12-13 as plain text
- Auto-checkpoint committed `.replit`
- GitHub Push Protection blocked the push and detected the tokens
- Both tokens were revoked; `.replit` was cleaned manually

## Recovery steps (if this happens again)
1. Immediately revoke the exposed token on GitHub
2. Click the GitHub bypass URL from the push error to unblock
3. Generate a fresh token
4. Push using the token inline in the shell command (never saved to any file)
