package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderServiceTests {

	private final OrderService service = new OrderService();

	@Test
	void standardShipping() {
		assertEquals(20.0, service.total(10, 2.0, "EUR", "standard", false, false, 0));
	}

	@Test
	void pickup() {
		assertEquals(18.0, service.total(10, 2.0, "EUR", "pickup", false, false, 2));
	}

	// @Test
	void disabledForNow() {
	}

}
