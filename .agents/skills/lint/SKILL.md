---
name: lint
description: Run the same SwiftLint baseline and strict swift-format checks as MiaoYan CI.
version: 1.1.0
allowed-tools:
  - Bash
---

# Lint Skill

Use this skill to check or fix code style in MiaoYan.

## SwiftLint

```bash
# Match the CI gate and suppress only checked-in legacy violations
swiftlint lint --strict --baseline .swiftlint-baseline.json

# Auto-fix safe violations
swiftlint --fix

# Check specific file
swiftlint lint --strict --baseline .swiftlint-baseline.json --path Controllers/ViewController.swift
```

Config: `.swiftlint.yml` at project root.

## swift-format

```bash
# Check formatting (no changes)
xcrun swift-format lint --recursive . --strict

# Apply formatting
xcrun swift-format format --recursive --in-place .
```

Config: `.swift-format` at project root (line length: 240).

## Run Both

```bash
swiftlint lint --strict --baseline .swiftlint-baseline.json && xcrun swift-format lint --recursive . --strict
```

## Safety Rules

1. **ALWAYS** run lint check before proposing a commit
2. Apply fixes only to files in the task scope and inspect the diff before committing
