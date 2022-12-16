package org.openrewrite.gradle.plugins;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.HasSourcePath;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;
import org.openrewrite.groovy.GroovyVisitor;
import org.openrewrite.java.ChangeLiteral;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.semver.ExactVersion;
import org.openrewrite.semver.Semver;
import org.openrewrite.semver.VersionComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.openrewrite.gradle.plugins.GradlePluginUtils.availablePluginVersions;

@Value
@EqualsAndHashCode(callSuper = true)
public class InitscriptUpgrade extends Recipe {
    private static final Logger logger = LoggerFactory.getLogger(InitscriptUpgrade.class);
    @Option(displayName = "Variable name",
        description = "The variable name .",
        example = "gradleEnterprisePluginVersion")
    String varName;

    @Option(displayName = "File pattern",
        description = "File pattern corresponding to gradle scripts.",
        example = "filePattern")
    String filePattern;

    @Option(displayName = "Plugin id",
        description = "The exact Gradle plugin id.",
        example = "com.gradle.enterprise")
    String pluginId;

    @Option(displayName = "New version",
        description = "An exact version number or node-style semver selector used to select the version number.",
        example = "29.X")
    String newVersion;

    @Override
    public String getDisplayName() {
        return "Update a var referencing a version in an initscript";
    }

    @Override
    public String getDescription() {
        return "Update a Gradle init script var to a later version.";
    }

    @Override
    public Validated validate() {
        return super.validate().and(Semver.validate(newVersion, null));
    }


    @Override
    protected TreeVisitor<?, ExecutionContext> getApplicableTest() {
        return new HasSourcePath<>(filePattern);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        VersionComparator versionComparator = Semver.validate(newVersion, null).getValue();
        assert versionComparator != null;
        return new GroovyVisitor<ExecutionContext>() {
            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (!(m.getSimpleName().equals("initscript"))) {
                    return m;
                }
                List<Expression> initscriptArgs = m.getArguments();
                if (!(initscriptArgs.get(0) instanceof J.Lambda)) {
                    return m;
                }
                J.Block initscriptBlock = (J.Block) ((J.Lambda) initscriptArgs.get(0)).getBody();
                Optional<J.VariableDeclarations.NamedVariable> variable = findVariable(initscriptBlock);
                if (!variable.isPresent() || variable.get().getInitializer() == null) {
                    return m;
                }
                doAfterVisit(new ChangeLiteral<>(variable.get().getInitializer(), v -> upgradeVersion(ctx, v, versionComparator)));
                return m;
            }
        };
    }

    @NotNull
    private Object upgradeVersion(ExecutionContext ctx, Object v, VersionComparator versionComparator) {
        if (!(v instanceof String)) {
            return v;
        }
        String currentVersion = (String) v;
        Optional<String> version;
        if (versionComparator instanceof ExactVersion) {
            version = versionComparator.upgrade(currentVersion, Collections.singletonList(newVersion));
        } else {
            version = versionComparator.upgrade(currentVersion, availablePluginVersions(pluginId, ctx));
        }
        return version.orElse(currentVersion);
    }

    @NotNull
    private Optional<J.VariableDeclarations.NamedVariable> findVariable(J.Block initscriptBlock) {
        List<Statement> statements = initscriptBlock.getStatements();
        return statements.stream()
            .filter(s -> s instanceof J.VariableDeclarations)
            .flatMap(s -> ((J.VariableDeclarations) s).getVariables().stream())
            .filter(v -> varName.equals(v.getSimpleName()))
            .findAny();
    }
}
