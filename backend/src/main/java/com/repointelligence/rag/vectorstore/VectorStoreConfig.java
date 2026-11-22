package com.repointelligence.rag.vectorstore;

import com.repointelligence.rag.RagProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chooses the vector store once at startup: {@code rag.retrieval.vector-store=auto} uses pgvector when the
 * extension is installed in the database (the V7 migration creates it when it can), else the in-process
 * store. Forcing {@code pgvector} without the extension fails startup with a clear message.
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

	private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

	@Bean
	VectorStore vectorStore(JdbcTemplate jdbc, RagProperties properties) {
		String mode = properties.retrieval().vectorStore().trim().toLowerCase(Locale.ROOT);
		boolean installed = pgvectorInstalled(jdbc);
		VectorStore store = switch (mode) {
			case "pgvector" -> {
				if (!installed) {
					throw new IllegalStateException(
							"rag.retrieval.vector-store=pgvector, but the pgvector extension is not installed in the database");
				}
				yield new PgVectorStore(jdbc);
			}
			case "in-process" -> new InProcessVectorStore(jdbc, properties.retrieval().maxInProcessVectors());
			case "auto" -> installed ? new PgVectorStore(jdbc)
					: new InProcessVectorStore(jdbc, properties.retrieval().maxInProcessVectors());
			default -> throw new IllegalStateException(
					"rag.retrieval.vector-store must be auto, pgvector or in-process, not " + mode);
		};
		log.info("Vector store: {} (pgvector extension {})", store.name(), installed ? "installed" : "not installed");
		return store;
	}

	static boolean pgvectorInstalled(JdbcTemplate jdbc) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
		return count != null && count > 0;
	}

}
