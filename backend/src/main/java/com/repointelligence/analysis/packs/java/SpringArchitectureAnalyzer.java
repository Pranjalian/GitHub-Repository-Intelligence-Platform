package com.repointelligence.analysis.packs.java;

import com.repointelligence.analysis.packs.java.architecture.JavaProjectModel;
import com.repointelligence.analysis.packs.java.architecture.ModuleLayout;
import com.repointelligence.analysis.packs.java.architecture.SpringDiscovery;
import com.repointelligence.analysis.packs.java.architecture.SymbolGraphBuilder;
import com.repointelligence.analysis.packs.java.architecture.SymbolGraphBuilder.Graph;
import com.repointelligence.analysis.packs.java.architecture.TypeResolver;
import com.repointelligence.analysis.packs.java.maven.MavenPomParser;
import com.repointelligence.analysis.packs.java.maven.MavenReactor;
import com.repointelligence.analysis.spi.AnalysisContext;
import com.repointelligence.analysis.spi.AnalyzerDescriptor;
import com.repointelligence.analysis.spi.AnalyzerResult;
import com.repointelligence.analysis.spi.Applicability;
import com.repointelligence.analysis.spi.ArchitectureAnalyzer;
import com.repointelligence.analysis.spi.ArchitectureResult;
import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.CapabilityLevel;
import com.repointelligence.repository.domain.FileType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Architecture discovery for Java and Spring Boot. It parses the production sources with JavaParser
 * (never compiling or running them), builds the symbol graph and reads Maven module relationships.
 * Results are returned, never persisted here.
 */
@Component
public class SpringArchitectureAnalyzer implements ArchitectureAnalyzer {

	public static final String ID = "spring-architecture-analyzer";

	private static final int MAX_WARNINGS = 20;

	private static final SpringDiscovery.Rules RULES = SpringDiscovery.Rules.load();

	private static final AnalyzerDescriptor DESCRIPTOR = AnalyzerDescriptor
		.builder(ID, "Spring Architecture Analyzer", "1.0.0", AnalysisCategory.ARCHITECTURE, CapabilityLevel.DEEP)
		.languages("Java")
		.frameworks("Spring Boot")
		.description("Parses Java sources (JavaParser) into symbols and relationships (inheritance, imports, "
				+ "field/parameter/return types, dependency injection, method calls) and discovers Spring layers, REST "
				+ "endpoints with request/response types, JPA entities and relationships, repository-to-entity mappings, "
				+ "dependency cycles and design-pattern candidates, plus Maven module dependencies.")
		.limitations("There is no classpath: third-party types are not resolved. Edges to them keep the imported name and "
				+ "are labelled UNRESOLVED; they are never guessed.",
				"Edges to JDK types (java.*) and primitives are not recorded.",
				"Method calls are resolved only when the receiver's declared type is known from the source (fields, "
						+ "parameters, typed local variables, this/super, static calls); calls on chained expressions are not recorded.",
				"Reflection, runtime proxies, conditional beans, @Bean factory methods and XML configuration are not analysed.",
				"Test sources are not analysed unless the include-tests option is set.",
				"Only Maven module relationships are read; Gradle builds are not supported.",
				"Layers come from stereotype annotations and Spring Data interfaces (DETECTED), endpoint signatures (DTO, "
						+ "LIKELY) or package names only (LIKELY); plain @Component is at most POSSIBLE infrastructure.",
				"Request mappings declared on interfaces or inherited from superclasses, and functional (router) endpoints, "
						+ "are not discovered.",
				"Design patterns are candidates inferred from structure, never from names; they are POSSIBLE or LIKELY, "
						+ "except Spring Data repositories (DETECTED).")
		.build();

	@Override
	public AnalyzerDescriptor descriptor() {
		return DESCRIPTOR;
	}

	@Override
	public Applicability applicability(AnalysisContext context) {
		boolean java = context.files()
			.stream()
			.anyMatch(f -> f.extracted() && f.path().endsWith(".java") && f.fileType() != FileType.TEST);
		return java ? Applicability.applicable() : Applicability.notApplicable();
	}

	@Override
	public AnalyzerResult analyze(AnalysisContext context) {
		AnalyzerResult.Builder result = AnalyzerResult.builder();
		boolean includeTests = Boolean.parseBoolean(context.settings().option(ID, "include-tests", "false"));
		int maxSymbols = context.settings().intOption(ID, "max-symbols", 50_000);
		int maxEdges = context.settings().intOption(ID, "max-edges", 200_000);
		long maxFileBytes = context.settings().intOption(ID, "max-file-kb", 512) * 1024L;

		MavenReactor reactor = MavenPomParser.pomPaths(context.files()).isEmpty() ? null
				: MavenPomParser.parse(context.workspace(), context.files());
		ModuleLayout modules = ModuleLayout.of(reactor);
		JavaProjectModel model = JavaProjectModel.parse(context.workspace(), context.files(), includeTests, maxFileBytes,
				modules::modulePathOf, context.cancellation());
		TypeResolver resolver = new TypeResolver(model);
		Graph graph = new SymbolGraphBuilder(model, resolver, maxSymbols, maxEdges).build(context.cancellation());
		context.cancellation().throwIfCancelled();
		SpringDiscovery.Discovery discovery = new SpringDiscovery(model, resolver, graph, RULES).discover();
		discovery.findings().forEach(result::finding);

		List<String> warnings = model.warnings();
		warnings.stream().limit(MAX_WARNINGS).forEach(result::warning);
		if (warnings.size() > MAX_WARNINGS) {
			result.warning((warnings.size() - MAX_WARNINGS) + " more files could not be parsed or were skipped.");
		}
		if (reactor != null) {
			reactor.warnings().forEach(result::warning);
		}
		if (graph.symbolsTruncated()) {
			result.limitation("Only the first " + maxSymbols + " symbols were kept (types first); members beyond the cap "
					+ "and their edges are missing.");
		}
		if (graph.edgesTruncated()) {
			result.limitation("Only the first " + maxEdges + " relationships were kept.");
		}
		// unresolved and ambiguous counts are in the config summary; the architecture report states them once
		SymbolGraphBuilder.Stats stats = graph.stats();

		if (discovery.unresolvedRoutes() > 0) {
			result.limitation(discovery.unresolvedRoutes() + " endpoint paths use expressions that are not compile-time "
					+ "constants of the project; they are shown as written in braces.");
		}
		if (discovery.cyclesTruncated()) {
			result.limitation("Only the first 50 dependency cycles are reported.");
		}
		result.architecture(new ArchitectureResult(graph.symbols(), graph.edges(), discovery.endpoints(),
				discovery.entityRelations(), discovery.layers(), modules.dependencies(), discovery.patterns()));
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("javaFiles", model.files().size());
		summary.put("types", model.types().size());
		summary.put("symbols", graph.symbols().size());
		summary.put("edges", graph.edges().size());
		summary.put("resolvedEdges", stats.resolvedEdges());
		summary.put("unresolvedEdges", stats.unresolvedEdges());
		summary.put("ambiguousEdges", stats.ambiguousEdges());
		summary.put("callsNotResolvable", stats.callsNotResolvable());
		summary.put("mavenModules", modules.moduleCount());
		summary.put("layers", discovery.layers().size());
		summary.put("endpoints", discovery.endpoints().size());
		summary.put("entityRelations", discovery.entityRelations().size());
		summary.put("patternCandidates", discovery.patterns().size());
		summary.put("cycles", discovery.findings().size());
		summary.put("includeTests", includeTests);
		result.configSummary(summary);
		return result.build();
	}

}
