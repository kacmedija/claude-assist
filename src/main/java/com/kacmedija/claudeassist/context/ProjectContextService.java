package com.kacmedija.claudeassist.context;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level service that caches the detected project context with a 1-minute TTL.
 */
@Service(Service.Level.PROJECT)
public final class ProjectContextService {

    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    private final Project project;
    private volatile ProjectContext.DetectedContext cachedContext;
    private volatile long cacheTimestamp;

    public ProjectContextService(@NotNull Project project) {
        this.project = project;
    }

    public static ProjectContextService getInstance(@NotNull Project project) {
        return project.getService(ProjectContextService.class);
    }

    /**
     * Returns the detected project context, using a cached value if fresh enough.
     */
    @Nullable
    public ProjectContext.DetectedContext getContext() {
        long now = System.currentTimeMillis();
        if (cachedContext != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedContext;
        }

        ProjectContext.DetectedContext context = ProjectContextDetector.detect(project);
        cachedContext = context;
        cacheTimestamp = now;
        return context;
    }

    /**
     * Invalidates the cached context, forcing a fresh detection on next access.
     */
    public void invalidateCache() {
        cachedContext = null;
        cacheTimestamp = 0;
    }
}
