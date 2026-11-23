package com.mimocodes.mimorag.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.mimocodes.mimorag.shared.observability.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the platform error contract independently of security, using a throwaway test controller.
 */
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.setValidator(validator)
			.addFilters(new CorrelationIdFilter())
			.build();
	}

	@Test
	void validationErrorsListEveryInvalidFieldWithoutEchoingValues() throws Exception {
		mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\",\"code\":\"secret-value-that-is-way-too-long\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.type").value("urn:mimorag:problem:validation-failed"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty())
			.andExpect(jsonPath("$.timestamp").isNotEmpty())
			.andExpect(jsonPath("$.violations.length()").value(2))
			.andExpect(jsonPath("$.violations[0].field").value("code"))
			.andExpect(jsonPath("$.violations[1].field").value("name"))
			.andExpect(content().string(not(containsString("secret-value"))));
	}

	@Test
	void malformedJsonIsRejectedWithoutParserDetails() throws Exception {
		mockMvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
			.andExpect(content().string(not(containsString("Jackson"))))
			.andExpect(content().string(not(containsString("JsonParse"))));
	}

	@Test
	void unexpectedErrorsHideInternalDetails() throws Exception {
		mockMvc.perform(get("/sample/failure"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty())
			.andExpect(content().string(not(containsString("jdbc:postgresql"))))
			.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void unsupportedMethodUsesPlatformFormat() throws Exception {
		mockMvc.perform(delete("/sample"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty());
	}

	@Test
	void unsupportedMediaTypeUsesPlatformFormat() throws Exception {
		mockMvc.perform(post("/sample").contentType(MediaType.TEXT_PLAIN).content("x"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	record SampleRequest(@NotBlank String name, @Size(max = 10) String code) {
	}

	@RestController
	static class SampleController {

		@PostMapping("/sample")
		SampleRequest create(@Valid @RequestBody SampleRequest request) {
			return request;
		}

		@GetMapping("/sample/failure")
		String fail() {
			throw new IllegalStateException("connection to jdbc:postgresql://internal-host failed");
		}

	}

}
