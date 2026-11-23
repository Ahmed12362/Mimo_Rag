package com.mimocodes.mimorag.shared.web;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CurrentAccessArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentAccess.class)
				&& AccessContext.class.equals(parameter.getParameterType());
	}

	@Override
	public AccessContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		// Security guarantees a context on every protected endpoint; its absence is a configuration bug.
		return AccessContextHolder.current()
			.orElseThrow(() -> new IllegalStateException("No verified access context for this request"));
	}

}
