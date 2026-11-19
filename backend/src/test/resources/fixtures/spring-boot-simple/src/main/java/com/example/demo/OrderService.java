package com.example.demo;

import java.util.ArrayList;
import java.util.List;

/** Deliberately smelly: many parameters, deep branching, an empty catch block and an unused local. */
public class OrderService {

	public double total(int quantity, double price, String currency, String shipping, boolean gift, boolean express,
			int discountTier) {
		double total = 0;
		int unused = 42;
		List<String> notes = new ArrayList<>();
		if (quantity > 0) {
			if (price > 0) {
				if (currency != null) {
					if (shipping.equals("standard")) {
						if (gift) {
							total = quantity * price + 1;
						}
						else if (express) {
							total = quantity * price + 5;
						}
						else {
							total = quantity * price;
						}
					}
					else if (shipping.equals("pickup")) {
						total = quantity * price - discountTier;
					}
				}
			}
		}
		try {
			total = Math.round(total);
		}
		catch (Exception e) {
		}
		return total;
	}

}
