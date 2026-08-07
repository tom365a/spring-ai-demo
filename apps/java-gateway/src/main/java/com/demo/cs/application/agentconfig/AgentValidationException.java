package com.demo.cs.application.agentconfig;

import java.util.List;

public class AgentValidationException extends RuntimeException {

    private final List<ValidationIssue> errors;
    private final List<ValidationIssue> warnings;

    public AgentValidationException(String message, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        super(message);
        this.errors = errors != null ? List.copyOf(errors) : List.of();
        this.warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public List<ValidationIssue> getErrors() { return errors; }
    public List<ValidationIssue> getWarnings() { return warnings; }

    public record ValidationIssue(String path, String message) {}
}
