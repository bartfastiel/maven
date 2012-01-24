package org.universAAL.maven.treebuilder;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ResolutionListener;
import org.apache.maven.artifact.resolver.ResolutionListenerForDepMgmt;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.traversal.CollectingDependencyNodeVisitor;

/**
 * An artifact resolution listener that constructs a dependency tree.
 * 
 * Class code was based on implementation of class
 * org.apache.maven.shared.dependency.tree.DependencyTreeResolutionListener
 * present in maven-dependency-tree-1.1.jar which is licensed under Apache
 * License, Version 2.0.
 * 
 * @author Marek Psiuk 
 * @author Edwin Punzalan
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 * @version $Id: DependencyTreeResolutionListener.java 576969 2007-09-18
 *          16:11:29Z markh $
 */
public class DependencyTreeResolutionListener implements ResolutionListener,
	ResolutionListenerForDepMgmt {
    // fields -----------------------------------------------------------------

    /**
     * The parent dependency nodes of the current dependency node.
     */
    private final Stack parentNodes;

    /**
     * A map of dependency nodes by their attached artifact.
     */
    private final Map nodesByArtifact;

    /**
     * The root dependency node of the computed dependency tree.
     */
    private List rootNodes;

    /**
     * The dependency node currently being processed by this listener.
     */
    private DependencyNode currentNode;

    /**
     * Map &lt; String replacementId, String premanaged version >
     */
    private Map managedVersions = new HashMap();

    /**
     * Map &lt; String replacementId, String premanaged scope >
     */
    private Map managedScopes = new HashMap();

    // constructors -----------------------------------------------------------

    private ArtifactFilter artifactFilter;

    /**
     * Creates a new dependency tree resolution listener that writes to the
     * specified log.
     * 
     * @param logger
     *            the log to write debug messages to
     */
    public DependencyTreeResolutionListener(ArtifactFilter artifactFilter) {
	this.artifactFilter = artifactFilter;
	parentNodes = new Stack();
	nodesByArtifact = new IdentityHashMap();
	rootNodes = new ArrayList();
	;
	currentNode = null;
    }

    // ResolutionListener methods ---------------------------------------------

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#testArtifact(org
     * .apache.maven.artifact.Artifact)
     */
    public void testArtifact(Artifact artifact) {
	log("testArtifact: artifact=" + artifact);
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#startProcessChildren
     * (org.apache.maven.artifact.Artifact)
     */
    public void startProcessChildren(Artifact artifact) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("startProcessChildren: artifact=" + artifact);

	if (!currentNode.getArtifact().equals(artifact)) {
	    throw new IllegalStateException("Artifact was expected to be "
		    + currentNode.getArtifact() + " but was " + artifact);
	}

	parentNodes.push(currentNode);
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#endProcessChildren
     * (org.apache.maven.artifact.Artifact)
     */
    public void endProcessChildren(Artifact artifact) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	DependencyNode node = (DependencyNode) parentNodes.pop();

	log("endProcessChildren: artifact=" + artifact);

	if (node == null) {
	    throw new IllegalStateException("Parent dependency node was null");
	}

	if (!node.getArtifact().equals(artifact)) {
	    throw new IllegalStateException(
		    "Parent dependency node artifact was expected to be "
			    + node.getArtifact() + " but was " + artifact);
	}
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#includeArtifact
     * (org.apache.maven.artifact.Artifact)
     */
    public void includeArtifact(Artifact artifact) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("includeArtifact: artifact=" + artifact);

	DependencyNode existingNode = getNode(artifact);

	/*
	 * Ignore duplicate includeArtifact calls since omitForNearer can be
	 * called prior to includeArtifact on the same artifact, and we don't
	 * wish to include it twice.
	 */
	if (existingNode == null && isCurrentNodeIncluded()) {
	    DependencyNode node = addNode(artifact);

	    /*
	     * Add the dependency management information cached in any prior
	     * manageArtifact calls, since includeArtifact is always called
	     * after manageArtifact.
	     */
	    flushDependencyManagement(node);
	}
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#omitForNearer(org
     * .apache.maven.artifact.Artifact, org.apache.maven.artifact.Artifact)
     */
    public void omitForNearer(Artifact omitted, Artifact kept) {
	if (!artifactFilter.include(omitted)) {
	    return;
	}
	// if (1==1) {
	// return;
	// }
	log("omitForNearer: omitted=" + omitted + " kept=" + kept);

	if (!omitted.getDependencyConflictId().equals(
		kept.getDependencyConflictId())) {
	    throw new IllegalArgumentException(
		    "Omitted artifact dependency conflict id "
			    + omitted.getDependencyConflictId()
			    + " differs from kept artifact dependency conflict id "
			    + kept.getDependencyConflictId());
	}

	if (isCurrentNodeIncluded()) {
	    DependencyNode omittedNode = getNode(omitted);

	    if (omittedNode != null) {
		removeNode(omitted);
	    } else {
		omittedNode = createNode(omitted);

		currentNode = omittedNode;

		if (omittedNode.getDepth() == 0) {
		    rootNodes.add(omittedNode);
		}
	    }

	    omittedNode.omitForConflict(kept);

	    /*
	     * Add the dependency management information cached in any prior
	     * manageArtifact calls, since omitForNearer is always called after
	     * manageArtifact.
	     */
	    flushDependencyManagement(omittedNode);

	    DependencyNode keptNode = getNode(kept);

	    if (keptNode == null) {
		addNode(kept);
	    }
	}
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#updateScope(org
     * .apache.maven.artifact.Artifact, java.lang.String)
     */
    public void updateScope(Artifact artifact, String scope) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("updateScope: artifact=" + artifact + ", scope=" + scope);

	DependencyNode node = getNode(artifact);

	if (node == null) {
	    // updateScope events can be received prior to includeArtifact
	    // events
	    node = addNode(artifact);
	}

	node.setOriginalScope(artifact.getScope());
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#manageArtifact(
     * org.apache.maven.artifact.Artifact, org.apache.maven.artifact.Artifact)
     */
    public void manageArtifact(Artifact artifact, Artifact replacement) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	// TODO: remove when ResolutionListenerForDepMgmt merged into
	// ResolutionListener

	log("manageArtifact: artifact=" + artifact + ", replacement="
		+ replacement);

	if (replacement.getVersion() != null) {
	    manageArtifactVersion(artifact, replacement);
	}

	if (replacement.getScope() != null) {
	    manageArtifactScope(artifact, replacement);
	}
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#omitForCycle(org
     * .apache.maven.artifact.Artifact)
     */
    public void omitForCycle(Artifact artifact) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("omitForCycle: artifact=" + artifact);

	if (isCurrentNodeIncluded()) {
	    DependencyNode node = createNode(artifact);

	    node.omitForCycle();
	}
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#updateScopeCurrentPom
     * (org.apache.maven.artifact.Artifact, java.lang.String)
     */
    public void updateScopeCurrentPom(Artifact artifact, String scopeIgnored) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("updateScopeCurrentPom: artifact=" + artifact + ", scopeIgnored="
		+ scopeIgnored);

	DependencyNode node = getNode(artifact);

	if (node == null) {
	    // updateScopeCurrentPom events can be received prior to
	    // includeArtifact events
	    node = addNode(artifact);
	    // TODO remove the node that tried to impose its scope and add some
	    // info
	}

	node.setFailedUpdateScope(scopeIgnored);
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#selectVersionFromRange
     * (org.apache.maven.artifact.Artifact)
     */
    public void selectVersionFromRange(Artifact artifact) {
	log("selectVersionFromRange: artifact=" + artifact);

	// TODO: track version selection from range in node (MNG-3093)
    }

    /*
     * @see
     * org.apache.maven.artifact.resolver.ResolutionListener#restrictRange(org
     * .apache.maven.artifact.Artifact, org.apache.maven.artifact.Artifact,
     * org.apache.maven.artifact.versioning.VersionRange)
     */
    public void restrictRange(Artifact artifact, Artifact replacement,
	    VersionRange versionRange) {
	log("restrictRange: artifact=" + artifact + ", replacement="
		+ replacement + ", versionRange=" + versionRange);

	// TODO: track range restriction in node (MNG-3093)
    }

    // ResolutionListenerForDepMgmt methods -----------------------------------

    /*
     * @seeorg.apache.maven.artifact.resolver.ResolutionListenerForDepMgmt#
     * manageArtifactVersion(org.apache.maven.artifact.Artifact,
     * org.apache.maven.artifact.Artifact)
     */
    public void manageArtifactVersion(Artifact artifact, Artifact replacement) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("manageArtifactVersion: artifact=" + artifact + ", replacement="
		+ replacement);

	/*
	 * DefaultArtifactCollector calls manageArtifact twice: first with the
	 * change; then subsequently with no change. We ignore the second call
	 * when the versions are equal.
	 */
	if (isCurrentNodeIncluded()
		&& !replacement.getVersion().equals(artifact.getVersion())) {
	    /*
	     * Cache management information and apply in includeArtifact, since
	     * DefaultArtifactCollector mutates the artifact and then calls
	     * includeArtifact after manageArtifact.
	     */
	    managedVersions.put(replacement.getId(), artifact.getVersion());
	}
    }

    /*
     * @seeorg.apache.maven.artifact.resolver.ResolutionListenerForDepMgmt#
     * manageArtifactScope(org.apache.maven.artifact.Artifact,
     * org.apache.maven.artifact.Artifact)
     */
    public void manageArtifactScope(Artifact artifact, Artifact replacement) {
	if (!artifactFilter.include(artifact)) {
	    return;
	}
	log("manageArtifactScope: artifact=" + artifact + ", replacement="
		+ replacement);

	/*
	 * DefaultArtifactCollector calls manageArtifact twice: first with the
	 * change; then subsequently with no change. We ignore the second call
	 * when the scopes are equal.
	 */
	if (isCurrentNodeIncluded()
		&& !replacement.getScope().equals(artifact.getScope())) {
	    /*
	     * Cache management information and apply in includeArtifact, since
	     * DefaultArtifactCollector mutates the artifact and then calls
	     * includeArtifact after manageArtifact.
	     */
	    managedScopes.put(replacement.getId(), artifact.getScope());
	}
    }

    // public methods ---------------------------------------------------------

    /**
     * Gets a list of all dependency nodes in the computed dependency tree.
     * 
     * @return a list of dependency nodes
     * @deprecated As of 1.1, use a {@link CollectingDependencyNodeVisitor} on
     *             the root dependency node
     */
    public Collection getNodes() {
	return Collections.unmodifiableCollection(nodesByArtifact.values());
    }

    /**
     * Gets the root dependency node of the computed dependency tree.
     * 
     * @return the root node
     */
    public List getRootNodes() {
	return rootNodes;
    }

    // private methods --------------------------------------------------------

    /**
     * Writes the specified message to the log at debug level with indentation
     * for the current node's depth.
     * 
     * @param message
     *            the message to write to the log
     */
    private void log(String message) {
	int depth = parentNodes.size();

	StringBuffer buffer = new StringBuffer();

	for (int i = 0; i < depth; i++) {
	    buffer.append("  ");
	}

	buffer.append(message);
    }

    /**
     * Creates a new dependency node for the specified artifact and appends it
     * to the current parent dependency node.
     * 
     * @param artifact
     *            the attached artifact for the new dependency node
     * @return the new dependency node
     */
    private DependencyNode createNode(Artifact artifact) {
	DependencyNode node = new DependencyNode(artifact);

	if (!parentNodes.isEmpty()) {
	    DependencyNode parent = (DependencyNode) parentNodes.peek();

	    parent.addChild(node);
	}

	return node;
    }

    /**
     * Creates a new dependency node for the specified artifact, appends it to
     * the current parent dependency node and puts it into the dependency node
     * cache.
     * 
     * @param artifact
     *            the attached artifact for the new dependency node
     * @return the new dependency node
     */
    // package protected for unit test
    DependencyNode addNode(Artifact artifact) {
	DependencyNode node = createNode(artifact);

	DependencyNode previousNode = (DependencyNode) nodesByArtifact.put(node
		.getArtifact(), node);

	if (previousNode != null) {
	    throw new IllegalStateException(
		    "Duplicate node registered for artifact: "
			    + node.getArtifact());
	}

	int depth = node.getDepth();
	if (depth == 0) {
	    rootNodes.add(node);
	}

	currentNode = node;

	return node;
    }

    /**
     * Gets the dependency node for the specified artifact from the dependency
     * node cache.
     * 
     * @param artifact
     *            the artifact to find the dependency node for
     * @return the dependency node, or <code>null</code> if the specified
     *         artifact has no corresponding dependency node
     */
    private DependencyNode getNode(Artifact artifact) {
	return (DependencyNode) nodesByArtifact.get(artifact);
    }

    /**
     * Removes the dependency node for the specified artifact from the
     * dependency node cache.
     * 
     * @param artifact
     *            the artifact to remove the dependency node for
     */
    private void removeNode(Artifact artifact) {
	DependencyNode node = (DependencyNode) nodesByArtifact.remove(artifact);

	if (!artifact.equals(node.getArtifact())) {
	    throw new IllegalStateException(
		    "Removed dependency node artifact was expected to be "
			    + artifact + " but was " + node.getArtifact());
	}
    }

    /**
     * Gets whether the all the ancestors of the dependency node currently being
     * processed by this listener have an included state.
     * 
     * @return <code>true</code> if all the ancestors of the current dependency
     *         node have a state of <code>INCLUDED</code>
     */
    private boolean isCurrentNodeIncluded() {
	boolean included = true;

	for (Iterator iterator = parentNodes.iterator(); included
		&& iterator.hasNext();) {
	    DependencyNode node = (DependencyNode) iterator.next();

	    if (node.getState() != DependencyNode.INCLUDED) {
		included = false;
	    }
	}

	return included;
    }

    /**
     * Updates the specified node with any dependency management information
     * cached in prior <code>manageArtifact</code> calls.
     * 
     * @param node
     *            the node to update
     */
    private void flushDependencyManagement(DependencyNode node) {
	Artifact artifact = node.getArtifact();
	String premanagedVersion = (String) managedVersions.get(artifact
		.getId());
	String premanagedScope = (String) managedScopes.get(artifact.getId());

	if (premanagedVersion != null || premanagedScope != null) {
	    if (premanagedVersion != null) {
		node.setPremanagedVersion(premanagedVersion);
	    }

	    if (premanagedScope != null) {
		node.setPremanagedScope(premanagedScope);
	    }

	    premanagedVersion = null;
	    premanagedScope = null;
	}
    }

    public Map getNodesByArtifact() {
	return nodesByArtifact;
    }

}