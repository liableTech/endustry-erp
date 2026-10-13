package com.endustry.erp.platform.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** A structurally valid 15-character GSTIN with a correct check digit. Blank is allowed. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = GstinValidator.class)
public @interface Gstin {

	String message() default "is not a valid GSTIN";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
