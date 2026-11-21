package com.example.web;

import com.example.core.InventoryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

	private final InventoryService inventory;

	public InventoryController(InventoryService inventory) {
		this.inventory = inventory;
	}

	@PostMapping("/inventory/{sku}/reserve")
	public boolean reserve(@PathVariable String sku) {
		return inventory.reserve(sku, 1);
	}

}
