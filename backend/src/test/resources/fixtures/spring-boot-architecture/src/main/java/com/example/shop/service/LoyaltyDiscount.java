package com.example.shop.service;

import org.springframework.stereotype.Component;

@Component
public class LoyaltyDiscount implements DiscountPolicy {

	@Override
	public long apply(long totalCents) {
		return totalCents - 100;
	}

}
