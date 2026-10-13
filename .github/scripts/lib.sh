#!/usr/bin/env bash
# Shared helpers for .github/scripts/*.sh — SOURCE this file, do not execute it.

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  echo "lib.sh holds helpers for the sibling scripts — source it, don't run it." >&2
  exit 1
fi

# Diagnoses the token's shape from its prefix, printing the KIND only — never the value and
# never the length. Length was the old check ("a real PAT should be ~52"), and it is wrong for
# every token GitHub issues today: classic PATs are 40 (or 93 in the v2 roll-out), fine-grained
# PATs are ~82-93, App tokens are ~50-60.
#
#   ghp_…      classic PAT             github_pat_…   fine-grained PAT
#   gho_…      OAuth token             ghu_/ghs_/ghr_ App tokens
#   40 hex     legacy pre-2021 PAT
github_token_kind() {
  local t="$1"
  case "$t" in
    ghp_*)        echo "classic PAT (ghp_)" ;;
    github_pat_*) echo "fine-grained PAT (github_pat_)" ;;
    gho_*)        echo "OAuth user token (gho_)" ;;
    ghu_*)        echo "user-to-server token (ghu_)" ;;
    ghs_*)        echo "server-to-server token (ghs_)" ;;
    ghr_*)        echo "refresh token (ghr_)" ;;
    *)
      if printf '%s' "$t" | grep -Eq '^[0-9a-f]{40}$'; then
        echo "legacy 40-hex PAT"
      else
        echo "unrecognised token format"
      fi
      ;;
  esac
}

# Pushes the current HEAD to <branch> on origin using GIT_PUSH_PAT.
#
# The token travels through GIT_ASKPASS rather than being spliced into the remote URL: argv is
# world-readable via /proc, and a failed `git push` otherwise echoes the credential verbatim in
# `fatal: unable to access '<url>'`. Failures report the token kind plus the scope to check —
# actionable, and still no secret in the log.
git_push_with_pat() {
  local branch="$1" base_url askpass kind rc=0

  if [ -z "${GIT_PUSH_PAT:-}" ]; then
    echo "GIT_PUSH_PAT is not set — cannot push to '$branch'." >&2
    return 1
  fi

  kind=$(github_token_kind "$GIT_PUSH_PAT")
  echo "Pushing HEAD to '$branch' with ${kind}."

  base_url=$(git remote get-url origin | sed 's#https://[^@]*@#https://#')

  askpass=$(mktemp)
  chmod 700 "$askpass"
  cat >"$askpass" <<'EOF'
#!/bin/sh
case "$1" in
  [Uu]sername*) printf '%s\n' 'x-access-token' ;;
  *)             printf '%s\n' "${GIT_PUSH_PAT:?}" ;;
esac
EOF

  # -c http.extraheader= clears the checkout's persisted AUTHORIZATION header — sending it
  # alongside askpass credentials is rejected by the server.
  GIT_PUSH_PAT="$GIT_PUSH_PAT" GIT_ASKPASS="$askpass" GIT_TERMINAL_PROMPT=0 \
    git -c http.extraheader= -c credential.helper= push "$base_url" "HEAD:$branch" || rc=$?
  rm -f "$askpass"

  if [ "$rc" -ne 0 ]; then
    echo "Push to '$branch' failed using ${kind}." >&2
    echo "Check GIT_PUSH_PAT: it needs 'contents: write' on this repository and must not be expired." >&2
  fi
  return "$rc"
}
