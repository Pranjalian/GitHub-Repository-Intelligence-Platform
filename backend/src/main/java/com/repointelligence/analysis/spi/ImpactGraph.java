package com.repointelligence.analysis.spi;

import com.repointelligence.common.model.Confidence;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only, snapshot-scoped access to the stored dependency graph (MVP 2 symbols and edges, endpoints,
 * layers, entities, modules, and MVP 5 test references) for impact analyzers. The core implements it with
 * bounded recursive queries; an analyzer applies language-specific rules on top and never sees a
 * repository or an entity manager. Every answer is limited to the one snapshot and analysis run that the
 * graph was opened for.
 */
public interface ImpactGraph {

	/** A symbol of the snapshot. {@code type} is the MVP 2 symbol type name (CLASS, METHOD, ...). */
	record Node(UUID id, String type, String fqn, String signature, UUID parentId, String filePath, Integer startLine,
			Integer endLine, List<String> annotations, List<String> modifiers, String modulePath) {

		public Node {
			annotations = annotations == null ? List.of() : List.copyOf(annotations);
			modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
		}

		public boolean isType() {
			return Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION").contains(type);
		}

		public boolean isCallable() {
			return type.equals("METHOD") || type.equals("CONSTRUCTOR");
		}

		/** The enclosing type's name for a member, else the symbol's own name. */
		public String ownerName() {
			int hash = fqn.indexOf('#');
			return hash >= 0 ? fqn.substring(0, hash) : fqn;
		}

		/** The member name of a method, constructor or field; the simple name for a type. */
		public String simpleName() {
			int hash = fqn.indexOf('#');
			if (hash >= 0) {
				return fqn.substring(hash + 1);
			}
			return fqn.substring(fqn.lastIndexOf('.') + 1);
		}

		/** Number of declared parameters of a method or constructor, from its signature. */
		public int parameterCount() {
			if (signature == null) {
				return -1;
			}
			int open = signature.indexOf('(');
			int close = signature.lastIndexOf(')');
			if (open < 0 || close < open) {
				return -1;
			}
			String inside = signature.substring(open + 1, close).trim();
			if (inside.isEmpty()) {
				return 0;
			}
			int depth = 0;
			int count = 1;
			for (char c : inside.toCharArray()) {
				if (c == '<') {
					depth++;
				}
				else if (c == '>') {
					depth--;
				}
				else if (c == ',' && depth == 0) {
					count++;
				}
			}
			return count;
		}

		public boolean isVarArgs() {
			return signature != null && signature.contains("...");
		}

	}

	/** The unit a traversal walks: single methods (CALLS edges) or whole types (every relationship). */
	enum Granularity {

		METHOD, TYPE

	}

	/**
	 * A node reached by a reverse traversal ("who depends on the seeds"), one row per distinct way it was reached.
	 *
	 * @param node the dependent
	 * @param depth hops from the nearest seed (1 = direct dependent)
	 * @param via the node it depends on (a seed at depth 1)
	 * @param relationships the relationship types of that hop (CALLS, INJECTS, ...)
	 * @param file where the relationship was read, with {@code line}
	 */
	record Reach(UUID node, int depth, UUID via, List<String> relationships, String file, Integer line) {
	}

	record Traversal(List<Reach> reached, boolean truncated) {

		public static Traversal empty() {
			return new Traversal(List.of(), false);
		}

	}

	record LayerOf(UUID symbolId, String layer, Confidence confidence) {
	}

	record EndpointOf(String httpMethod, String route, boolean routeResolved, UUID controllerId, UUID handlerId,
			String filePath, Integer line) {
	}

	/** A JPA relationship or repository-to-entity mapping between two types of the snapshot. */
	record EntityLinkOf(UUID sourceId, UUID targetId, String relationship, String field) {
	}

	record ModuleLink(String sourceModule, String targetModule, int depth, List<String> evidencePaths) {
	}

	/** A reference from a test class to a production type (and member), read from the test source. */
	record TestReferenceOf(String testSymbol, String testFilePath, String testMethod, String referencedType,
			String referencedMember, Integer argumentCount, String kind, Integer line, Confidence confidence) {
	}

	/** A test class mapped to a production class by naming convention. */
	record ConventionTest(String testSymbol, String testFilePath, String productionSymbol, Integer line) {
	}

	/** A type that a symbol directly depends on, by one relationship (members are lifted to their type). */
	record DependencyOf(UUID sourceId, UUID typeId, String relationship) {
	}

	/** A call that could not be resolved to one method but may be this one (ambiguous overloads). */
	record AmbiguousCaller(UUID callerId, String file, Integer line) {
	}

	/**
	 * How complete the graph is.
	 *
	 * @param testReferencesAvailable false when the test analyzer of this run did not record references (an
	 * older run), so "no tests found" cannot be told from "tests not read"
	 */
	record GraphStats(long symbols, long edges, long unresolvedEdges, long ambiguousEdges, long callsNotResolvable,
			boolean testReferencesAvailable, boolean testsAnalysed) {
	}

	Optional<Node> node(UUID id);

	Map<UUID, Node> nodes(Collection<UUID> ids);

	/** Methods, constructors, fields and nested types of a type. */
	List<Node> membersOf(UUID typeId);

	/** Project types that this type extends or implements, transitively, nearest first. */
	List<Node> supertypes(UUID typeId, int maxDepth);

	/** Project types that extend or implement this type, transitively, nearest first. */
	List<Node> subtypes(UUID typeId, int maxDepth);

	/**
	 * Everything that depends on the seeds, to {@code maxDepth} hops. Cycles never loop and each dependent
	 * keeps its shortest depth; the result is capped and says so when it was.
	 */
	Traversal dependents(Collection<UUID> seeds, Granularity granularity, int maxDepth);

	/**
	 * Types the symbols directly depend on over resolved edges (imports excluded); the targets of members are
	 * lifted to their enclosing type. With {@code includeMembers} the members of a type count as the type.
	 */
	List<DependencyOf> directDependencies(Collection<UUID> sourceIds, boolean includeMembers);

	/** Methods with an ambiguous call whose candidates include the given method. */
	List<AmbiguousCaller> ambiguousCallers(Node method);

	Map<UUID, LayerOf> layers(Collection<UUID> symbolIds);

	/** Endpoints whose controller type or handler method is one of the ids. */
	List<EndpointOf> endpoints(Collection<UUID> controllerOrHandlerIds);

	/** JPA relationships and repository mappings in which one of the types takes part, either side. */
	List<EntityLinkOf> entityLinks(Collection<UUID> typeIds);

	/** Modules that (transitively) depend on the module, by the Maven module dependencies of the snapshot. */
	List<ModuleLink> moduleDependents(String modulePath, int maxDepth);

	List<TestReferenceOf> testReferences(Collection<String> typeFqns);

	List<ConventionTest> conventionTests(Collection<String> typeFqns);

	GraphStats stats();

}
