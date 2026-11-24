package com.example.pay.web;

import com.example.pay.domain.Order;
import com.example.pay.service.PaymentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

	private final PaymentService payments;

	public PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@PostMapping
	public long pay(@RequestBody Order order) {
		return payments.calculatePayment(order);
	}

}
