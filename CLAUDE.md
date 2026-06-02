# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Daily AI coding challenge from https://mobiledeveloper.tech/ai_advent_8. Each business day brings a new task. Each task is solved in its own folder with the simplest possible solution.

## Structure

Each day lives in `dayN/` (e.g. `day1/`, `day2/`). The solution is a single self-contained Kotlin script. Screen recordings of the solution may be included alongside.

## Running a solution

```bash
export ANTHROPIC_API_KEY=<your-key>
kotlin dayN/main.main.kts        # if the script uses @file:DependsOn
kotlin dayN/main.kts             # if the script has no external dependencies
```

Requires the `kotlin` CLI (comes with the Kotlin compiler). No build system, no Gradle.

## Script file extension

- Use **`main.kts`** when the script has no external dependencies (Kotlin stdlib + `java.net.http` only).
- Use **`main.main.kts`** when the script uses `@file:DependsOn(...)` to pull in a Maven artifact. The `.main.kts` extension activates the `kotlin-main-kts` scripting host, which is the only host that supports `@file:DependsOn`. Running a `.kts` file with `@file:DependsOn` produces an `unresolved reference 'DependsOn'` compile error.

## Conventions

- **One file per day**: the script is the only source file. No helper modules, no shared code between days.
- **Anthropic API**: scripts talk to `https://api.anthropic.com/v1/messages` directly via `HttpClient`. The API key is read from `ANTHROPIC_API_KEY` env var and the script exits early if it is missing.
- **Simplest solution wins**: resist adding abstractions, libraries, or error handling beyond what the task requires.
- **Folder naming**: `dayN/` (lowercase, no padding).
