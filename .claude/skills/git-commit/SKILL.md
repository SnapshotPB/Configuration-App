---
name: git-commit
description: Safely commit staged or unstaged changes to git
---

# Git Commit Skill

Guide Claude through safely committing staged or unstaged changes to git.

## Steps

Run these commands in parallel to understand the current state:
- `git status` — see what files are modified or untracked
- `git diff HEAD` — see all changes (staged and unstaged)
- `git log --oneline -5` — understand the recent commit message style

### 1. Analyze the changes

Review the diff output and determine:
- What changed (files, scope)
- Why it changed (infer from context, or ask the user if unclear)
- Which files are relevant to stage (avoid accidentally staging `.env`, credentials, or unrelated files)

### 2. Draft a commit message

Follow the style from recent commits. Guidelines:
- First line: imperative mood, under 72 characters, no trailing period
- Focus on *why*, not *what* — the diff already shows what changed
- If there's more to say, add a blank line then a body paragraph
- Do not reference internal issue numbers or PR links unless the user asks

### 3. Stage files

Prefer staging specific files by name over `git add -A` or `git add .` to avoid accidentally including sensitive files or large binaries.

If there are untracked files the user probably doesn't want committed (build outputs, `.env`, IDE files), skip them and mention it.

### 4. Create the commit

Always use a HEREDOC to avoid shell quoting problems:

```bash
git commit -m "$(cat <<'EOF'
Short summary line
Optional body paragraph explaining the why.
EOF
)"
```

### 5. Confirm success

Run `git status` after the commit to verify it was created. Report the commit hash and message to the user.

## Safety rules (never skip)

- Do not pointlessly co auther the commit message, you're a tool, not a person, cut the shit
- Never use `--no-verify` or `--no-gpg-sign`
- Never amend a commit unless the user explicitly asks
- Never force-push
- If a pre-commit hook fails, fix the underlying issue and create a **new** commit — do not amend
- Only commit when the user has explicitly asked for a commit
