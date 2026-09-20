package com.hubsabai.changelog.connector.github;

/**
 * Structured exception for GitHub push failures. Carries the HTTP status returned by GitHub,
 * the operation that failed (createBlob, createTree, createCommit, updateRef), and the raw
 * GitHub error message so the frontend can present a useful, categorized error to the user.
 */
public class GitHubPushException extends RuntimeException {

    private final int httpStatus;
    private final String operation;
    private final String githubMessage;

    public GitHubPushException(int httpStatus, String operation, String githubMessage) {
        super(buildMessage(httpStatus, operation, githubMessage));
        this.httpStatus = httpStatus;
        this.operation = operation;
        this.githubMessage = githubMessage;
    }

    public GitHubPushException(int httpStatus, String operation, String githubMessage, Throwable cause) {
        super(buildMessage(httpStatus, operation, githubMessage), cause);
        this.httpStatus = httpStatus;
        this.operation = operation;
        this.githubMessage = githubMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getOperation() {
        return operation;
    }

    public String getGithubMessage() {
        return githubMessage;
    }

    private static String buildMessage(int httpStatus, String operation, String githubMessage) {
        String status = httpStatus > 0 ? "HTTP " + httpStatus : "unknown status";
        return "GitHub " + operation + " failed (" + status + "): " + githubMessage;
    }
}
