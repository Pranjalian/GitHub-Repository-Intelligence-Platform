package com.example.shop.service;

public class PriceCalculator {

	public long total(int quantity, long unitCents) {
		return quantity * unitCents;
	}

}
