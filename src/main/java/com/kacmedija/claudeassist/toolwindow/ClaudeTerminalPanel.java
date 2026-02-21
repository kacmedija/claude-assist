package com.kacmedija.claudeassist.toolwindow;

import com.kacmedija.claudeassist.services.CommandExecutor;
import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase;
import com.intellij.terminal.JBTerminalWidget;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embeds an interactive terminal running the Claude CLI inside the tool window.
 * Supports WSL2 on Windows and native execution on Linux/Mac.
 */
public class ClaudeTerminalPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(ClaudeTerminalPanel.class);

    private final Project project;
    private JBTerminalWidget terminalWidget;
    private PtyProcess ptyProcess;
    private Disposable terminalLifecycle;
    private JPanel restartPanel;

    public ClaudeTerminalPanel(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        Disposer.register(toolWindow.getDisposable(), this);

        add(createToolbar(), BorderLayout.NORTH);
        startTerminal();
    }

    // ── Toolbar ────────────────────────────────────────────────────

    private JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new AnAction("New Session", "Restart Claude Assist session", AllIcons.Actions.Restart) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                restartSession();
            }
        });

        group.add(new AnAction("Kill", "Kill Claude CLI process", AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                killProcess();
            }
        });

        group.add(new AnAction("Settings", "Open Claude Assist settings", AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Claude Assist");
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("ClaudeTerminalToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    // ── Terminal Lifecycle ──────────────────────────────────────────

    private void startTerminal() {
        if (restartPanel != null) {
            remove(restartPanel);
            restartPanel = null;
        }

        try {
            terminalLifecycle = Disposer.newDisposable("ClaudeTerminalSession");
            Disposer.register(this, terminalLifecycle);

            ClaudeAssistSettings.State settings = getSettings();
            String projectPath = project.getBasePath();

            List<String> cliArgs = new ArrayList<>();
            if (!settings.model.isEmpty()) {
                cliArgs.add("--model");
                cliArgs.add(settings.model);
            }
            CommandExecutor.ProcessSpec spec = CommandExecutor.interactive(
                    settings, projectPath, cliArgs.toArray(new String[0]));

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");
            env.remove("CLAUDECODE");

            LOG.info("Starting Claude terminal: " + String.join(" ", spec.command()));

            PtyProcessBuilder ptyBuilder = new PtyProcessBuilder(spec.command().toArray(new String[0]))
                    .setEnvironment(env)
                    .setConsole(false);
            if (spec.workingDir() != null) {
                ptyBuilder.setDirectory(spec.workingDir().getAbsolutePath());
            }
            ptyProcess = ptyBuilder.start();

            TtyConnector connector = new ClaudeTtyConnector(ptyProcess);

            JBTerminalSystemSettingsProviderBase settingsProvider =
                    new JBTerminalSystemSettingsProviderBase();
            terminalWidget = new JBTerminalWidget(project, settingsProvider, terminalLifecycle);

            add(terminalWidget, BorderLayout.CENTER);

            terminalWidget.createTerminalSession(connector);
            terminalWidget.start();

            revalidate();
            repaint();

            startProcessMonitor();

        } catch (Exception e) {
            LOG.error("Failed to start Claude terminal", e);
            showError("Failed to start Claude Assist: " + e.getMessage());
        }
    }

    private void restartSession() {
        killProcess();

        if (terminalLifecycle != null) {
            Disposer.dispose(terminalLifecycle);
            terminalLifecycle = null;
        }
        if (terminalWidget != null) {
            remove(terminalWidget);
            terminalWidget = null;
        }

        startTerminal();
    }

    private void killProcess() {
        if (ptyProcess != null && ptyProcess.isAlive()) {
            ptyProcess.destroy();
        }
    }

    // ── Process Monitoring ─────────────────────────────────────────

    private void startProcessMonitor() {
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = ptyProcess.waitFor();
                SwingUtilities.invokeLater(() -> showRestartOption(exitCode));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "ClaudeTerminalMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void showRestartOption(int exitCode) {
        restartPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        restartPanel.add(new JLabel("Process exited (code " + exitCode + ")"));
        JButton restartBtn = new JButton("Restart");
        restartBtn.addActionListener(e -> restartSession());
        restartPanel.add(restartBtn);
        add(restartPanel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    // ── Error Display ──────────────────────────────────────────────

    private void showError(String message) {
        JPanel errorPanel = new JPanel(new BorderLayout(0, 8));

        JLabel errorLabel = new JLabel(message);
        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        errorPanel.add(errorLabel, BorderLayout.CENTER);

        JButton retryBtn = new JButton("Retry");
        retryBtn.addActionListener(e -> {
            remove(errorPanel);
            startTerminal();
        });
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnPanel.add(retryBtn);
        errorPanel.add(btnPanel, BorderLayout.SOUTH);

        add(errorPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Sends text to the terminal's PTY stdin, as if the user typed it.
     * Used by the Code Review panel to send fix prompts to Claude.
     */
    public void sendToTerminal(@NotNull String text) {
        if (ptyProcess == null || !ptyProcess.isAlive()) {
            LOG.warn("Cannot send to terminal: process is not alive");
            return;
        }
        try {
            ptyProcess.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            ptyProcess.getOutputStream().write('\n');
            ptyProcess.getOutputStream().flush();
        } catch (IOException e) {
            LOG.error("Failed to write to terminal PTY", e);
        }
    }

    /**
     * Returns true if the terminal PTY process is alive.
     */
    public boolean isTerminalAlive() {
        return ptyProcess != null && ptyProcess.isAlive();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private ClaudeAssistSettings.State getSettings() {
        ClaudeAssistSettings.State s = ClaudeAssistSettings.getInstance().getState();
        return s != null ? s : new ClaudeAssistSettings.State();
    }

    @Override
    public void dispose() {
        killProcess();
    }

    // ── TtyConnector ───────────────────────────────────────────────

    private static class ClaudeTtyConnector implements TtyConnector {
        private final PtyProcess process;
        private final InputStreamReader reader;

        ClaudeTtyConnector(@NotNull PtyProcess process) {
            this.process = process;
            this.reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        }

        @Override
        public int read(char @NotNull [] buf, int offset, int length) throws IOException {
            return reader.read(buf, offset, length);
        }

        @Override
        public void write(byte @NotNull [] bytes) throws IOException {
            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();
        }

        @Override
        public void write(@NotNull String string) throws IOException {
            write(string.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean isConnected() {
            return process.isAlive();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() {
            process.destroy();
        }

        @Override
        @NotNull
        public String getName() {
            return "Claude Assist";
        }

        @Override
        public void resize(@NotNull TermSize termWinSize) {
            process.setWinSize(new WinSize(termWinSize.getColumns(), termWinSize.getRows()));
        }

        @Override
        public boolean ready() throws IOException {
            return reader.ready();
        }
    }
}
