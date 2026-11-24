package com.example.pay.domain;

public record Order(Long id, long amountCents, String customer) {
}
