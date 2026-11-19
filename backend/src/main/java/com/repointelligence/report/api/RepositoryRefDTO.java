package com.repointelligence.report.api;

import java.util.UUID;

public record RepositoryRefDTO(UUID id, String owner, String name, String url) {
}
