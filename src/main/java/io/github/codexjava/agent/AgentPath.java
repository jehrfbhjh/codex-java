package io.github.codexjava.agent;

import java.util.Objects;

public record AgentPath(String value) implements Comparable<AgentPath> {
    public static final String ROOT = "/root";

    public AgentPath {
        Objects.requireNonNull(value);
        validateAbsolutePath(value);
    }

    public static AgentPath root() {
        return new AgentPath(ROOT);
    }

    public AgentPath join(String agentName) {
        validateAgentName(agentName);
        return new AgentPath(value + "/" + agentName);
    }

    public AgentPath resolve(String reference) {
        if (reference == null || reference.isEmpty()) {
            throw new IllegalArgumentException("agent path must not be empty");
        }
        if (ROOT.equals(reference)) {
            return root();
        }
        if (reference.startsWith("/")) {
            return new AgentPath(reference);
        }
        validateRelativeReference(reference);
        return new AgentPath(value + "/" + reference);
    }

    public boolean isRoot() {
        return ROOT.equals(value);
    }

    public String name() {
        return isRoot() ? "root" : value.substring(value.lastIndexOf('/') + 1);
    }

    public AgentPath parent() {
        if (isRoot()) {
            throw new IllegalStateException("root agent has no parent");
        }
        return new AgentPath(value.substring(0, value.lastIndexOf('/')));
    }

    public boolean matchesPrefix(AgentPath prefix) {
        return value.equals(prefix.value) || value.startsWith(prefix.value + "/");
    }

    @Override
    public int compareTo(AgentPath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validateAbsolutePath(String path) {
        if (!path.startsWith(ROOT)) {
            throw new IllegalArgumentException("absolute agent paths must start with `/root`");
        }
        if (path.endsWith("/") || (!path.equals(ROOT) && !path.startsWith(ROOT + "/"))) {
            throw new IllegalArgumentException("invalid absolute agent path: " + path);
        }
        String suffix = path.substring(ROOT.length());
        if (!suffix.isEmpty()) {
            for (String segment : suffix.substring(1).split("/")) {
                validateAgentName(segment);
            }
        }
    }

    private static void validateRelativeReference(String reference) {
        if (reference.endsWith("/")) {
            throw new IllegalArgumentException("relative agent path must not end with `/`");
        }
        for (String segment : reference.split("/")) {
            validateAgentName(segment);
        }
    }

    private static void validateAgentName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("agent_name must not be empty");
        }
        if ("root".equals(name)) {
            throw new IllegalArgumentException("agent_name `root` is reserved");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("agent_name `" + name + "` is reserved");
        }
        if (name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("agent_name must not contain `/`");
        }
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "agent_name must use only lowercase letters, digits, and underscores"
            );
        }
    }
}
