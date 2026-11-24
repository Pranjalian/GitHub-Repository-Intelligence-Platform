package com.repointelligence.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.analysis.packs.java.JavaImpactAnalyzer;
import com.repointelligence.analysis.spi.ImpactGraph;
import com.repointelligence.analysis.spi.ImpactQuery;
import com.repointelligence.analysis.spi.ImpactResult;
import com.repointelligence.analysis.spi.ImpactResult.AffectedComponent;
import com.repointelligence.common.model.Confidence;
import com.repointelligence.impact.graph.SnapshotImpactGraph;
import com.repointelligence.support.EmbeddedPostgresConfig;
import com.repointelligence.support.GraphSeeder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Step 5.2: the Java impact analyzer on a hand-drawn graph of the plan's example, {@code
 * PaymentService.calculatePayment()}: expected callers, endpoints, repositories, modules and tests, with the
 * confidence and limitations that go with them.
 */
@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class JavaImpactAnalyzerTests {

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NamedParameterJdbcTemplate named;

	@Autowired
	private PlatformTransactionManager transactions;

	private final JavaImpactAnalyzer analyzer = new JavaImpactAnalyzer();

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM repository");
	}

	/** The shop: web -> core. admin (a third module) depends on web. */
	private GraphSeeder shop() {
		GraphSeeder s = GraphSeeder.create(em, transactions, jdbc);
		UUID paymentController = s.type("shop.web.PaymentController", "CLASS", "web", "RestController");
		UUID orderController = s.type("shop.web.OrderController", "CLASS", "web", "RestController");
		UUID paymentService = s.type("shop.service.PaymentService", "CLASS", "core", "Service");
		UUID orderService = s.type("shop.service.OrderService", "CLASS", "core", "Service");
		UUID billingJob = s.type("shop.service.BillingJob", "CLASS", "core", "Component");
		UUID legacyJob = s.type("shop.service.LegacyJob", "CLASS", "core", "Component");
		UUID paymentRepository = s.type("shop.repository.PaymentRepository", "INTERFACE", "core", "");
		UUID payment = s.type("shop.domain.Payment", "CLASS", "core", "Entity");
		UUID auditService = s.type("shop.service.AuditService", "CLASS", "core", "Service");
		UUID reportService = s.type("shop.service.ReportService", "CLASS", "core", "Service");
		s.layer(paymentController, "CONTROLLER");
		s.layer(orderController, "CONTROLLER");
		s.layer(paymentService, "SERVICE");
		s.layer(orderService, "SERVICE");
		s.layer(paymentRepository, "REPOSITORY");
		s.layer(payment, "ENTITY");

		UUID pay = s.method("shop.web.PaymentController", "pay", "Order");
		UUID place = s.method("shop.web.OrderController", "place", "Order");
		UUID calculate = s.method("shop.service.PaymentService", "calculatePayment", "Order");
		UUID calculateWithCoupon = s.method("shop.service.PaymentService", "calculatePayment", "Order, Coupon");
		UUID refund = s.method("shop.service.PaymentService", "refund", "Order");
		UUID checkout = s.method("shop.service.OrderService", "checkout", "Order");
		UUID run = s.method("shop.service.BillingJob", "run", "");
		UUID go = s.method("shop.service.LegacyJob", "go", "");
		UUID save = s.method("shop.repository.PaymentRepository", "save", "Payment");
		UUID audit = s.method("shop.service.AuditService", "audit", "");
		UUID report = s.method("shop.service.ReportService", "report", "");

		s.edge(pay, calculate, "CALLS");
		s.edge(checkout, calculate, "CALLS");
		s.edge(place, checkout, "CALLS");
		s.edge(run, checkout, "CALLS");
		s.edge(calculate, save, "CALLS");
		s.edge(refund, save, "CALLS");
		s.edge(paymentController, paymentService, "INJECTS");
		s.edge(orderService, paymentService, "INJECTS");
		s.edge(orderController, orderService, "INJECTS");
		s.edge(billingJob, orderService, "INJECTS");
		s.edge(paymentService, paymentRepository, "INJECTS");
		// a cycle between two services that both call the target's caller
		s.edge(audit, report, "CALLS");
		s.edge(report, audit, "CALLS");
		s.edge(audit, checkout, "CALLS");
		s.ambiguousCall(go, "shop.service.PaymentService#calculatePayment(Order)",
				"shop.service.PaymentService#calculatePayment(Order, Coupon)");
		s.edge(calculateWithCoupon, save, "CALLS");

		s.entityRelationship(paymentRepository, payment, "REPOSITORY_FOR", null);
		s.endpoint(paymentController, pay, "POST", "/payments");
		s.endpoint(orderController, place, "POST", "/orders");
		s.moduleDependency("web", "core");
		s.moduleDependency("admin", "web");

		s.testExecution(true);
		s.testReference("shop.service.PaymentServiceTests", "computesTotal", "shop.service.PaymentService", "calculatePayment", 1,
				"CALL");
		s.testReference("shop.service.PaymentServiceTests", null, "shop.service.PaymentService", null, null, "TYPE_USE");
		s.testReference("shop.service.PaymentRefundTests", "refunds", "shop.service.PaymentService", "refund", 1, "CALL");
		s.testReference("shop.web.OrderControllerTests", "places", "shop.web.OrderController", "place", 1, "CALL");
		s.conventionTest("shop.service.PaymentServiceTests", "shop.service.PaymentService");
		s.conventionTest("shop.service.OrderServiceTests", "shop.service.OrderService");
		return s;
	}

	private ImpactResult analyze(GraphSeeder seed, UUID target, int depth) {
		ImpactGraph graph = new SnapshotImpactGraph(named, seed.snapshotId(), seed.runId(), 20_000);
		return analyzer.analyze(new ImpactQuery(target, depth, 300), graph);
	}

	private static AffectedComponent find(ImpactResult result, String qualified) {
		return result.components()
			.stream()
			.filter(c -> c.qualifiedName().equals(qualified))
			.findFirst()
			.orElseThrow(() -> new AssertionError(qualified + " is not affected: " + result.components().stream().map(AffectedComponent::qualifiedName).toList()));
	}

	@Test
	void aMethodsCallersEndpointsRepositoriesModulesAndTestsAreFound() {
		GraphSeeder s = shop();

		ImpactResult result = analyze(s, s.id("shop.service.PaymentService#calculatePayment(Order)"), 3);

		assertThat(result.targetKind()).isEqualTo("METHOD");
		assertThat(result.targetLayer()).isEqualTo("SERVICE");
		assertThat(result.components()).extracting(AffectedComponent::qualifiedName)
			.containsExactlyInAnyOrder("shop.web.PaymentController#pay(Order)", "shop.service.OrderService#checkout(Order)",
					"shop.service.LegacyJob#go()", "shop.web.OrderController#place(Order)", "shop.service.BillingJob#run()",
					"shop.service.AuditService#audit()", "shop.service.ReportService#report()");
		AffectedComponent pay = find(result, "shop.web.PaymentController#pay(Order)");
		assertThat(pay.depth()).isEqualTo(1);
		assertThat(pay.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(pay.viaId()).isEqualTo(s.id("shop.service.PaymentService#calculatePayment(Order)"));
		assertThat(pay.relationships()).containsExactly("CALLS");
		assertThat(pay.layer()).isEqualTo("CONTROLLER");
		assertThat(pay.reason()).startsWith("Calls");
		assertThat(pay.filePath()).endsWith("PaymentController.java");
		AffectedComponent place = find(result, "shop.web.OrderController#place(Order)");
		assertThat(place.depth()).isEqualTo(2);
		assertThat(place.viaId()).isEqualTo(s.id("shop.service.OrderService#checkout(Order)"));
		// the overload with a coupon and the refund are not callers of this method
		assertThat(result.components()).extracting(AffectedComponent::qualifiedName)
			.noneMatch(n -> n.contains("calculatePayment") || n.contains("refund"));

		// an ambiguous call whose candidates include this method: possible, not detected
		AffectedComponent legacy = find(result, "shop.service.LegacyJob#go()");
		assertThat(legacy.confidence()).isEqualTo(Confidence.POSSIBLE);
		assertThat(legacy.reason()).contains("overloads could not be told apart");

		assertThat(result.endpoints()).extracting(e -> e.httpMethod() + " " + e.route())
			.containsExactly("POST /orders", "POST /payments");
		assertThat(result.endpoints()).allMatch(e -> e.confidence() == Confidence.DETECTED);

		assertThat(result.relatedData()).extracting(r -> r.kind() + " " + r.qualifiedName())
			.containsExactly("ENTITY shop.domain.Payment", "REPOSITORY shop.repository.PaymentRepository");

		assertThat(result.modules()).extracting(m -> m.reason() + ":" + m.modulePath())
			.containsExactly("CONTAINS_AFFECTED:core", "CONTAINS_AFFECTED:web", "DEPENDS_ON_TARGET_MODULE:admin");
		assertThat(result.modules().get(2).depth()).isEqualTo(2);

		assertThat(result.tests()).extracting(t -> t.testSymbol() + " " + t.relation() + " " + t.confidence())
			.containsExactly("shop.service.PaymentServiceTests DIRECT_CALL DETECTED",
					"shop.service.PaymentRefundTests TYPE_REFERENCE POSSIBLE",
					"shop.web.OrderControllerTests AFFECTED_COMPONENT LIKELY",
					"shop.service.OrderServiceTests AFFECTED_COMPONENT POSSIBLE");
		assertThat(result.tests().get(0).methods()).containsExactly("computesTotal");
		assertThat(result.truncated()).isFalse();
	}

	@Test
	void theDepthLimitIsRespectedAndCyclesTerminate() {
		GraphSeeder s = shop();

		ImpactResult shallow = analyze(s, s.id("shop.service.PaymentService#calculatePayment(Order)"), 1);

		assertThat(shallow.components()).extracting(AffectedComponent::qualifiedName)
			.containsExactlyInAnyOrder("shop.web.PaymentController#pay(Order)", "shop.service.OrderService#checkout(Order)",
					"shop.service.LegacyJob#go()");
		assertThat(shallow.maxDepthReached()).isEqualTo(1);
		ImpactResult deep = analyze(s, s.id("shop.service.PaymentService#calculatePayment(Order)"), 6);
		assertThat(deep.components()).extracting(AffectedComponent::id).doesNotHaveDuplicates();
		assertThat(find(deep, "shop.service.ReportService#report()").depth()).isEqualTo(3);
	}

	@Test
	void aTypeTargetListsEveryDependentTypeAndTheEndpointsOfAffectedControllers() {
		GraphSeeder s = shop();

		ImpactResult result = analyze(s, s.id("shop.service.PaymentService"), 3);

		assertThat(result.targetKind()).isEqualTo("TYPE");
		assertThat(result.components()).extracting(AffectedComponent::qualifiedName)
			.containsExactlyInAnyOrder("shop.web.PaymentController", "shop.service.OrderService", "shop.web.OrderController",
					"shop.service.BillingJob", "shop.service.AuditService", "shop.service.ReportService");
		AffectedComponent controller = find(result, "shop.web.PaymentController");
		assertThat(controller.relationships()).containsExactly("CALLS", "INJECTS");
		assertThat(controller.confidence()).isEqualTo(Confidence.DETECTED);
		assertThat(result.endpoints()).extracting(e -> e.route()).containsExactly("/orders", "/payments");
		// for a type, any call of one of its methods is direct; tests of its dependents come after
		assertThat(result.tests()).extracting(t -> t.testSymbol() + " " + t.relation())
			.startsWith("shop.service.PaymentRefundTests DIRECT_CALL", "shop.service.PaymentServiceTests DIRECT_CALL");
	}

	/** Changing an implementation: callers of the interface method may reach it, but only as POSSIBLE. */
	@Test
	void dynamicDispatchIsPossibleNeverDetected() {
		GraphSeeder s = GraphSeeder.create(em, transactions, jdbc);
		UUID discounts = s.type("shop.Discounts", "INTERFACE", "core", "");
		UUID loyalty = s.type("shop.LoyaltyDiscounts", "CLASS", "core", "");
		s.clazz("shop.CartService");
		UUID declared = s.method("shop.Discounts", "apply", "Order");
		UUID implemented = s.method("shop.LoyaltyDiscounts", "apply", "Order");
		s.method("shop.LoyaltyDiscounts", "apply", "Order, Coupon");
		UUID total = s.method("shop.CartService", "total", "");
		UUID summary = s.method("shop.CartService", "summary", "");
		s.edge(loyalty, discounts, "IMPLEMENTS");
		s.edge(total, declared, "CALLS");
		s.edge(summary, total, "CALLS");
		s.testExecution(true);

		ImpactResult result = analyze(s, implemented, 4);

		assertThat(result.components()).extracting(AffectedComponent::qualifiedName)
			.containsExactlyInAnyOrder("shop.Discounts#apply(Order)", "shop.CartService#total()", "shop.CartService#summary()");
		assertThat(result.components()).allMatch(c -> c.confidence() == Confidence.POSSIBLE);
		assertThat(find(result, "shop.Discounts#apply(Order)").relationships()).containsExactly("OVERRIDES");
		assertThat(find(result, "shop.CartService#total()").depth()).isEqualTo(2);
		assertThat(find(result, "shop.CartService#summary()").depth()).isEqualTo(3);
		assertThat(result.limitations()).anyMatch(l -> l.startsWith("Dynamic dispatch"));
	}

	@Test
	void unresolvedEdgesAndMissingTestDataAreStatedAsLimitations() {
		GraphSeeder s = GraphSeeder.create(em, transactions, jdbc);
		s.clazz("a.A");
		UUID caller = s.method("a.A", "run", "");
		UUID callee = s.method("a.A", "work", "");
		s.edge(caller, callee, "CALLS");
		jdbc.update("INSERT INTO dependency_edge (snapshot_id, run_id, source_symbol_id, target_unresolved_name, "
				+ "relationship_type, resolution, evidence) VALUES (?, ?, ?, 'lib.Thing#go', 'CALLS', 'UNRESOLVED', '{}')",
				s.snapshotId(), s.runId(), caller);
		jdbc.update("""
				INSERT INTO analyzer_execution (run_id, snapshot_id, analyzer_id, analyzer_version, category, status, config_summary)
				VALUES (?, ?, 'spring-architecture-analyzer', '1.0.0', 'ARCHITECTURE', 'COMPLETED', CAST(? AS jsonb))
				""", s.runId(), s.snapshotId(), "{\"callsNotResolvable\": 17}");
		s.testExecution(false);

		ImpactResult result = analyze(s, callee, 3);

		assertThat(result.limitations()).anyMatch(l -> l.contains("1 of 2 relationships") && l.contains("unresolved or ambiguous"))
			.anyMatch(l -> l.contains("17 calls") && l.contains("receiver whose type is not known"))
			.anyMatch(l -> l.contains("before test references were recorded"))
			.anyMatch(l -> l.contains("Reflection, runtime proxies"))
			.anyMatch(l -> l.contains("Dynamic dispatch is approximated"));
		assertThat(result.components()).hasSize(1);
		assertThat(result.tests()).isEmpty();
	}

	@Test
	void resultsAreCappedAndSayTheyAre() {
		GraphSeeder s = GraphSeeder.create(em, transactions, jdbc);
		s.clazz("a.Hub");
		UUID target = s.method("a.Hub", "go", "");
		for (int i = 0; i < 6; i++) {
			s.clazz("a.Caller" + i);
			s.edge(s.method("a.Caller" + i, "c", ""), target, "CALLS");
		}
		s.testExecution(true);
		ImpactGraph graph = new SnapshotImpactGraph(named, s.snapshotId(), s.runId(), 20_000);

		ImpactResult result = analyzer.analyze(new ImpactQuery(target, 2, 4), graph);

		assertThat(result.components()).hasSize(4);
		assertThat(result.totalDependents()).isEqualTo(6);
		assertThat(result.truncated()).isTrue();
		assertThat(result.limitations()).anyMatch(l -> l.contains("capped"));
		assertThat(List.of(result.components().get(0).depth())).containsExactly(1);
	}

}
