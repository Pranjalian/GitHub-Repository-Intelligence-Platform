package com.example.shop.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class OrderLine {

	@Id
	private Long id;

	@ManyToOne
	@JoinColumn(name = "order_id")
	private Order order;

	private String product;

}
