package com.example.pay.web;

import com.example.pay.domain.Order;
import org.junit.jupiter.api.Test;

class OrderControllerTests {

	@Test
	void placesAnOrder() {
		var controller = new OrderController(null);
		controller.place(new Order(4L, 700, "di"));
	}

}
