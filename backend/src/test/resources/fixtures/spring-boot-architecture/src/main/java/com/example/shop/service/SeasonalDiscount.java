package com.example.shop.service;

import org.springframework.stereotype.Component;

@Component
public class SeasonalDiscount implements DiscountPolicy {

	@Override
	public long apply(long totalCents) {
		return totalCents * 9 / 10;
	}

}
