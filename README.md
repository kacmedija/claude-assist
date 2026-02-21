# Claude Assist — JetBrains Plugin

Deep AI integration that transforms Claude CLI into a full IDE-integrated assistant for all JetBrains IDEs.

![JetBrains 2024.1+](https://img.shields.io/badge/JetBrains-2024.1%2B-000000?logo=jetbrains)
![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![License GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)
![Version 0.4.1](https://img.shields.io/badge/Version-0.4.1-green)

## Features

- **Chat Panel** — Streaming AI chat with full project awareness
- **Inline Edit** — Select code, describe the change, see diff preview, apply
- **Fix Diagnostics** — One-click AI fix for errors and warnings
- **Code Review** — AI-powered review of files or selections
- **Generate Tests** — Auto-generate unit tests (detects Jest, PHPUnit, Pytest, JUnit, etc.)
- **Generate Docs** — PHPDoc / JSDoc / docblocks from code
- **Refactor** — AI-assisted refactoring with diff preview
- **Commit Messages** — Generate meaningful commit messages from staged changes
- **Context Files** — Right-click files to explicitly include them in AI context
- **WSL2 Support** — Seamless bridge for Windows users

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle Chat Panel |
| `Ctrl+Shift+E` | Inline Edit (selection) |
| `Ctrl+Shift+F` | Fix Diagnostics |
| `Ctrl+Shift+X` | Explain Selection |
| `Alt+Enter` | Fix with Claude (on error gutter) |

## Installation

### Prerequisites

1. **Claude Code CLI** installed: `npm install -g @anthropic-ai/claude-code`
2. On **Windows**: WSL2 with Claude Code installed inside the distro

### Build from Source

```bash
git clone https://github.com/kacmedija/claude-assist-jetbrains.git
cd claude-assist-jetbrains

# Build plugin ZIP
./gradlew buildPlugin

# Output: build/distributions/claude-assist-*.zip
```

Install in your IDE: **Settings → Plugins → ⚙ → Install from Disk** → select the ZIP.

## Configuration

**Settings → Tools → Claude Assist**

| Option | Description |
|---|---|
| Claude binary path | Path to `claude` binary (default: `claude`) |
| Use WSL2 | Auto-detected on Windows |
| WSL distribution | Leave empty for default distro |
| Model override | e.g. `claude-sonnet-4-5-20250929` |
| Show diff preview | Preview all changes before applying |
| Auto-fix on save | Suggest fixes when errors detected |
| Include open tabs | Send all open editor tabs as context |
| Custom system prompt | Prepended to every request |

## Supported IDEs

| IDE | Gradle flag |
|---|---|
| IntelliJ IDEA Community | `-PideType=IC` (default) |
| IntelliJ IDEA Ultimate | `-PideType=IU` |
| PHPStorm | `-PideType=PS` |
| WebStorm | `-PideType=WS` |
| PyCharm | `-PideType=PY` |
| GoLand | `-PideType=GO` |
| Rider | `-PideType=RD` |

## Development

```bash
# Run sandbox IDE with plugin loaded (IntelliJ Community, default)
./gradlew runIde

# Run sandbox with PHPStorm
./gradlew runIde -PideType=PS

# Hot-reload after code changes (run in a second terminal)
./dev.sh build
```

The sandbox IDE has `autoReloadPlugins` enabled — after running `./dev.sh build`, the plugin reloads automatically without restarting the IDE.

### Build Requirements

- Java 17+
- Gradle 8.5 (included via wrapper)

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Push to your branch and open a Pull Request

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
