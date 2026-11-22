package com.repointelligence.ai.provider;

/**
 * Input to routing.
 *
 * @param feature feature id such as {@code explain-class}
 * @param preferredProvider provider chosen by the user, or null
 * @param hostedPermitted whether the repository's policy allows sending excerpts to a hosted provider
 */
public record RoutingContext(String feature, String preferredProvider, boolean hostedPermitted) {
}
