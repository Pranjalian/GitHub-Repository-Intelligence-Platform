package com.example.shop.service;

import org.springframework.stereotype.Component;

@Component
public class NotifierProvider {

	public Notifier create(String channel) {
		switch (channel) {
			case "sms":
				return new SmsNotifier();
			default:
				return new EmailNotifier();
		}
	}

}
