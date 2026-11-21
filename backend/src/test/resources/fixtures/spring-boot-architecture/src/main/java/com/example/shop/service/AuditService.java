package com.example.shop.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

	@Autowired
	private ReportService reportService;

	public void record(String event) {
		reportService.refresh();
	}

}
