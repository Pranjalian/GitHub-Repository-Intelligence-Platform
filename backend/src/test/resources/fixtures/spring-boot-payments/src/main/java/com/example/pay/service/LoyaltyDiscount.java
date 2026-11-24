package com.example.pay.service;

import com.example.pay.domain.Order;
import org.springframework.stereotype.Component;

@Component
public class LoyaltyDiscount implements DiscountPolicy {

	@Override
	public long apply(Order order) {
		return order.amountCents() / 10;
	}

}
