package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

	private final OrderService service;

	public OrderController(OrderService service) {
		this.service = service;
	}

	@GetMapping("/orders/total")
	public double total() {
		return service.total(10, 2.5, "EUR", "standard", true, false, 3);
	}

}
