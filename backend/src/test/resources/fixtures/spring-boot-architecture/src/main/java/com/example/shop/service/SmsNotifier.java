package com.example.shop.service;

public class SmsNotifier implements Notifier {

	@Override
	public void send(String message) {
		System.out.println("sms: " + message);
	}

}
