package com.repointelligence.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward, e.g. to refill a rate-limit bucket without waiting. */
public class MutableClock extends Clock {

	private Instant now;

	public MutableClock(Instant start) {
		this.now = start;
	}

	public synchronized void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public synchronized Instant instant() {
		return now;
	}

}
