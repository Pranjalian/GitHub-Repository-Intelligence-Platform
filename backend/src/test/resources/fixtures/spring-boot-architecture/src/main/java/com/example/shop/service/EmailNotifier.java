package com.example.shop.service;

public class EmailNotifier implements Notifier {

	@Override
	public void send(String message) {
		System.out.println("email: " + message);
	}

}
