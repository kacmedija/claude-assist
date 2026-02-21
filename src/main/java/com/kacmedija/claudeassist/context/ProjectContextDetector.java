package com.kacmedija.claudeassist.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-ecosystem project context detector.
 * Reads various configuration files to detect languages, frameworks, and test frameworks.
 */
public final class ProjectContextDetector {

    private static final Logger LOG = Logger.getInstance(ProjectContextDetector.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+)");

    private ProjectContextDetector() {}

    /**
     * Detects project context from IDE SDK and configuration files.
     */
    @NotNull
    public static ProjectContext.DetectedContext detect(@NotNull Project project) {
        List<ProjectContext.LanguageInfo> languages = new ArrayList<>();
        List<ProjectContext.FrameworkInfo> frameworks = new ArrayList<>();
        List<ProjectContext.TestFramework> testFrameworks = new ArrayList<>();

        String basePath = project.getBasePath();

        // 1. IDE SDK
        detectFromSdk(project, languages);

        if (basePath != null) {
            // 2. composer.json (PHP)
            detectFromComposerJson(basePath, languages, frameworks, testFrameworks);

            // 3. package.json (Node/TypeScript)
            detectFromPackageJson(basePath, languages, frameworks, testFrameworks);

            // 4. pyproject.toml (Python)
            detectFromPyprojectToml(basePath, languages, frameworks, testFrameworks);

            // 5. build.gradle / build.gradle.kts (Java/Kotlin)
            detectFromGradle(basePath, languages, frameworks, testFrameworks);

            // 6. pom.xml (Java/Maven)
            detectFromPomXml(basePath, languages, frameworks, testFrameworks);

            // 7. go.mod (Go)
            detectFromGoMod(basePath, languages, frameworks, testFrameworks);

            // 8. Cargo.toml (Rust)
            detectFromCargoToml(basePath, languages, frameworks, testFrameworks);

            // 9. Gemfile (Ruby)
            detectFromGemfile(basePath, languages, frameworks, testFrameworks);
        }

        return new ProjectContext.DetectedContext(languages, frameworks, testFrameworks);
    }

    // ── IDE SDK ──────────────────────────────────────────────────────

    private static void detectFromSdk(
            @NotNull Project project,
            @NotNull List<ProjectContext.LanguageInfo> languages
    ) {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) {
                String sdkType = sdk.getSdkType().getName();
                String version = sdk.getVersionString();
                String extractedVersion = version != null ? extractVersion(version) : null;

                String langName = switch (sdkType) {
                    case "JavaSDK" -> "Java";
                    case "PythonSDK", "Python SDK" -> "Python";
                    case "GoSDK", "Go SDK" -> "Go";
                    case "RubySDK", "Ruby SDK" -> "Ruby";
                    default -> null;
                };

                if (langName != null) {
                    languages.add(new ProjectContext.LanguageInfo(langName, extractedVersion, "IDE SDK"));
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to detect SDK", e);
        }
    }

    // ── composer.json (PHP) ──────────────────────────────────────────

    private static void detectFromComposerJson(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "composer.json");
        if (!Files.exists(file)) return;

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject require = getJsonObject(root, "require");
            JsonObject requireDev = getJsonObject(root, "require-dev");

            // PHP version
            if (require != null) {
                String phpConstraint = getJsonString(require, "php");
                if (phpConstraint != null) {
                    String version = extractVersion(phpConstraint);
                    if (!hasLanguage(languages, "PHP")) {
                        languages.add(new ProjectContext.LanguageInfo("PHP", version, "composer.json"));
                    }
                }

                // Frameworks
                String laravelVer = getJsonString(require, "laravel/framework");
                if (laravelVer != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo("Laravel", laravelVer, "composer.json"));
                }
                String symfonyVer = getJsonString(require, "symfony/framework-bundle");
                if (symfonyVer != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo("Symfony", symfonyVer, "composer.json"));
                }
            }

            // Test frameworks
            JsonObject allDeps = mergeDeps(require, requireDev);
            if (getJsonString(allDeps, "phpunit/phpunit") != null) {
                testFrameworks.add(new ProjectContext.TestFramework(
                        "PHPUnit", getJsonString(allDeps, "phpunit/phpunit"), "composer.json"));
            } else if (getJsonString(allDeps, "pestphp/pest") != null) {
                testFrameworks.add(new ProjectContext.TestFramework(
                        "Pest", getJsonString(allDeps, "pestphp/pest"), "composer.json"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse composer.json", e);
        }
    }

    // ── package.json (Node/TypeScript) ──────────────────────────────

    private static void detectFromPackageJson(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "package.json");
        if (!Files.exists(file)) return;

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject deps = getJsonObject(root, "dependencies");
            JsonObject devDeps = getJsonObject(root, "devDependencies");
            JsonObject allDeps = mergeDeps(deps, devDeps);

            // TypeScript detection
            if (getJsonString(allDeps, "typescript") != null) {
                if (!hasLanguage(languages, "TypeScript")) {
                    languages.add(new ProjectContext.LanguageInfo(
                            "TypeScript", getJsonString(allDeps, "typescript"), "package.json"));
                }
            } else if (!hasLanguage(languages, "JavaScript")) {
                // Node version from engines
                JsonObject engines = getJsonObject(root, "engines");
                String nodeVer = engines != null ? getJsonString(engines, "node") : null;
                languages.add(new ProjectContext.LanguageInfo(
                        "JavaScript", nodeVer != null ? extractVersion(nodeVer) : null, "package.json"));
            }

            // Frameworks
            if (deps != null) {
                if (getJsonString(deps, "react") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "React", getJsonString(deps, "react"), "package.json"));
                }
                if (getJsonString(deps, "vue") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Vue", getJsonString(deps, "vue"), "package.json"));
                }
                if (getJsonString(deps, "@angular/core") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Angular", getJsonString(deps, "@angular/core"), "package.json"));
                }
                if (getJsonString(deps, "next") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Next.js", getJsonString(deps, "next"), "package.json"));
                }
                if (getJsonString(deps, "express") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Express", getJsonString(deps, "express"), "package.json"));
                }
                if (getJsonString(deps, "nuxt") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Nuxt", getJsonString(deps, "nuxt"), "package.json"));
                }
                if (getJsonString(deps, "svelte") != null) {
                    frameworks.add(new ProjectContext.FrameworkInfo(
                            "Svelte", getJsonString(deps, "svelte"), "package.json"));
                }
            }

            // Test frameworks
            if (getJsonString(allDeps, "jest") != null) {
                testFrameworks.add(new ProjectContext.TestFramework(
                        "Jest", getJsonString(allDeps, "jest"), "package.json"));
            }
            if (getJsonString(allDeps, "vitest") != null) {
                testFrameworks.add(new ProjectContext.TestFramework(
                        "Vitest", getJsonString(allDeps, "vitest"), "package.json"));
            }
            if (getJsonString(allDeps, "mocha") != null) {
                testFrameworks.add(new ProjectContext.TestFramework(
                        "Mocha", getJsonString(allDeps, "mocha"), "package.json"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse package.json", e);
        }
    }

    // ── pyproject.toml (Python) ─────────────────────────────────────

    private static void detectFromPyprojectToml(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "pyproject.toml");
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Python version from requires-python
            if (!hasLanguage(languages, "Python")) {
                Matcher m = Pattern.compile("requires-python\\s*=\\s*\"([^\"]+)\"").matcher(content);
                if (m.find()) {
                    String version = extractVersion(m.group(1));
                    languages.add(new ProjectContext.LanguageInfo("Python", version, "pyproject.toml"));
                }
            }

            // Frameworks
            if (content.contains("django") || content.contains("Django")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Django", null, "pyproject.toml"));
            }
            if (content.contains("flask") || content.contains("Flask")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Flask", null, "pyproject.toml"));
            }
            if (content.contains("fastapi") || content.contains("FastAPI")) {
                frameworks.add(new ProjectContext.FrameworkInfo("FastAPI", null, "pyproject.toml"));
            }

            // Test frameworks
            if (content.contains("pytest") || content.contains("[tool.pytest")) {
                testFrameworks.add(new ProjectContext.TestFramework("pytest", null, "pyproject.toml"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse pyproject.toml", e);
        }
    }

    // ── Gradle (Java/Kotlin) ────────────────────────────────────────

    private static void detectFromGradle(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path gradleKts = Path.of(basePath, "build.gradle.kts");
        Path gradle = Path.of(basePath, "build.gradle");
        Path file = Files.exists(gradleKts) ? gradleKts : (Files.exists(gradle) ? gradle : null);
        if (file == null) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Java version
            Matcher javaVer = Pattern.compile("(?:sourceCompatibility|jvmTarget|JavaVersion\\.VERSION_)(\\d+)").matcher(content);
            if (javaVer.find() && !hasLanguage(languages, "Java")) {
                languages.add(new ProjectContext.LanguageInfo("Java", javaVer.group(1), file.getFileName().toString()));
            }

            // Kotlin detection
            if (content.contains("kotlin(") || content.contains("org.jetbrains.kotlin")) {
                Matcher kotlinVer = Pattern.compile("kotlin.*?version\\s*=?\\s*\"(\\d+\\.\\d+)").matcher(content);
                String version = kotlinVer.find() ? kotlinVer.group(1) : null;
                if (!hasLanguage(languages, "Kotlin")) {
                    languages.add(new ProjectContext.LanguageInfo("Kotlin", version, file.getFileName().toString()));
                }
            }

            // Spring Boot
            if (content.contains("spring-boot") || content.contains("org.springframework.boot")) {
                Matcher springVer = Pattern.compile("spring-boot.*?(\\d+\\.\\d+\\.\\d+)").matcher(content);
                String version = springVer.find() ? springVer.group(1) : null;
                frameworks.add(new ProjectContext.FrameworkInfo("Spring Boot", version, file.getFileName().toString()));
            }

            // JUnit
            if (content.contains("junit-jupiter") || content.contains("org.junit.jupiter")) {
                testFrameworks.add(new ProjectContext.TestFramework("JUnit 5", null, file.getFileName().toString()));
            } else if (content.contains("junit")) {
                testFrameworks.add(new ProjectContext.TestFramework("JUnit", null, file.getFileName().toString()));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse Gradle build file", e);
        }
    }

    // ── pom.xml (Maven) ─────────────────────────────────────────────

    private static void detectFromPomXml(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "pom.xml");
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Java version
            Matcher javaVer = Pattern.compile("<(?:maven\\.compiler\\.source|java\\.version)>(\\d+)</").matcher(content);
            if (javaVer.find() && !hasLanguage(languages, "Java")) {
                languages.add(new ProjectContext.LanguageInfo("Java", javaVer.group(1), "pom.xml"));
            }

            // Spring Boot
            if (content.contains("spring-boot")) {
                Matcher springVer = Pattern.compile("spring-boot[^<]*<version>(.*?)</version>").matcher(content);
                String version = springVer.find() ? springVer.group(1) : null;
                if (!hasFramework(frameworks, "Spring Boot")) {
                    frameworks.add(new ProjectContext.FrameworkInfo("Spring Boot", version, "pom.xml"));
                }
            }

            // JUnit
            if (content.contains("junit-jupiter") && !hasTestFramework(testFrameworks, "JUnit 5")) {
                testFrameworks.add(new ProjectContext.TestFramework("JUnit 5", null, "pom.xml"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse pom.xml", e);
        }
    }

    // ── go.mod (Go) ─────────────────────────────────────────────────

    private static void detectFromGoMod(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "go.mod");
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Go version
            Matcher goVer = Pattern.compile("^go\\s+(\\d+\\.\\d+)", Pattern.MULTILINE).matcher(content);
            if (goVer.find() && !hasLanguage(languages, "Go")) {
                languages.add(new ProjectContext.LanguageInfo("Go", goVer.group(1), "go.mod"));
            }

            // Frameworks
            if (content.contains("github.com/gin-gonic/gin")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Gin", null, "go.mod"));
            }
            if (content.contains("github.com/labstack/echo")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Echo", null, "go.mod"));
            }
            if (content.contains("github.com/gofiber/fiber")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Fiber", null, "go.mod"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse go.mod", e);
        }
    }

    // ── Cargo.toml (Rust) ───────────────────────────────────────────

    private static void detectFromCargoToml(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "Cargo.toml");
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Rust edition
            Matcher edition = Pattern.compile("edition\\s*=\\s*\"(\\d+)\"").matcher(content);
            String editionStr = edition.find() ? edition.group(1) : null;
            if (!hasLanguage(languages, "Rust")) {
                languages.add(new ProjectContext.LanguageInfo(
                        "Rust", editionStr != null ? "edition " + editionStr : null, "Cargo.toml"));
            }

            // Frameworks
            if (content.contains("actix-web")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Actix Web", null, "Cargo.toml"));
            }
            if (content.contains("axum")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Axum", null, "Cargo.toml"));
            }
            if (content.contains("rocket")) {
                frameworks.add(new ProjectContext.FrameworkInfo("Rocket", null, "Cargo.toml"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse Cargo.toml", e);
        }
    }

    // ── Gemfile (Ruby) ──────────────────────────────────────────────

    private static void detectFromGemfile(
            @NotNull String basePath,
            @NotNull List<ProjectContext.LanguageInfo> languages,
            @NotNull List<ProjectContext.FrameworkInfo> frameworks,
            @NotNull List<ProjectContext.TestFramework> testFrameworks
    ) {
        Path file = Path.of(basePath, "Gemfile");
        if (!Files.exists(file)) return;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // Ruby version
            Matcher rubyVer = Pattern.compile("ruby\\s+[\"'](\\d+\\.\\d+)").matcher(content);
            if (rubyVer.find() && !hasLanguage(languages, "Ruby")) {
                languages.add(new ProjectContext.LanguageInfo("Ruby", rubyVer.group(1), "Gemfile"));
            }

            // Rails
            if (content.contains("'rails'") || content.contains("\"rails\"")) {
                Matcher railsVer = Pattern.compile("[\"']rails[\"']\\s*,\\s*[\"']~>\\s*(\\d+\\.\\d+)").matcher(content);
                String version = railsVer.find() ? railsVer.group(1) : null;
                frameworks.add(new ProjectContext.FrameworkInfo("Rails", version, "Gemfile"));
            }

            // Test frameworks
            if (content.contains("'rspec") || content.contains("\"rspec")) {
                testFrameworks.add(new ProjectContext.TestFramework("RSpec", null, "Gemfile"));
            }
            if (content.contains("'minitest") || content.contains("\"minitest")) {
                testFrameworks.add(new ProjectContext.TestFramework("Minitest", null, "Gemfile"));
            }

        } catch (Exception e) {
            LOG.debug("Failed to parse Gemfile", e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @Nullable
    static String extractVersion(@NotNull String constraint) {
        Matcher matcher = VERSION_PATTERN.matcher(constraint);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean hasLanguage(@NotNull List<ProjectContext.LanguageInfo> list, @NotNull String name) {
        return list.stream().anyMatch(l -> l.name().equalsIgnoreCase(name));
    }

    private static boolean hasFramework(@NotNull List<ProjectContext.FrameworkInfo> list, @NotNull String name) {
        return list.stream().anyMatch(f -> f.name().equalsIgnoreCase(name));
    }

    private static boolean hasTestFramework(@NotNull List<ProjectContext.TestFramework> list, @NotNull String name) {
        return list.stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
    }

    @Nullable
    private static JsonObject getJsonObject(@NotNull JsonObject parent, @NotNull String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    @Nullable
    private static String getJsonString(@NotNull JsonObject parent, @NotNull String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    @NotNull
    private static JsonObject mergeDeps(@Nullable JsonObject deps, @Nullable JsonObject devDeps) {
        JsonObject merged = new JsonObject();
        if (deps != null) {
            for (var entry : deps.entrySet()) {
                merged.add(entry.getKey(), entry.getValue());
            }
        }
        if (devDeps != null) {
            for (var entry : devDeps.entrySet()) {
                if (!merged.has(entry.getKey())) {
                    merged.add(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }
}
