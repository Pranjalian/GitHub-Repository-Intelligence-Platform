package com.example.pay.service;

import com.example.pay.domain.Order;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

	private final PaymentService payments;

	public OrderService(PaymentService payments) {
		this.payments = payments;
	}

	public long checkout(Order order) {
		return payments.calculatePayment(order);
	}

}
