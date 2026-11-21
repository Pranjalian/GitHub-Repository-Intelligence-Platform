package com.example.shop.service;

import org.springframework.stereotype.Component;

@Component
public class LegacyPaymentBridge implements PaymentGateway {

	private final LegacyPaymentClient client = new LegacyPaymentClient();

	@Override
	public boolean charge(long cents) {
		return client.pay(cents / 100.0) == 0;
	}

	@Override
	public void refund(long cents) {
		client.reverse(cents / 100.0);
	}

}
