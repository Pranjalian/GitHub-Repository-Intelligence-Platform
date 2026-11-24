package com.example.pay.service;

import com.example.pay.domain.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentServiceTests {

	@Autowired
	PaymentService service;

	@Test
	void computesTheTotal() {
		service.calculatePayment(new Order(1L, 1000, "ann"));
	}

	@Test
	void computesTheTotalWithACoupon() {
		service.calculatePayment(new Order(2L, 1000, "bob"), "SPRING");
	}

}
