package com.kacmedija.claudeassist.services;

import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Central platform-aware command builder for Claude CLI invocations.
 * Encapsulates WSL, native Windows, and Unix command construction
 * so callers only specify CLI arguments and working directory.
 */
public final class CommandExecutor {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private CommandExecutor() {}

    // ── Platform Detection ────────────────────────────────────────

    public static boolean isNativeWindows() {
        return IS_WINDOWS;
    }

    // ── Path Conversion ───────────────────────────────────────────

    /**
     * Converts a Windows path to a WSL-compatible path.
     * <ul>
     *   <li>Drive-letter paths: C:/Users/dev  to  /mnt/c/Users/dev</li>
     *   <li>WSL UNC paths: //wsl.localhost/Distro/home/dev  to  /home/dev</li>
     *   <li>WSL UNC paths: //wsl$/Distro/home/dev  to  /home/dev</li>
     * </ul>
     * Paths that are already POSIX-style are returned as-is.
     */
    public static String toWslPath(@NotNull String windowsPath) {
        String normalized = windowsPath.replace('\\', '/');

        if (normalized.startsWith("//wsl.localhost/") || normalized.startsWith("//wsl$/")) {
            int distroStart = normalized.indexOf('/', 2);
            if (distroStart >= 0) {
                int pathStart = normalized.indexOf('/', distroStart + 1);
                if (pathStart >= 0) {
                    return normalized.substring(pathStart);
                }
            }
        }

        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            char drive = Character.toLowerCase(normalized.charAt(0));
            String rest = normalized.substring(2);
            return "/mnt/" + drive + rest;
        }

        return normalized;
    }

    /**
     * Converts a WSL path back to Windows format.
     * Example: /mnt/c/Users/dev -> C:\Users\dev
     */
    public static String toWindowsPath(@NotNull String wslPath) {
        if (wslPath.startsWith("/mnt/") && wslPath.length() > 5) {
            char drive = Character.toUpperCase(wslPath.charAt(5));
            String rest = wslPath.substring(6).replace('/', '\\');
            return drive + ":" + rest;
        }
        return wslPath;
    }

    // ── Process Spec ──────────────────────────────────────────────

    /**
     * Describes a fully-built command ready for execution.
     * Callers can further configure the ProcessBuilder (env vars, error redirect, etc.).
     */
    public record ProcessSpec(
            @NotNull List<String> command,
            @Nullable File workingDir,
            @Nullable Path stdinFile
    ) {
        /**
         * Creates a pre-configured ProcessBuilder from this spec.
         */
        public ProcessBuilder createProcessBuilder() {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) pb.directory(workingDir);
            if (stdinFile != null) pb.redirectInput(stdinFile.toFile());
            return pb;
        }
    }

    // ── Factory Methods ───────────────────────────────────────────

    /**
     * Claude CLI with prompt piped via stdin ({@code --print} mode).
     * <p>
     * Callers: StreamJsonService, ClaudeAssistService, CodeReviewService, ReviewDetailPanel
     *
     * @param settings    plugin settings (claudePath, useWsl, wslDistro, model)
     * @param promptFile  temp file containing the prompt text
     * @param workDir     working directory (Windows or WSL path), may be null
     * @param cliArgs     additional CLI flags, e.g. "--print", "--verbose", "--output-format", "stream-json"
     */
    public static ProcessSpec claude(
            @NotNull ClaudeAssistSettings.State settings,
            @NotNull Path promptFile,
            @Nullable String workDir,
            String... cliArgs
    ) {
        if (settings.useWsl) {
            return claudeWsl(settings, promptFile, workDir, cliArgs);
        } else if (IS_WINDOWS) {
            return claudeNativeWindows(settings, promptFile, workDir, cliArgs);
        } else {
            return claudeUnix(settings, promptFile, workDir, cliArgs);
        }
    }

    /**
     * Claude CLI interactive (terminal, no --print).
     * <p>
     * Caller: ClaudeTerminalPanel
     *
     * @param settings  plugin settings
     * @param workDir   working directory, may be null
     * @param cliArgs   additional CLI flags, e.g. "--model", "sonnet"
     */
    public static ProcessSpec interactive(
            @NotNull ClaudeAssistSettings.State settings,
            @Nullable String workDir,
            String... cliArgs
    ) {
        List<String> command = new ArrayList<>();

        if (settings.useWsl) {
            command.add("wsl.exe");
            addWslDistro(command, settings);
            command.add("--");
            command.add("bash");
            command.add("-lc");

            String wslDir = workDir != null ? toWslPath(workDir) : "~";
            String bashCmd = "cd " + shellQuote(wslDir) + " && "
                    + joinForShell(settings.claudePath, cliArgs);
            command.add(bashCmd);
            return new ProcessSpec(command, null, null);

        } else if (IS_WINDOWS) {
            // Windows needs cmd.exe wrapping for PTY (winpty/conpty) compatibility —
            // bare binary names may not resolve via CreateProcess.
            command.add("cmd.exe");
            command.add("/c");
            StringBuilder cmdLine = new StringBuilder(settings.claudePath);
            for (String arg : cliArgs) {
                cmdLine.append(' ').append(arg);
            }
            command.add(cmdLine.toString());
            File dir = workDir != null ? new File(workDir) : null;
            return new ProcessSpec(command, dir, null);

        } else {
            command.add("bash");
            command.add("-lc");

            String cdPart = workDir != null ? "cd " + shellQuote(workDir) + " && " : "";
            String bashCmd = cdPart + joinForShell(settings.claudePath, cliArgs);
            command.add(bashCmd);
            return new ProcessSpec(command, null, null);
        }
    }

    /**
     * Direct binary invocation (no stdin, no shell wrapping on native platforms).
     * <p>
     * Caller: ClaudeAssistService.checkHealth()
     *
     * @param settings  plugin settings
     * @param args      binary + arguments, e.g. settings.claudePath, "--version"
     */
    public static ProcessSpec direct(
            @NotNull ClaudeAssistSettings.State settings,
            String... args
    ) {
        List<String> command = new ArrayList<>();

        if (settings.useWsl) {
            command.add("wsl.exe");
            addWslDistro(command, settings);
            command.add("--");
            command.add("bash");
            command.add("-lc");
            command.add(joinForShell(args));
            return new ProcessSpec(command, null, null);

        } else if (IS_WINDOWS) {
            for (String arg : args) {
                command.add(arg);
            }
            return new ProcessSpec(command, null, null);

        } else {
            command.add("bash");
            command.add("-lc");
            command.add(joinForShell(args));
            return new ProcessSpec(command, null, null);
        }
    }

    /**
     * Arbitrary shell command (git, etc.).
     * <p>
     * Caller: CodeReviewService.runGitCommand()
     *
     * @param settings      plugin settings
     * @param shellCommand  the shell command string, e.g. "git diff --name-only"
     * @param workDir       working directory, may be null
     */
    public static ProcessSpec shell(
            @NotNull ClaudeAssistSettings.State settings,
            @NotNull String shellCommand,
            @Nullable String workDir
    ) {
        List<String> command = new ArrayList<>();

        if (settings.useWsl) {
            command.add("wsl.exe");
            addWslDistro(command, settings);
            command.add("--");
            command.add("bash");
            command.add("-c");
            String wslDir = workDir != null ? toWslPath(workDir) : null;
            String cdPart = wslDir != null ? "cd " + shellQuote(wslDir) + " && " : "";
            command.add(cdPart + shellCommand);
            return new ProcessSpec(command, null, null);

        } else if (IS_WINDOWS) {
            command.add("cmd.exe");
            command.add("/c");
            command.add(shellCommand.replace("2>/dev/null", "2>NUL"));
            File dir = workDir != null ? new File(workDir) : null;
            return new ProcessSpec(command, dir, null);

        } else {
            command.add("bash");
            command.add("-c");
            String cdPart = workDir != null ? "cd " + shellQuote(workDir) + " && " : "";
            command.add(cdPart + shellCommand);
            return new ProcessSpec(command, null, null);
        }
    }

    // ── Internal: claude() platform variants ──────────────────────

    private static ProcessSpec claudeWsl(
            ClaudeAssistSettings.State settings,
            Path promptFile,
            @Nullable String workDir,
            String[] cliArgs
    ) {
        List<String> command = new ArrayList<>();
        command.add("wsl.exe");
        addWslDistro(command, settings);
        command.add("--");
        command.add("bash");
        command.add("-lc");

        String wslTemp = toWslPath(promptFile.toString());
        String wslDir = workDir != null ? toWslPath(workDir) : null;
        String cdPart = wslDir != null ? "cd " + shellQuote(wslDir) + " && " : "";

        String bashCmd = cdPart + "cat " + shellQuote(wslTemp) + " | "
                + joinForShell(settings.claudePath, cliArgs);
        command.add(bashCmd);

        return new ProcessSpec(command, null, null);
    }

    private static ProcessSpec claudeNativeWindows(
            ClaudeAssistSettings.State settings,
            Path promptFile,
            @Nullable String workDir,
            String[] cliArgs
    ) {
        List<String> command = new ArrayList<>();
        command.add(settings.claudePath);
        for (String arg : cliArgs) {
            command.add(arg);
        }
        if (!settings.model.isEmpty()) {
            command.add("--model");
            command.add(settings.model);
        }

        File dir = workDir != null ? new File(workDir) : null;
        return new ProcessSpec(command, dir, promptFile);
    }

    private static ProcessSpec claudeUnix(
            ClaudeAssistSettings.State settings,
            Path promptFile,
            @Nullable String workDir,
            String[] cliArgs
    ) {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add("-lc");

        String cdPart = workDir != null ? "cd " + shellQuote(workDir) + " && " : "";
        String bashCmd = cdPart + "cat " + shellQuote(promptFile.toString()) + " | "
                + joinForShell(settings.claudePath, cliArgs);
        command.add(bashCmd);

        return new ProcessSpec(command, null, null);
    }

    // ── Internal Helpers ──────────────────────────────────────────

    private static void addWslDistro(List<String> command, ClaudeAssistSettings.State settings) {
        if (!settings.wslDistro.isEmpty()) {
            command.add("-d");
            command.add(settings.wslDistro);
        }
    }

    /**
     * Wraps a value in single quotes for POSIX shell usage.
     * Internal single quotes are escaped as {@code '\''}.
     */
    private static String shellQuote(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * Joins a binary path and arguments into a single shell command string.
     * Arguments containing spaces or special characters are single-quoted.
     */
    private static String joinForShell(String binary, String[] args) {
        StringBuilder sb = new StringBuilder(binary);
        for (String arg : args) {
            sb.append(' ');
            if (needsQuoting(arg)) {
                sb.append(shellQuote(arg));
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    /**
     * Joins raw arguments into a single shell command string.
     */
    private static String joinForShell(String[] args) {
        if (args.length == 0) return "";
        StringBuilder sb = new StringBuilder(args[0]);
        for (int i = 1; i < args.length; i++) {
            sb.append(' ');
            if (needsQuoting(args[i])) {
                sb.append(shellQuote(args[i]));
            } else {
                sb.append(args[i]);
            }
        }
        return sb.toString();
    }

    /**
     * Returns true if the argument contains characters that require quoting in a POSIX shell.
     */
    private static boolean needsQuoting(String arg) {
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == ' ' || c == '\'' || c == '"' || c == '\\' || c == '$'
                    || c == '`' || c == '!' || c == '(' || c == ')' || c == '{'
                    || c == '}' || c == '[' || c == ']' || c == '|' || c == '&'
                    || c == ';' || c == '<' || c == '>' || c == '*' || c == '?'
                    || c == '#' || c == '~') {

                return true;
            }
        }
        return false;
    }
}
