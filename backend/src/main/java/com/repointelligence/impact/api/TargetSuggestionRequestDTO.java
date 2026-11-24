package com.repointelligence.impact.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TargetSuggestionRequestDTO(@NotBlank @Size(max = 1000) String changeDescription, String provider) {
}
