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
package org.eclipse.lemminx.customservice.synapse.parser.pom;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.lemminx.customservice.synapse.parser.Constants;
import org.eclipse.lemminx.customservice.synapse.parser.DependencyDetails;
import org.eclipse.lemminx.customservice.synapse.utils.Constant;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Resolves Maven property placeholders ({@code ${...}}) in a project's pom.xml.
 * <p>
 * Properties are collected from the project's pom.xml and its parent chain (e.g. the root pom of a
 * consolidated project), with child values overriding parent values as in Maven. Parent poms are
 * located through {@code <relativePath>} (defaulting to {@code ../pom.xml}) and, failing that,
 * the local Maven repository. The {@code project.*} and {@code project.parent.*} coordinates
 * are also available for interpolation.
 * </p>
 */
public final class PomPropertyResolver {

    private static final Logger LOGGER = Logger.getLogger(PomPropertyResolver.class.getName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_PARENT_DEPTH = 10;
    private static final String RELATIVE_PATH = "relativePath";
    private static final String DEFAULT_PARENT_RELATIVE_PATH = ".." + File.separator + Constants.POM_FILE;
    private static final String PROJECT_PREFIX = "project.";
    private static final String PROJECT_PARENT_PREFIX = "project.parent.";
    private static final String POM_EXTENSION = ".pom";

    private PomPropertyResolver() {

    }

    /**
     * Resolves property placeholders in the group ID, artifact ID and version of the given dependencies,
     * in place. Placeholders that cannot be resolved are left untouched.
     *
     * @param projectPath  the file system path of the project containing the pom.xml
     * @param dependencies the dependencies to resolve
     */
    public static void resolveDependencies(String projectPath, List<DependencyDetails> dependencies) {

        if (dependencies.stream().noneMatch(PomPropertyResolver::hasPlaceholder)) {
            return;
        }
        Map<String, String> properties = getProperties(projectPath);
        for (DependencyDetails dependency : dependencies) {
            dependency.setGroupId(resolve(dependency.getGroupId(), properties));
            dependency.setArtifact(resolve(dependency.getArtifact(), properties));
            dependency.setVersion(resolve(dependency.getVersion(), properties));
            if (hasPlaceholder(dependency)) {
                LOGGER.log(Level.WARNING, "Unresolved property in dependency: " + dependency.getGroupId() + ":"
                        + dependency.getArtifact() + ":" + dependency.getVersion());
            }
        }
    }

    /**
     * Collects the properties visible to the given project: those declared in its pom.xml and in its
     * parent poms, plus the {@code project.*} and {@code project.parent.*} coordinates.
     *
     * @param projectPath the file system path of the project containing the pom.xml
     * @return the collected properties, or an empty map if the pom.xml cannot be read
     */
    public static Map<String, String> getProperties(String projectPath) {

        List<Element> pomChain = loadPomChain(Path.of(projectPath, Constants.POM_FILE).toFile());
        if (pomChain.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> properties = new HashMap<>();
        // Apply from the furthest ancestor to the project so that child values take precedence
        for (int i = pomChain.size() - 1; i >= 0; i--) {
            Element propertiesElement = getChildElement(pomChain.get(i), Constants.PROPERTIES);
            if (propertiesElement == null) {
                continue;
            }
            for (Element property : getChildElements(propertiesElement)) {
                properties.put(property.getNodeName(), property.getTextContent().trim());
            }
        }

        Element project = pomChain.get(0);
        Element parent = getChildElement(project, Constant.PARENT);
        putIfNotBlank(properties, PROJECT_PREFIX + Constants.GROUP_ID, getGroupId(project));
        putIfNotBlank(properties, PROJECT_PREFIX + Constants.ARTIFACT_ID,
                getChildText(project, Constants.ARTIFACT_ID));
        putIfNotBlank(properties, PROJECT_PREFIX + Constants.VERSION, getVersion(project));
        if (parent != null) {
            putIfNotBlank(properties, PROJECT_PARENT_PREFIX + Constants.GROUP_ID,
                    getChildText(parent, Constants.GROUP_ID));
            putIfNotBlank(properties, PROJECT_PARENT_PREFIX + Constants.ARTIFACT_ID,
                    getChildText(parent, Constants.ARTIFACT_ID));
            putIfNotBlank(properties, PROJECT_PARENT_PREFIX + Constants.VERSION,
                    getChildText(parent, Constants.VERSION));
        }
        return properties;
    }

    /**
     * Replaces {@code ${...}} placeholders in the given value using the given properties. Property values
     * may themselves contain placeholders. Unknown or cyclic placeholders are left untouched.
     *
     * @param value      the value to resolve
     * @param properties the properties to resolve against
     * @return the resolved value
     */
    public static String resolve(String value, Map<String, String> properties) {

        if (value == null || !value.contains("${")) {
            return value;
        }
        return resolve(value, properties, new HashSet<>());
    }

    private static String resolve(String value, Map<String, String> properties, Set<String> resolving) {

        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = properties.get(key);
            if (replacement == null || resolving.contains(key)) {
                replacement = matcher.group(0);
            } else {
                resolving.add(key);
                replacement = resolve(replacement, properties, resolving);
                resolving.remove(key);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Loads the project pom and its parent poms, starting with the project pom.
     */
    private static List<Element> loadPomChain(File pomFile) {

        List<Element> pomChain = new ArrayList<>();
        File currentPom = pomFile;
        while (currentPom != null && pomChain.size() < MAX_PARENT_DEPTH) {
            Element project = parsePom(currentPom);
            if (project == null) {
                break;
            }
            pomChain.add(project);
            currentPom = findParentPom(currentPom, project);
        }
        return pomChain;
    }

    /**
     * Locates the parent pom declared in the given project, first through {@code <relativePath>} and then
     * in the local Maven repository. Returns {@code null} if there is no parent or it cannot be found.
     */
    private static File findParentPom(File pomFile, Element project) {

        Element parent = getChildElement(project, Constant.PARENT);
        if (parent == null) {
            return null;
        }
        String groupId = getChildText(parent, Constants.GROUP_ID);
        String artifactId = getChildText(parent, Constants.ARTIFACT_ID);
        String version = getChildText(parent, Constants.VERSION);

        // An empty <relativePath/> disables the local lookup, as in Maven
        Element relativePathElement = getChildElement(parent, RELATIVE_PATH);
        String relativePath = relativePathElement == null ? DEFAULT_PARENT_RELATIVE_PATH :
                relativePathElement.getTextContent().trim();
        if (StringUtils.isNotBlank(relativePath)) {
            File candidate = pomFile.getParentFile().toPath().resolve(relativePath).normalize().toFile();
            if (candidate.isDirectory()) {
                candidate = new File(candidate, Constants.POM_FILE);
            }
            if (candidate.isFile() && isMatchingPom(candidate, groupId, artifactId)) {
                return candidate;
            }
        }

        if (StringUtils.isNoneBlank(groupId, artifactId, version)) {
            File repositoryPom = Path.of(System.getProperty(Constant.USER_HOME), Constant.M2, Constant.REPOSITORY)
                    .resolve(groupId.replace(Constant.DOT, File.separator))
                    .resolve(artifactId).resolve(version)
                    .resolve(artifactId + Constant.HYPHEN + version + POM_EXTENSION).toFile();
            if (repositoryPom.isFile()) {
                return repositoryPom;
            }
        }
        LOGGER.log(Level.FINE, "Parent pom not found for " + pomFile + ": " + groupId + ":" + artifactId + ":"
                + version);
        return null;
    }

    private static boolean isMatchingPom(File pomFile, String groupId, String artifactId) {

        Element project = parsePom(pomFile);
        if (project == null) {
            return false;
        }
        return (StringUtils.isBlank(groupId) || groupId.equals(getGroupId(project))) &&
                (StringUtils.isBlank(artifactId) || artifactId.equals(getChildText(project, Constants.ARTIFACT_ID)));
    }

    private static Element parsePom(File pomFile) {

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pomFile).getDocumentElement();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error parsing pom file " + pomFile + ": " + e.getMessage());
            return null;
        }
    }

    private static String getGroupId(Element project) {

        String groupId = getChildText(project, Constants.GROUP_ID);
        if (StringUtils.isBlank(groupId)) {
            Element parent = getChildElement(project, Constant.PARENT);
            groupId = parent != null ? getChildText(parent, Constants.GROUP_ID) : null;
        }
        return groupId;
    }

    private static String getVersion(Element project) {

        String version = getChildText(project, Constants.VERSION);
        if (StringUtils.isBlank(version)) {
            Element parent = getChildElement(project, Constant.PARENT);
            version = parent != null ? getChildText(parent, Constants.VERSION) : null;
        }
        return version;
    }

    private static boolean hasPlaceholder(DependencyDetails dependency) {

        return StringUtils.contains(dependency.getGroupId(), "${") ||
                StringUtils.contains(dependency.getArtifact(), "${") ||
                StringUtils.contains(dependency.getVersion(), "${");
    }

    private static void putIfNotBlank(Map<String, String> properties, String key, String value) {

        if (StringUtils.isNotBlank(value)) {
            properties.put(key, value);
        }
    }

    private static String getChildText(Element parent, String name) {

        Element child = getChildElement(parent, name);
        return child != null ? child.getTextContent().trim() : null;
    }

    private static Element getChildElement(Element parent, String name) {

        for (Element child : getChildElements(parent)) {
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> getChildElements(Element parent) {

        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) child);
            }
        }
        return elements;
    }
}
