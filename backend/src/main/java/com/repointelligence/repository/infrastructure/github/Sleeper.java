package com.repointelligence.repository.infrastructure.github;

import java.time.Duration;

/** Abstraction over sleeping so backoff can be tested without waiting. */
@FunctionalInterface
public interface Sleeper {

	void sleep(Duration duration) throws InterruptedException;

}
