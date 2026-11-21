package com.example.shop.service;

public interface PaymentGateway {

	boolean charge(long cents);

	void refund(long cents);

}
