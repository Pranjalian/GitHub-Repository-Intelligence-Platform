package com.example.shop.web;

import com.example.shop.service.OrderService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.ORDERS)
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@GetMapping
	public List<OrderResponse> list() {
		return orderService.findAll();
	}

	@GetMapping(ApiPaths.BY_ID)
	public ResponseEntity<OrderResponse> get(@PathVariable long id) {
		return ResponseEntity.ok(orderService.find(id));
	}

	@PostMapping
	public OrderResponse create(@RequestBody CreateOrderRequest request) {
		return orderService.create(request);
	}

	@DeleteMapping(path = "/{id}")
	public void delete(@PathVariable long id) {
		orderService.delete(id);
	}

	@RequestMapping(value = "/search", method = { RequestMethod.GET, RequestMethod.POST })
	public List<OrderResponse> search(@RequestBody(required = false) CreateOrderRequest filter) {
		return orderService.findAll();
	}

	@GetMapping(ExternalRoutes.LEGACY)
	public String legacy() {
		return "legacy";
	}

}
