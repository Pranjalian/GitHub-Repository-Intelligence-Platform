package com.example.shop.service;

import org.springframework.stereotype.Service;

@Service
public class ReportService {

	private final AuditService auditService;

	public ReportService(AuditService auditService) {
		this.auditService = auditService;
	}

	public void refresh() {
		auditService.record("refresh");
	}

}
