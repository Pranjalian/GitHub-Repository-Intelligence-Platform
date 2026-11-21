package com.repointelligence.architecture.domain;

import com.repointelligence.analysis.domain.AnalysisRun;
import com.repointelligence.repository.domain.RepositorySnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** An HTTP endpoint read from Spring mapping annotations. A raw parser fact. */
@Entity
@Table(name = "api_endpoint")
public class ApiEndpoint {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "snapshot_id", nullable = false)
	private RepositorySnapshot snapshot;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private AnalysisRun run;

	@Column(name = "http_method", nullable = false)
	private String httpMethod;

	@Column(name = "route", nullable = false)
	private String route;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "controller_symbol_id", nullable = false)
	private CodeSymbol controller;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "handler_symbol_id", nullable = false)
	private CodeSymbol handler;

	@Column(name = "file_path", nullable = false)
	private String filePath;

	@Column(name = "route_resolved", nullable = false)
	private boolean routeResolved = true;

	@Column(name = "request_type")
	private String requestType;

	@Column(name = "response_type")
	private String responseType;

	@Column(name = "line_number")
	private Integer lineNumber;

	protected ApiEndpoint() {
	}

	public ApiEndpoint(RepositorySnapshot snapshot, AnalysisRun run, String httpMethod, String route, CodeSymbol controller, CodeSymbol handler, String filePath) {
		this.snapshot = snapshot;
		this.run = run;
		this.httpMethod = httpMethod;
		this.route = route;
		this.controller = controller;
		this.handler = handler;
		this.filePath = filePath;
	}

	public UUID getId() {
		return id;
	}

	public RepositorySnapshot getSnapshot() {
		return snapshot;
	}

	public AnalysisRun getRun() {
		return run;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public String getRoute() {
		return route;
	}

	public CodeSymbol getController() {
		return controller;
	}

	public CodeSymbol getHandler() {
		return handler;
	}

	public String getFilePath() {
		return filePath;
	}

	public boolean isRouteResolved() {
		return routeResolved;
	}

	public void setRouteResolved(boolean routeResolved) {
		this.routeResolved = routeResolved;
	}

	public String getRequestType() {
		return requestType;
	}

	public void setRequestType(String requestType) {
		this.requestType = requestType;
	}

	public String getResponseType() {
		return responseType;
	}

	public void setResponseType(String responseType) {
		this.responseType = responseType;
	}

	public Integer getLineNumber() {
		return lineNumber;
	}

	public void setLineNumber(Integer lineNumber) {
		this.lineNumber = lineNumber;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ApiEndpoint other)) {
			return false;
		}
		return id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
