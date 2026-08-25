package com.campusguard.comment;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CommentContentValidator.class)
public @interface CommentContent {
    String message() default "A comment must contain text or an image.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
