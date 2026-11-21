package com.example.shop.service;

import org.springframework.stereotype.Component;

/** Named like a factory, but it creates nothing: it must not be reported as one. */
@Component
public class PaymentFactory {

	public String defaultCurrency() {
		return "EUR";
	}

}
