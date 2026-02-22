plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.kacmedija.claudeassist"
version = "0.4.4"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    // Use IC (IntelliJ Community) for faster dev cycles, PS (PHPStorm) for final build
    version.set("2024.1")
    type.set(providers.gradleProperty("ideType").getOrElse("IC"))
    plugins.set(
        if (providers.gradleProperty("ideType").getOrElse("IC") == "PS") {
            listOf("org.jetbrains.plugins.terminal", "com.jetbrains.php")
        } else {
            listOf("org.jetbrains.plugins.terminal")
        }
    )
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")
        changeNotes.set("""
            <h3>0.2.0</h3>
            <ul>
                <li>Universal IDE integration — plugin now works with any language/framework, not just PHP</li>
                <li>Auto-detect project context: languages, frameworks, and test frameworks from config files (composer.json, package.json, pyproject.toml, build.gradle, pom.xml, go.mod, Cargo.toml, Gemfile)</li>
                <li>IDE code style injection — indentation, brace placement, line length sent to Claude automatically</li>
                <li>Real diagnostics — Fix Diagnostics now gathers actual IDE warnings/errors instead of placeholder text</li>
                <li>Smart doc generation — PHPDoc, Javadoc, JSDoc, rustdoc, etc. based on detected language</li>
                <li>Smart test generation — uses detected test framework (Jest, PHPUnit, Pytest, JUnit 5, etc.)</li>
                <li>Universal code review dialog — auto-detected project standards replace PHP-only dropdowns</li>
                <li>Language detection via JetBrains PsiFile API instead of file extension mapping</li>
            </ul>
            <h3>0.1.2</h3>
            <ul>
                <li>Fix status bar showing "N/A" — health check now uses login shell to find claude binary</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Chat panel with streaming responses</li>
                <li>Inline edit with diff preview</li>
                <li>Context-aware prompts (file, selection, diagnostics)</li>
                <li>Quick actions: Explain, Refactor, Tests, Docs, Review</li>
                <li>WSL2 bridge for Windows users</li>
            </ul>
        """.trimIndent())
    }

    buildSearchableOptions {
        enabled = false
    }

    runIde {
        jvmArgs("-Xmx2g")
        // Auto-reload plugin on rebuild
        autoReloadPlugins.set(true)
    }
}
