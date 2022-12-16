package org.openrewrite.gradle.plugins;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpec;
import org.openrewrite.test.SourceSpecs;

import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class InitscriptUpgradeTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new InitscriptUpgrade("gradleEnterprisePluginVersion", "**/*.gradle", "com.gradle.enterprise", "3.X"));
    }

    private static SourceSpecs initscriptGradle(@Language("groovy") @Nullable String before, Consumer<SourceSpec<G.CompilationUnit>> spec) {
        SourceSpec<G.CompilationUnit> gradle = new SourceSpec<>(G.CompilationUnit.class, "gradle",  GroovyParser.builder().logCompilationWarningsAndErrors(true),
          before, null);
        gradle.path(Paths.get("init.gradle"));
        spec.accept(gradle);
        return gradle;
    }

    @Test
    void addNewPluginsBlock() {
        rewriteRun(
          // TODO: bug in parser this: \\${gradleEnterprisePluginVersion}
          initscriptGradle(
            """
              initscript {
                  def gradleEnterprisePluginVersion = "3.11.1"
                  repositories {
                      def dummy = "should not change"
                      gradlePluginPortal()
                  }
              }""",
            spec -> spec.after(actual -> {
                assertThat(actual).isNotNull();
                Matcher version = Pattern.compile("3.\\d+").matcher(actual);
                assertThat(version.find()).isTrue();
                String newVersion = version.group(0);
                assertThat(newVersion).isNotEqualTo("3.11.1");
                return """
              initscript {
                  def gradleEnterprisePluginVersion = "%s"
                  repositories {
                      def dummy = "should not change"
                      gradlePluginPortal()
                  }
              }""".formatted(newVersion);
            })
          )
        );
    }
}