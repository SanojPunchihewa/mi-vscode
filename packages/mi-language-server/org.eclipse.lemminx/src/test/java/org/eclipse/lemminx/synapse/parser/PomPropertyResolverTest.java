/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */
package org.eclipse.lemminx.synapse.parser;

import org.eclipse.lemminx.customservice.synapse.parser.DependencyDetails;
import org.eclipse.lemminx.customservice.synapse.parser.pom.PomPropertyResolver;
import org.eclipse.lemminx.synapse.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PomPropertyResolver}.
 */
public class PomPropertyResolverTest {

    private Path root;

    @BeforeEach
    public void setUp() throws IOException {

        root = Files.createTempDirectory("mi-pom-resolver-");
    }

    @AfterEach
    public void tearDown() throws IOException {

        TestUtils.deleteRecursively(root);
    }

    /**
     * A module of a consolidated project should resolve a dependency version from a property
     * declared in the root pom.
     */
    @Test
    public void testPropertyFromParentPom_resolved() throws IOException {

        writePom(root, rootPom("<version.common-lib>1.2.0</version.common-lib>"));
        Path module = root.resolve("module-a");
        writePom(module, modulePom("", "<parent><groupId>com.example</groupId><artifactId>root</artifactId>"
                + "<version>1.0.0</version></parent>"));

        DependencyDetails dependency = makeDep("com.example", "common-lib", "${version.common-lib}");
        PomPropertyResolver.resolveDependencies(module.toString(), List.of(dependency));

        assertEquals("1.2.0", dependency.getVersion());
    }

    /**
     * A property declared in the module should override the same property in the parent pom.
     */
    @Test
    public void testModulePropertyOverridesParent() throws IOException {

        writePom(root, rootPom("<version.common-lib>1.2.0</version.common-lib>"));
        Path module = root.resolve("module-a");
        writePom(module, modulePom("<version.common-lib>2.0.0</version.common-lib>",
                "<parent><groupId>com.example</groupId><artifactId>root</artifactId>"
                        + "<version>1.0.0</version></parent>"));

        DependencyDetails dependency = makeDep("com.example", "common-lib", "${version.common-lib}");
        PomPropertyResolver.resolveDependencies(module.toString(), List.of(dependency));

        assertEquals("2.0.0", dependency.getVersion());
    }

    /**
     * Project coordinates, nested properties and a parent located through relativePath should be resolved.
     */
    @Test
    public void testProjectCoordinatesAndNestedPropertiesWithRelativePath() throws IOException {

        Path parentDir = root.resolve("parent");
        writePom(parentDir, rootPom("<base.version>3.1</base.version>"
                + "<version.common-lib>${base.version}.0</version.common-lib>"));
        Path module = root.resolve("modules").resolve("module-a");
        writePom(module, modulePom("", "<parent><groupId>com.example</groupId><artifactId>root</artifactId>"
                + "<version>1.0.0</version><relativePath>../../parent</relativePath></parent>"));

        Map<String, String> properties = PomPropertyResolver.getProperties(module.toString());

        assertEquals("3.1.0", PomPropertyResolver.resolve("${version.common-lib}", properties));
        assertEquals("com.example", PomPropertyResolver.resolve("${project.groupId}", properties));
        assertEquals("1.0.0", PomPropertyResolver.resolve("${project.version}", properties));
        assertEquals("1.0.0", PomPropertyResolver.resolve("${project.parent.version}", properties));
    }

    /**
     * Unknown and cyclic properties should be left untouched.
     */
    @Test
    public void testUnknownAndCyclicPropertiesLeftUntouched() throws IOException {

        writePom(root, modulePom("<a>${b}</a><b>${a}</b>", ""));

        Map<String, String> properties = PomPropertyResolver.getProperties(root.toString());

        assertEquals("${missing}", PomPropertyResolver.resolve("${missing}", properties));
        assertEquals("${a}", PomPropertyResolver.resolve("${a}", properties));
    }

    /**
     * A parent pom whose coordinates do not match the declared parent should be ignored.
     */
    @Test
    public void testMismatchedParentPomIgnored() throws IOException {

        writePom(root, rootPom("<version.common-lib>1.2.0</version.common-lib>"));
        Path module = root.resolve("module-a");
        writePom(module, modulePom("", "<parent><groupId>com.other</groupId><artifactId>other-root</artifactId>"
                + "<version>1.0.0</version></parent>"));

        DependencyDetails dependency = makeDep("com.example", "common-lib", "${version.common-lib}");
        PomPropertyResolver.resolveDependencies(module.toString(), List.of(dependency));

        assertEquals("${version.common-lib}", dependency.getVersion());
    }

    private static String rootPom(String properties) {

        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0.0</version>"
                + "<packaging>pom</packaging><properties>" + properties + "</properties></project>";
    }

    private static String modulePom(String properties, String parent) {

        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + parent + "<artifactId>module-a</artifactId><properties>" + properties + "</properties>"
                + "</project>";
    }

    private static void writePom(Path directory, String content) throws IOException {

        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), content, StandardCharsets.UTF_8);
    }

    private static DependencyDetails makeDep(String groupId, String artifactId, String version) {

        DependencyDetails dependency = new DependencyDetails();
        dependency.setGroupId(groupId);
        dependency.setArtifact(artifactId);
        dependency.setVersion(version);
        dependency.setType("car");
        return dependency;
    }
}
