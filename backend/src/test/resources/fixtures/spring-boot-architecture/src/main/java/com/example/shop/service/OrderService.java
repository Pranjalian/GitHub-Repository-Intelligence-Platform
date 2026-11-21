package com.example.shop.service;

import com.example.shop.domain.Customer;
import com.example.shop.domain.Order;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.web.CreateOrderRequest;
import com.example.shop.web.OrderResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

	private final OrderRepository orders;

	private final CustomerRepository customers;

	private final List<DiscountPolicy> discountPolicies;

	private final PriceCalculator calculator = new PriceCalculator();

	public OrderService(OrderRepository orders, CustomerRepository customers, List<DiscountPolicy> discountPolicies) {
		this.orders = orders;
		this.customers = customers;
		this.discountPolicies = discountPolicies;
	}

	public List<OrderResponse> findAll() {
		return orders.findAll().stream().map(this::toResponse).toList();
	}

	public OrderResponse find(long id) {
		return toResponse(orders.findById(id).orElseThrow());
	}

	public OrderResponse create(CreateOrderRequest request) {
		Customer customer = customers.findById(request.customerId()).orElseThrow();
		Order order = new Order(customer);
		long total = calculator.total(request.quantity(), 1000);
		for (DiscountPolicy policy : discountPolicies) {
			total = policy.apply(total);
		}
		order.setTotalCents(total);
		return toResponse(orders.save(order));
	}

	public void delete(long id) {
		orders.deleteById(id);
	}

	private OrderResponse toResponse(Order order) {
		return new OrderResponse(order.getId(), order.getCustomer().getName(), order.getTotalCents());
	}

}
