package com.example.pay.service;

import com.example.pay.domain.Order;
import com.example.pay.domain.Payment;
import com.example.pay.repository.PaymentRepository;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

	private final PaymentRepository repository;

	private final DiscountPolicy discounts;

	public PaymentService(PaymentRepository repository, DiscountPolicy discounts) {
		this.repository = repository;
		this.discounts = discounts;
	}

	public long calculatePayment(Order order) {
		long total = order.amountCents() - discounts.apply(order);
		repository.save(new Payment());
		return total;
	}

	public long calculatePayment(Order order, String coupon) {
		return calculatePayment(order) - (coupon == null ? 0 : 100);
	}

	public void refund(Order order) {
		repository.deleteById(order.id());
	}

}
