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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.metadata.ResolutionGroup;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.CyclicDependencyException;
import org.apache.maven.artifact.resolver.ResolutionListener;
import org.apache.maven.artifact.resolver.ResolutionListenerForDepMgmt;
import org.apache.maven.artifact.resolver.ResolutionNode;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.ManagedVersionMap;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;

/**
 * Class builds one big dependency tree for given list of artifacts. Each
 * artifact in the list is treated as a rootnode of a separate tree. All
 * duplicates and conflicts are detected throughout the big tree. The tree
 * contains not only regular maven dependencies but also runtime dependencies
 * which are extracted from "uAAL-Runtime" profile of each pom. Artifacts of the
 * following scopes are included in the tree:
 * <ul>
 * <li/>regular maven dependencies: compile and runtime
 * <li/>maven dependencies from "uAAL-Runtime" profile: compile, runtime and
 * provided
 * </ul>
 * 
 * Class code was based on the implementation of class
 * org.apache.maven.shared.dependency.tree.DefaultDependencyTreeBuilder present
 * in maven-dependency-tree-1.1.jar and the implementation of class
 * org.apache.maven.artifact.resolver.DefaultArtifactCollector present in
 * maven-artifact-2.2.1.jar. Both classes are licensed under Apache License,
 * Version 2.0.
 * 
 * @author rotgier
 * 
 * 
 */
public class DependencyTreeBuilder {

    public static final String UAAL_RUNTIME_PROFILE = "uAAL-Runtime";

    private ArtifactFactory artifactFactory;

    private MavenProjectBuilder mavenProjectBuilder;

    private ArtifactRepository localRepository;

    private List remoteRepositories;

    public DependencyTreeBuilder(ArtifactFactory artifactFactory,
	    MavenProjectBuilder mavenProjectBuilder,
	    ArtifactRepository localRepository, List remoteRepositories) {
	this.artifactFactory = artifactFactory;
	this.mavenProjectBuilder = mavenProjectBuilder;
	this.localRepository = localRepository;
	this.remoteRepositories = remoteRepositories;
    }

    private void fireEvent(int event, List listeners, ResolutionNode node) {
	fireEvent(event, listeners, node, null);
    }

    private void fireEvent(int event, List listeners, ResolutionNode node,
	    Artifact replacement) {
	fireEvent(event, listeners, node, replacement, null);
    }

    private void fireEvent(int event, List listeners, ResolutionNode node,
	    Artifact replacement, VersionRange newRange) {
	for (Iterator i = listeners.iterator(); i.hasNext();) {
	    ResolutionListener listener = (ResolutionListener) i.next();

	    switch (event) {
	    case ResolutionListener.TEST_ARTIFACT:
		listener.testArtifact(node.getArtifact());
		break;
	    case ResolutionListener.PROCESS_CHILDREN:
		listener.startProcessChildren(node.getArtifact());
		break;
	    case ResolutionListener.FINISH_PROCESSING_CHILDREN:
		listener.endProcessChildren(node.getArtifact());
		break;
	    case ResolutionListener.INCLUDE_ARTIFACT:
		listener.includeArtifact(node.getArtifact());
		break;
	    case ResolutionListener.OMIT_FOR_NEARER:
		listener.omitForNearer(node.getArtifact(), replacement);
		break;
	    case ResolutionListener.OMIT_FOR_CYCLE:
		listener.omitForCycle(node.getArtifact());
		break;
	    case ResolutionListener.UPDATE_SCOPE:
		listener
			.updateScope(node.getArtifact(), replacement.getScope());
		break;
	    case ResolutionListener.UPDATE_SCOPE_CURRENT_POM:
		listener.updateScopeCurrentPom(node.getArtifact(), replacement
			.getScope());
		break;
	    case ResolutionListener.MANAGE_ARTIFACT_VERSION:
		if (listener instanceof ResolutionListenerForDepMgmt) {
		    ResolutionListenerForDepMgmt asImpl = (ResolutionListenerForDepMgmt) listener;
		    asImpl.manageArtifactVersion(node.getArtifact(),
			    replacement);
		} else {
		    listener.manageArtifact(node.getArtifact(), replacement);
		}
		break;
	    case ResolutionListener.MANAGE_ARTIFACT_SCOPE:
		if (listener instanceof ResolutionListenerForDepMgmt) {
		    ResolutionListenerForDepMgmt asImpl = (ResolutionListenerForDepMgmt) listener;
		    asImpl.manageArtifactScope(node.getArtifact(), replacement);
		} else {
		    listener.manageArtifact(node.getArtifact(), replacement);
		}
		break;
	    case ResolutionListener.SELECT_VERSION_FROM_RANGE:
		listener.selectVersionFromRange(node.getArtifact());
		break;
	    case ResolutionListener.RESTRICT_RANGE:
		if (node.getArtifact().getVersionRange().hasRestrictions()
			|| replacement.getVersionRange().hasRestrictions()) {
		    listener.restrictRange(node.getArtifact(), replacement,
			    newRange);
		}
		break;
	    default:
		throw new IllegalStateException("Unknown event: " + event);
	    }
	}
    }

    private boolean checkScopeUpdate(ResolutionNode farthest,
	    ResolutionNode nearest, List listeners) {
	boolean updateScope = false;
	Artifact farthestArtifact = farthest.getArtifact();
	Artifact nearestArtifact = nearest.getArtifact();

	/* farthest is runtime and nearest has lower priority, change to runtime */
	if (Artifact.SCOPE_RUNTIME.equals(farthestArtifact.getScope())
		&& (Artifact.SCOPE_TEST.equals(nearestArtifact.getScope()) || Artifact.SCOPE_PROVIDED
			.equals(nearestArtifact.getScope()))) {
	    updateScope = true;
	}

	/*
	 * farthest is compile and nearest is not (has lower priority), change
	 * to compile
	 */
	if (Artifact.SCOPE_COMPILE.equals(farthestArtifact.getScope())
		&& !Artifact.SCOPE_COMPILE.equals(nearestArtifact.getScope())) {
	    updateScope = true;
	}

	/*
	 * current POM rules all, if nearest is in current pom, do not update
	 * its scope
	 */
	if (nearest.getDepth() < 2 && updateScope) {
	    updateScope = false;
	}

	if (updateScope) {
	    // previously we cloned the artifact, but it is more effecient to
	    // just update the scope
	    // if problems are later discovered that the original object needs
	    // its original scope value, cloning may
	    // again be appropriate
	    nearestArtifact.setScope(farthestArtifact.getScope());
	}

	return updateScope;
    }

    private void manageArtifact(ResolutionNode node,
	    ManagedVersionMap managedVersions, List listeners) {
	Artifact artifact = (Artifact) managedVersions.get(node.getKey());

	// Before we update the version of the artifact, we need to know
	// whether we are working on a transitive dependency or not. This
	// allows depMgmt to always override transitive dependencies, while
	// explicit child override depMgmt (viz. depMgmt should only
	// provide defaults to children, but should override transitives).
	// We can do this by calling isChildOfRootNode on the current node.

	if (artifact.getVersion() != null
		&& (node.isChildOfRootNode() ? node.getArtifact().getVersion() == null
			: true)) {
	    if (!"org.apache.felix:org.osgi.compendium:jar".equals(node
		    .getKey())) {
		node.getArtifact().setVersion(artifact.getVersion());
	    }
	}

	if (artifact.getScope() != null
		&& (node.isChildOfRootNode() ? node.getArtifact().getScope() == null
			: true)) {
	    if (!"org.apache.felix:org.osgi.compendium:jar".equals(node
		    .getKey())) {
		node.getArtifact().setScope(artifact.getScope());
	    }
	}
    }

    private boolean resolveChildNode(ResolutionNode node, ResolutionNode child,
	    ArtifactFilter filter, ManagedVersionMap managedVersions,
	    List listeners, ArtifactMetadataSource source,
	    Artifact parentArtifact, boolean runtimeDep)
	    throws OverConstrainedVersionException,
	    ArtifactMetadataRetrievalException {
	// We leave in optional ones, but don't pick up its dependencies
	if (!child.isResolved()
		&& (!child.getArtifact().isOptional() || child
			.isChildOfRootNode())) {
	    Artifact artifact = child.getArtifact();
	    artifact.setDependencyTrail(node.getDependencyTrail());

	    List childRemoteRepositories = child.getRemoteRepositories();
	    try {
		Object childKey;
		do {
		    childKey = child.getKey();

		    if (managedVersions.containsKey(childKey)) {
			// If this child node is a managed dependency,
			// ensure
			// we are using the dependency management
			// version
			// of this child if applicable b/c we want to
			// use the
			// managed version's POM, *not* any other
			// version's POM.
			// We retrieve the POM below in the retrieval
			// step.
			manageArtifact(child, managedVersions, listeners);

			// Also, we need to ensure that any exclusions
			// it presents are
			// added to the artifact before we retrieve the
			// metadata
			// for the artifact; otherwise we may end up
			// with unwanted
			// dependencies.
			Artifact ma = (Artifact) managedVersions.get(childKey);
			ArtifactFilter managedExclusionFilter = ma
				.getDependencyFilter();
			if (null != managedExclusionFilter) {
			    if (null != artifact.getDependencyFilter()) {
				AndArtifactFilter aaf = new AndArtifactFilter();
				aaf.add(artifact.getDependencyFilter());
				aaf.add(managedExclusionFilter);
				artifact.setDependencyFilter(aaf);
			    } else {
				artifact
					.setDependencyFilter(managedExclusionFilter);
			    }
			}
		    }

		    if (artifact.getVersion() == null) {
			// set the recommended version
			// TODO: maybe its better to just pass the range
			// through to retrieval and use a
			// transformation?
			ArtifactVersion version;
			if (artifact.isSelectedVersionKnown()) {
			    version = artifact.getSelectedVersion();
			} else {
			    // go find the version
			    List versions = artifact.getAvailableVersions();
			    if (versions == null) {
				versions = source.retrieveAvailableVersions(
					artifact, localRepository,
					childRemoteRepositories);
				artifact.setAvailableVersions(versions);
			    }

			    Collections.sort(versions);

			    VersionRange versionRange = artifact
				    .getVersionRange();

			    version = versionRange.matchVersion(versions);

			    if (version == null) {
				if (versions.isEmpty()) {
				    throw new OverConstrainedVersionException(
					    "No versions are present in the repository for the artifact with a range "
						    + versionRange, artifact,
					    childRemoteRepositories);
				}

				throw new OverConstrainedVersionException(
					"Couldn't find a version in "
						+ versions + " to match range "
						+ versionRange, artifact,
					childRemoteRepositories);
			    }
			}

			// this is dangerous because
			// artifact.getSelectedVersion() can
			// return null. However it is ok here because we
			// first check if the
			// selected version is known. As currently coded
			// we can't get a null here.
			artifact.selectVersion(version.toString());
			fireEvent(ResolutionListener.SELECT_VERSION_FROM_RANGE,
				listeners, child);
		    }

		    // rotgier: it is not compatible with maven 3
		    // Artifact relocated = source.retrieveRelocatedArtifact(
		    // artifact, localRepository, childRemoteRepositories);
		    // if (relocated != null && !artifact.equals(relocated)) {
		    // relocated.setDependencyFilter(artifact
		    // .getDependencyFilter());
		    // artifact = relocated;
		    // child.setArtifact(artifact);
		    // }
		} while (!childKey.equals(child.getKey()));

		if (parentArtifact != null
			&& parentArtifact.getDependencyFilter() != null
			&& !parentArtifact.getDependencyFilter().include(
				artifact)) {
		    // MNG-3769: the [probably relocated] artifact is
		    // excluded.
		    // We could process exclusions on relocated artifact
		    // details in the
		    // MavenMetadataSource.createArtifacts(..) step, BUT
		    // that would
		    // require resolving the POM from the repository
		    // very early on in
		    // the build.
		    return true;
		}

		ResolutionGroup rGroup = source.retrieve(artifact,
			localRepository, childRemoteRepositories);

		// TODO might be better to have source.retrieve() throw
		// a specific exception for this situation
		// and catch here rather than have it return null
		if (rGroup == null) {
		    // relocated dependency artifact is declared
		    // excluded, no need to add and recurse further
		    return true;
		}

		child.addDependencies(rGroup.getArtifacts(), rGroup
			.getResolutionRepositories(), filter);

	    } catch (CyclicDependencyException e) {
		// would like to throw this, but we have crappy stuff in
		// the repo

		fireEvent(ResolutionListener.OMIT_FOR_CYCLE, listeners,
			new ResolutionNode(e.getArtifact(),
				childRemoteRepositories, child));
	    } catch (ArtifactMetadataRetrievalException e) {
		artifact.setDependencyTrail(node.getDependencyTrail());
		throw e;
	    }
	}
	return false;
    }

    private void recurse(Artifact originatingArtifact, ResolutionNode node,
	    Map resolvedArtifacts, ManagedVersionMap managedVersions,
	    ArtifactRepository localRepository, List remoteRepositories,
	    ArtifactMetadataSource source, ArtifactFilter filter,
	    List listeners, boolean transitive)
	    throws CyclicDependencyException, ArtifactResolutionException,
	    OverConstrainedVersionException, ArtifactMetadataRetrievalException {
	fireEvent(ResolutionListener.TEST_ARTIFACT, listeners, node);
	Object key = node.getKey();

	// TODO: Does this check need to happen here? Had to add the same call
	// below when we iterate on child nodes -- will that suffice?
	if (managedVersions.containsKey(key)) {
	    manageArtifact(node, managedVersions, listeners);
	}

	List previousNodes = (List) resolvedArtifacts.get(key);
	if (previousNodes != null) {
	    for (Iterator i = previousNodes.iterator(); i.hasNext();) {
		ResolutionNode previous = (ResolutionNode) i.next();

		if (previous.isActive()) {
		    // Version mediation
		    VersionRange previousRange = previous.getArtifact()
			    .getVersionRange();
		    VersionRange currentRange = node.getArtifact()
			    .getVersionRange();

		    if (previousRange != null && currentRange != null) {
			// TODO: shouldn't need to double up on this work, only
			// done for simplicity of handling recommended
			// version but the restriction is identical
			VersionRange newRange = previousRange
				.restrict(currentRange);
			// TODO: ick. this forces the OCE that should have come
			// from the previous call. It is still correct
			if (newRange.isSelectedVersionKnown(previous
				.getArtifact())) {
			    fireEvent(ResolutionListener.RESTRICT_RANGE,
				    listeners, node, previous.getArtifact(),
				    newRange);
			}
			previous.getArtifact().setVersionRange(newRange);
			node.getArtifact().setVersionRange(
				currentRange.restrict(previousRange));

			// Select an appropriate available version from the (now
			// restricted) range
			// Note this version was selected before to get the
			// appropriate POM
			// But it was reset by the call to setVersionRange on
			// restricting the version
			ResolutionNode[] resetNodes = { previous, node };
			for (int j = 0; j < 2; j++) {
			    Artifact resetArtifact = resetNodes[j]
				    .getArtifact();

			    // MNG-2123: if the previous node was not a range,
			    // then it wouldn't have any available
			    // versions. We just clobbered the selected version
			    // above. (why? i have no idea.)
			    // So since we are here and this is ranges we must
			    // go figure out the version (for a third time...)
			    if (resetArtifact.getVersion() == null
				    && resetArtifact.getVersionRange() != null) {

				// go find the version. This is a total hack.
				// See previous comment.
				List versions = resetArtifact
					.getAvailableVersions();
				if (versions == null) {
				    try {
					versions = source
						.retrieveAvailableVersions(
							resetArtifact,
							localRepository,
							remoteRepositories);
					resetArtifact
						.setAvailableVersions(versions);
				    } catch (ArtifactMetadataRetrievalException e) {
					resetArtifact.setDependencyTrail(node
						.getDependencyTrail());
					throw e;
				    }
				}
				// end hack

				// MNG-2861: match version can return null
				ArtifactVersion selectedVersion = resetArtifact
					.getVersionRange()
					.matchVersion(
						resetArtifact
							.getAvailableVersions());
				if (selectedVersion != null) {
				    resetArtifact.selectVersion(selectedVersion
					    .toString());
				} else {
				    throw new OverConstrainedVersionException(
					    " Unable to find a version in "
						    + resetArtifact
							    .getAvailableVersions()
						    + " to match the range "
						    + resetArtifact
							    .getVersionRange(),
					    resetArtifact);
				}
				fireEvent(
					ResolutionListener.SELECT_VERSION_FROM_RANGE,
					listeners, resetNodes[j]);
			    }
			}
		    }

		    // Conflict Resolution
		    // TODO: use as conflict resolver(s), chain

		    // TODO: should this be part of mediation?
		    // previous one is more dominant
		    ResolutionNode nearest;
		    ResolutionNode farthest;
		    if (previous.getDepth() <= node.getDepth()) {
			nearest = previous;
			farthest = node;
		    } else {
			nearest = node;
			farthest = previous;
		    }

		    if (checkScopeUpdate(farthest, nearest, listeners)) {
			// if we need to update scope of nearest to use farthest
			// scope, use the nearest version, but farthest scope
			nearest.disable();
			farthest.getArtifact().setVersion(
				nearest.getArtifact().getVersion());
			fireEvent(ResolutionListener.OMIT_FOR_NEARER,
				listeners, nearest, farthest.getArtifact());
		    } else {
			farthest.disable();
			fireEvent(ResolutionListener.OMIT_FOR_NEARER,
				listeners, farthest, nearest.getArtifact());
		    }
		}
	    }
	} else {
	    previousNodes = new ArrayList();
	    resolvedArtifacts.put(key, previousNodes);
	}
	previousNodes.add(node);

	if (node.isActive()) {
	    fireEvent(ResolutionListener.INCLUDE_ARTIFACT, listeners, node);
	}

	// don't pull in the transitive deps of a system-scoped dependency.
	if (node.isActive()
		&& !Artifact.SCOPE_SYSTEM.equals(node.getArtifact().getScope())) {
	    fireEvent(ResolutionListener.PROCESS_CHILDREN, listeners, node);
	    if (transitive) {
		Artifact parentArtifact = node.getArtifact();

		for (Iterator i = node.getChildrenIterator(); i.hasNext();) {
		    ResolutionNode child = (ResolutionNode) i.next();
		    if (!filter.include(child.getArtifact())) {
			continue;
		    }
		    /*
		     * rotgier: In case of regular dependencies provided scope
		     * is simply ignored (artifact versions specified there
		     * conflict with the ones of runtime deps)
		     */
		    if (Artifact.SCOPE_PROVIDED.equals(child.getArtifact()
			    .getScope())) {
			continue;
		    }
		    boolean isContinue = resolveChildNode(node, child, filter,
			    managedVersions, listeners, source, parentArtifact,
			    false);
		    if (isContinue) {
			continue;
		    }
		    recurse(originatingArtifact, child, resolvedArtifacts,
			    managedVersions, localRepository, child
				    .getRemoteRepositories(), source, filter,
			    listeners, true);
		}
		List runtimeDeps = getRuntimeDeps(node.getArtifact(),
			managedVersions);
		for (Object runtimeDepObj : runtimeDeps) {
		    DependencyNode runtimeDep = (DependencyNode) runtimeDepObj;
		    Artifact artifact = runtimeDep.getArtifact();
		    ResolutionNode childRuntime = new ResolutionNode(artifact,
			    node.getRemoteRepositories(), node);
		    /*
		     * rotgier: In case of runtime dependencies provided scope
		     * should be allowed
		     */
		    if (!filter.include(childRuntime.getArtifact())) {

			if (!Artifact.SCOPE_PROVIDED
				.equals(artifact.getScope())) {
			    continue;
			}
		    }
		    boolean isContinue = resolveChildNode(node, childRuntime,
			    filter, managedVersions, listeners, source,
			    parentArtifact, false);
		    if (isContinue) {
			continue;
		    }
		    recurse(originatingArtifact, childRuntime,
			    resolvedArtifacts, managedVersions,
			    localRepository, childRuntime
				    .getRemoteRepositories(), source, filter,
			    listeners, true);
		}
	    }
	    fireEvent(ResolutionListener.FINISH_PROCESSING_CHILDREN, listeners,
		    node);
	}
    }

    private String calculateDepKey(Dependency dep) {
	StringBuffer sb = new StringBuffer();
	sb.append(dep.getGroupId());
	sb.append(":");
	sb.append(dep.getArtifactId());
	sb.append(":");
	sb.append(dep.getType());
	if (dep.getClassifier() != null) {
	    sb.append(":");
	    sb.append(dep.getClassifier());
	}
	return sb.toString();
    }

    private List getRuntimeDeps(Artifact nodeArtifact,
	    ManagedVersionMap managedVersions) {
	try {
	    List runtimeDeps = new ArrayList();
	    Artifact pomArtifact = artifactFactory.createArtifact(nodeArtifact
		    .getGroupId(), nodeArtifact.getArtifactId(), nodeArtifact
		    .getVersion(), "", "pom");
	    MavenProject pomProject = mavenProjectBuilder.buildFromRepository(
		    pomArtifact, remoteRepositories, localRepository);
	    List profiles = pomProject.getModel().getProfiles();
	    if (profiles != null) {
		for (Object profileObj : profiles) {
		    Profile profile = (Profile) profileObj;
		    if (UAAL_RUNTIME_PROFILE.equals(profile.getId())) {
			List deps = profile.getDependencies();
			if (deps != null) {
			    for (Object depObj : deps) {
				Dependency dep = (Dependency) depObj;
				String depKey = calculateDepKey(dep);

				String depVersion = dep.getVersion();
				String depScope = dep.getScope();

				if (managedVersions.containsKey(depKey)) {
				    Artifact managedArtifact = (Artifact) managedVersions
					    .get(depKey);
				    if (managedArtifact.getVersion() != null
					    && (depVersion == null)) {
					depVersion = managedArtifact
						.getVersion();
				    }
				    if (managedArtifact.getScope() != null
					    && (depScope == null)) {
					depScope = managedArtifact.getScope();
				    }
				}
				Artifact runtimeArtifact = artifactFactory
					.createArtifact(dep.getGroupId(), dep
						.getArtifactId(), depVersion,
						depScope, dep.getType());
				DependencyNode runtimeDepNode = new DependencyNode(
					runtimeArtifact);
				runtimeDeps.add(runtimeDepNode);
			    }
			}
		    }
		}
	    }
	    return runtimeDeps;
	} catch (ProjectBuildingException e) {
	    throw new RuntimeException(e);
	}
    }

    /**
     * Get the map of managed versions, removing the originating artifact if it
     * is also in managed versions
     * 
     * @param originatingArtifact
     *            artifact we are processing
     * @param managedVersions
     *            original managed versions
     */
    private ManagedVersionMap getManagedVersionsMap(
	    Artifact originatingArtifact, Map managedVersions) {
	ManagedVersionMap versionMap;
	if (managedVersions != null
		&& managedVersions instanceof ManagedVersionMap) {
	    versionMap = (ManagedVersionMap) managedVersions;
	} else {
	    versionMap = new ManagedVersionMap(managedVersions);
	}

	/*
	 * remove the originating artifact if it is also in managed versions to
	 * avoid being modified during resolution
	 */
	Artifact managedOriginatingArtifact = (Artifact) versionMap
		.get(originatingArtifact.getDependencyConflictId());
	if (managedOriginatingArtifact != null) {
	    // TODO we probably want to warn the user that he is building an
	    // artifact with
	    // different values than in dependencyManagement
	    if (managedVersions instanceof ManagedVersionMap) {
		/*
		 * avoid modifying the managedVersions parameter creating a new
		 * map
		 */
		versionMap = new ManagedVersionMap(managedVersions);
	    }
	    versionMap.remove(originatingArtifact.getDependencyConflictId());
	}

	return versionMap;
    }

    public List buildDependencyTree(ArtifactRepository repository,
	    ArtifactFactory factory, ArtifactMetadataSource metadataSource,
	    ArtifactFilter filter, MavenProject... projects)
	    throws DependencyTreeBuilderException,
	    ArtifactMetadataRetrievalException {
	DependencyTreeResolutionListener listener = new DependencyTreeResolutionListener(
		filter);
	Map resolvedArtifacts = new LinkedHashMap();
	for (MavenProject project : projects) {
	    try {
		Map managedVersions = project.getManagedVersionMap();

		Set dependencyArtifacts = project.getDependencyArtifacts();

		Artifact originatingArtifact = project.getArtifact();

		if (dependencyArtifacts == null) {
		    dependencyArtifacts = project.createArtifacts(factory,
			    null, null);
		}

		if ("bundle".equals(originatingArtifact.getType())) {
		    Artifact changeArtifact = artifactFactory.createArtifact(
			    originatingArtifact.getGroupId(),
			    originatingArtifact.getArtifactId(),
			    originatingArtifact.getVersion(),
			    originatingArtifact.getScope(), "jar");
		    originatingArtifact = changeArtifact;
		}
		ResolutionNode root = new ResolutionNode(originatingArtifact,
			remoteRepositories);

		root.addDependencies(dependencyArtifacts, remoteRepositories,
			filter);

		ManagedVersionMap versionMap = getManagedVersionsMap(
			originatingArtifact, managedVersions);

		recurse(originatingArtifact, root, resolvedArtifacts,
			versionMap, localRepository, remoteRepositories,
			metadataSource, filter, Collections
				.singletonList(listener), false);
	    } catch (ArtifactResolutionException exception) {
		throw new DependencyTreeBuilderException(
			"Cannot build project dependency tree", exception);
	    } catch (InvalidDependencyVersionException e) {
		throw new DependencyTreeBuilderException(
			"Invalid dependency version for artifact "
				+ project.getArtifact());
	    }
	}
	return listener.getRootNodes();
    }

}
