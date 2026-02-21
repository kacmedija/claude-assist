package com.kacmedija.claudeassist.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Project-level service that manages chat sessions and message history.
 */
@Service(Service.Level.PROJECT)
public final class SessionManager {

    private final Project project;
    private String activeSessionId;
    private final List<Message> messages = new ArrayList<>();

    public SessionManager(@NotNull Project project) {
        this.project = project;
        this.activeSessionId = generateSessionId();
    }

    public static SessionManager getInstance(@NotNull Project project) {
        return project.getService(SessionManager.class);
    }

    public @NotNull String getActiveSessionId() {
        return activeSessionId;
    }

    public void createNewSession() {
        activeSessionId = generateSessionId();
        messages.clear();
    }

    public void addMessage(@NotNull String role, @NotNull String content) {
        messages.add(new Message(role, content, System.currentTimeMillis()));
    }

    public List<Message> getMessages() {
        return List.copyOf(messages);
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record Message(String role, String content, long timestamp) {}
}
