package com.example.shop.support;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdGenerator {

	public String next() {
		return UUID.randomUUID().toString();
	}

}
