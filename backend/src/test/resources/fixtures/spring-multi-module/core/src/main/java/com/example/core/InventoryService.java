package com.example.core;

import org.springframework.stereotype.Service;

@Service
public class InventoryService {

	public boolean reserve(String sku, int quantity) {
		return quantity > 0;
	}

}
