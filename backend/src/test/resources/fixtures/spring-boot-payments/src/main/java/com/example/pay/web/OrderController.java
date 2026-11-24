package com.example.pay.web;

import com.example.pay.domain.Order;
import com.example.pay.service.OrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

	private final OrderService orders;

	public OrderController(OrderService orders) {
		this.orders = orders;
	}

	@PostMapping
	public long place(@RequestBody Order order) {
		return orders.checkout(order);
	}

}
