package com.repointelligence.repository.api;

/**
 * The dashboard KPI tiles. {@code openFindings} is null when no repository has an analyzed finding
 * category (the tile then says "Unavailable", never 0).
 */
public record RepositoryStatsDTO(long repositoriesTotal, long repositoriesAnalyzed, long indexedForSearch,
		Long openFindings, long activeRuns) {
}
