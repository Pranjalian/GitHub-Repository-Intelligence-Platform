package com.example.pay.service;

import com.example.pay.domain.Order;

public interface DiscountPolicy {

	long apply(Order order);

}
