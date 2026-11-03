#!/usr/bin/env bash
# One-time: make git use the versioned hooks in scripts/hooks.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
chmod +x scripts/hooks/*
git config core.hooksPath scripts/hooks
echo "Hooks installed (core.hooksPath=scripts/hooks)."
