package com.repointelligence;

import com.repointelligence.support.EmbeddedPostgresConfig;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application against an embedded PostgreSQL, so no local database install is needed.
 * Start with {@code mvnw spring-boot:test-run}. Data is discarded when the process exits.
 */
public class TestRepoIntelligenceApplication {

	public static void main(String[] args) {
		SpringApplication.from(RepoIntelligenceApplication::main).with(EmbeddedPostgresConfig.class).run(args);
	}

}
