package com.example.shop.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	private Customer customer;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
	private List<OrderLine> lines = new ArrayList<>();

	private long totalCents;

	protected Order() {
	}

	public Order(Customer customer) {
		this.customer = customer;
	}

	public Long getId() {
		return id;
	}

	public Customer getCustomer() {
		return customer;
	}

	public long getTotalCents() {
		return totalCents;
	}

	public void setTotalCents(long totalCents) {
		this.totalCents = totalCents;
	}

}
