package com.mimocodes.mimorag.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the verified {@code AccessContext} of the current request into a controller method parameter, so
 * controllers never deal with authentication mechanics.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentAccess {

}
