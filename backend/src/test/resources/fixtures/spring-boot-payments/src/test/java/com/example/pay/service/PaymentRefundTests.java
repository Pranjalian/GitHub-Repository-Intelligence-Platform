package com.example.pay.service;

import com.example.pay.domain.Order;
import org.junit.jupiter.api.Test;

class PaymentRefundTests {

	@Test
	void refunds() {
		PaymentService service = new PaymentService(null, null);
		service.refund(new Order(3L, 500, "cy"));
	}

}
