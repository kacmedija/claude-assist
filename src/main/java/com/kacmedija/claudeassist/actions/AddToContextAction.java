package com.kacmedija.claudeassist.actions;

import com.kacmedija.claudeassist.services.ContextManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class AddToContextAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || file.isDirectory()) return;

        ContextManager ctx = ContextManager.getInstance(project);
        if (ctx.hasFile(file)) {
            ctx.removeFile(file);
        } else {
            ctx.addFile(file);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || file.isDirectory()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setEnabledAndVisible(true);

        ContextManager ctx = ContextManager.getInstance(project);
        if (ctx.hasFile(file)) {
            e.getPresentation().setText("Remove from Claude Context");
        } else {
            e.getPresentation().setText("Add to Claude Context");
        }
    }
}
