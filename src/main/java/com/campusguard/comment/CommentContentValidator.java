package com.campusguard.comment;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

final class CommentContentValidator implements ConstraintValidator<CommentContent, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String body;
        java.util.UUID mediaId;
        if (value instanceof CreateCommentRequest request) {
            body = request.body();
            mediaId = request.mediaId();
        } else if (value instanceof UpdateCommentRequest request) {
            body = request.body();
            mediaId = request.mediaId();
        } else {
            return true;
        }

        if ((body != null && !body.isBlank()) || mediaId != null) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("body")
                .addConstraintViolation();
        return false;
    }
}
