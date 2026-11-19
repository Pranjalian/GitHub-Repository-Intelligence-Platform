package com.repointelligence.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

	private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

	@Test
	void generatesAnIdPutsItInMdcAndResponseHeaderAndClearsMdc() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get(RequestCorrelationFilter.MDC_KEY));

		filter.doFilter(new MockHttpServletRequest(), response, chain);

		String header = response.getHeader(RequestCorrelationFilter.HEADER);
		assertThat(header).isNotBlank();
		assertThat(seen.get()).isEqualTo(header);
		assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
	}

	@Test
	void keepsASafeSuppliedId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestCorrelationFilter.HEADER, "abc-12345-def");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> { });

		assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("abc-12345-def");
	}

	@Test
	void replacesAnUnsafeSuppliedId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestCorrelationFilter.HEADER, "bad id\nwith injection");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> { });

		assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).matches("^[0-9a-f-]{36}$");
	}

}
