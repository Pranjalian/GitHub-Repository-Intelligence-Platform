package com.example.shop.web;

public record CreateOrderRequest(long customerId, String product, int quantity) {
}
