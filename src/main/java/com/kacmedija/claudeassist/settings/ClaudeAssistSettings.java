package com.kacmedija.claudeassist.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persisted settings for the Claude Assist plugin.
 * Stored in the global IDE configuration directory.
 */
@Service(Service.Level.APP)
@State(
    name = "ClaudeAssistSettings",
    storages = @Storage("claudeAssist.xml")
)
public final class ClaudeAssistSettings implements PersistentStateComponent<ClaudeAssistSettings.State> {

    public static class State {
        /** Path to the claude binary inside WSL (or native). */
        public String claudePath = "claude";

        /** WSL distribution name (empty = default distro). */
        public String wslDistro = "";

        /** Whether to use WSL2 to execute Claude CLI. Auto-detected on Windows. */
        public boolean useWsl = System.getProperty("os.name", "").toLowerCase().contains("win");

        /** Model override (empty = CLI default). */
        public String model = "";

        /** Maximum context files to include in prompts. */
        public int maxContextFiles = 10;

        /** Show diff preview before applying inline edits. */
        public boolean showDiffPreview = true;

        /** Automatically suggest fixes when saving a file with diagnostics. */
        public boolean autoFixOnSave = false;

        /** Include open editor tabs as implicit context. */
        public boolean includeOpenTabs = false;

        /** Custom system prompt prepended to all requests. */
        public String customSystemPrompt = "";
    }

    private State state = new State();

    public static ClaudeAssistSettings getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeAssistSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    // ── Convenience Getters ───────────────────────────────────────

    public String getClaudePath()       { return state.claudePath; }
    public String getWslDistro()        { return state.wslDistro; }
    public boolean isUseWsl()           { return state.useWsl; }
    public String getModel()            { return state.model; }
    public int getMaxContextFiles()     { return state.maxContextFiles; }
    public boolean isShowDiffPreview()  { return state.showDiffPreview; }
    public boolean isAutoFixOnSave()    { return state.autoFixOnSave; }
    public boolean isIncludeOpenTabs()  { return state.includeOpenTabs; }
    public String getCustomSystemPrompt() { return state.customSystemPrompt; }
}
