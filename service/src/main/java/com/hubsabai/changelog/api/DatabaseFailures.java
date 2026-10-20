package com.hubsabai.changelog.api;

import jakarta.persistence.PersistenceException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;

/** Recognizes connection/resource failures without treating constraint violations as outages. */
public final class DatabaseFailures {

    private DatabaseFailures() {
    }

    public static boolean isUnavailable(Throwable failure) {
        boolean sawSql = false;
        boolean sawPersistence = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                sawSql = true;
                String state = sql.getSQLState();
                if (state == null
                        || state.startsWith("08")
                        || state.startsWith("53")
                        || state.startsWith("57")
                        || state.startsWith("58")) {
                    return true;
                }
            }
            if (cause instanceof PersistenceException) {
                sawPersistence = true;
            }
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return sawPersistence && !sawSql;
    }
}
