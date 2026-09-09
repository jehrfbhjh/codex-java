package io.github.codexjava.tools;

import io.github.codexjava.config.CodexConfig;

import java.io.Console;
import java.util.Locale;

public final class ApprovalGate {
    private final CodexConfig.ApprovalPolicy policy;

    public ApprovalGate(CodexConfig.ApprovalPolicy policy) {
        this.policy = policy;
    }

    public boolean approve(String action, boolean requiresApproval) {
        if (!requiresApproval) {
            return true;
        }
        if (policy == CodexConfig.ApprovalPolicy.NEVER) {
            return false;
        }
        Console console = System.console();
        if (console == null) {
            return false;
        }
        String answer = console.readLine("Approve %s? [y/N] ", action);
        return answer != null && answer.strip().toLowerCase(Locale.ROOT).matches("y|yes");
    }
}
