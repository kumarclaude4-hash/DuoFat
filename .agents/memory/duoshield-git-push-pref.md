---
name: DuoShield git push preference
description: User wants every code change pushed to origin/main using the GIT PAT secret; author must be the user's identity.
---

# Git Push Preference

**Rule:** After completing any code change, always push to `origin/main` using the `gitPush({})` callback from the git-remote skill.

**Why:** User explicitly requested this — they want all agent-made changes reflected in their GitHub repo automatically.

**How to apply:**
- Secret name: `GIT` (PAT stored in Replit Secrets)
- Commit author is already set in the repo's git config — do not override it
- Call `gitPush({})` after every task that modifies files
- Do NOT set up or fix the Push Server workflow — user does not require it on Replit
