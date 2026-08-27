package com.metaml.workbench.generation;

// Exception thrown when launching a generated project fails.
public class GeneratedProjectLaunchException extends IllegalStateException {

    private final int port;
    private final Integer exitCode;

    public GeneratedProjectLaunchException(String message, int port, Integer exitCode, Throwable cause) {
        super(message, cause);
        this.port = port;
        this.exitCode = exitCode;
    }

    public int port() {
        return port;
    }

    // Optional process exit code (null if execution timed out).
    public Integer exitCode() {
        return exitCode;
    }
}
