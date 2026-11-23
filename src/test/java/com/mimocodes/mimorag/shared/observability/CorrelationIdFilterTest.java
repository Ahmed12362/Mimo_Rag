package com.mimocodes.mimorag.shared.observability;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

	private final CorrelationIdFilter filter = new CorrelationIdFilter();

	@Test
	void generatesIdWhenHeaderIsMissing() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInMdc = new AtomicReference<>();

		filter.doFilter(new MockHttpServletRequest(), response, capturingMdc(seenInMdc));

		String header = response.getHeader(CorrelationId.HEADER_NAME);
		assertThat(header).isNotBlank().isEqualTo(seenInMdc.get());
		assertThat(CorrelationId.isAcceptable(header)).isTrue();
	}

	@Test
	void propagatesValidClientSuppliedId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationId.HEADER_NAME, "hris-req-0001");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInMdc = new AtomicReference<>();

		filter.doFilter(request, response, capturingMdc(seenInMdc));

		assertThat(response.getHeader(CorrelationId.HEADER_NAME)).isEqualTo("hris-req-0001");
		assertThat(seenInMdc.get()).isEqualTo("hris-req-0001");
	}

	@ParameterizedTest
	@ValueSource(strings = { "short", "contains spaces here", "line\nbreak-injection", "<script>alert(1)</script>",
			"0123456789012345678901234567890123456789012345678901234567890123456789" })
	void replacesUnsafeOrMalformedIds(String supplied) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationId.HEADER_NAME, supplied);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(response.getHeader(CorrelationId.HEADER_NAME)).isNotEqualTo(supplied)
			.satisfies(id -> assertThat(CorrelationId.isAcceptable(id)).isTrue());
	}

	@Test
	void clearsMdcAfterRequestEvenWhenChainFails() {
		FilterChain failingChain = (req, res) -> {
			throw new IllegalStateException("boom");
		};

		try {
			filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain);
		}
		catch (Exception expected) {
			// the filter must not swallow downstream failures
		}

		assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
	}

	private static FilterChain capturingMdc(AtomicReference<String> target) {
		return (req, res) -> target.set(MDC.get(CorrelationId.MDC_KEY));
	}

}
