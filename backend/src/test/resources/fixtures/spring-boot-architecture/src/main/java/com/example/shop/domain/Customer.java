package com.example.shop.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.Set;

@Entity
public class Customer {

	@Id
	private Long id;

	private String name;

	@OneToMany(mappedBy = "customer")
	private Set<Order> orders;

	public String getName() {
		return name;
	}

}
