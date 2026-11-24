package com.example.pay.service;

import com.example.pay.domain.Order;
import org.springframework.stereotype.Component;

@Component
public class BillingJob {

	private final OrderService orders;

	public BillingJob(OrderService orders) {
		this.orders = orders;
	}

	public void runNightly(Order order) {
		orders.checkout(order);
	}

}
