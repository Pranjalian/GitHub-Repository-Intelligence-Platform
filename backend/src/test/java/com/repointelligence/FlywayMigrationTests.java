package com.repointelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.support.EmbeddedPostgresConfig;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(EmbeddedPostgresConfig.class)
@SpringBootTest
class FlywayMigrationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void allMigrationsAppliedOnStartup() {
		MigrationInfo[] pending = flyway.info().pending();
		assertThat(pending).isEmpty();
		assertThat(flyway.info().applied()).isNotEmpty();
	}

	@Test
	void schemaHistoryRecordsSuccessfulBaseline() {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

		assertThat(rows).isNotEmpty();
		assertThat(rows).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));
		assertThat(rows.get(0).get("version")).isEqualTo("1");
	}

}
