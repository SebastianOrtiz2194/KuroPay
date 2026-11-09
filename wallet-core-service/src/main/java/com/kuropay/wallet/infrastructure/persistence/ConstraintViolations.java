package com.kuropay.wallet.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Lets adapters translate one specific database constraint into a domain exception while
 * rethrowing any other integrity error untouched.
 */
final class ConstraintViolations {

    private ConstraintViolations() {
    }

    static boolean isViolationOf(Throwable error, String constraintName) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
