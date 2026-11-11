package com.group5.inventoryservice.inventory.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NonZero.Validator.class)
public @interface NonZero {

    String message() default "must not be 0";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NonZero, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value != 0;
        }
    }
}
