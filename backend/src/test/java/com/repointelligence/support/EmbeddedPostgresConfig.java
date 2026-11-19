package com.repointelligence.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared real PostgreSQL for tests, started from embedded binaries (no Docker). Import into any
 * test that touches the database. One server is started per JVM and reused across Spring test
 * contexts; it is stopped by a shutdown hook.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresConfig {

	private static volatile EmbeddedPostgres server;

	@Bean
	DataSource dataSource() {
		return server().getPostgresDatabase();
	}

	private static EmbeddedPostgres server() {
		if (server == null) {
			synchronized (EmbeddedPostgresConfig.class) {
				if (server == null) {
					try {
						EmbeddedPostgres started = EmbeddedPostgres.builder().start();
						Runtime.getRuntime().addShutdownHook(new Thread(() -> {
							try {
								started.close();
							}
							catch (IOException ignored) {
								// best effort on JVM exit
							}
						}, "embedded-postgres-shutdown"));
						server = started;
					}
					catch (IOException ex) {
						throw new UncheckedIOException("Failed to start embedded PostgreSQL", ex);
					}
				}
			}
		}
		return server;
	}

}
