package com.example.pay.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Payment {

	@Id
	private Long id;

	private long amountCents;

}
