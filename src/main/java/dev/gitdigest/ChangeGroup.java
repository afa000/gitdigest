package dev.gitdigest;

/**
 * The buckets a changelog is grouped into, in the order they are presented.
 */
public enum ChangeGroup {
    FEATURES("Features"),
    FIXES("Bug Fixes"),
    OTHER("Other");

    private final String label;

    ChangeGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ChangeGroup forType(String type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case "feat" -> FEATURES;
            case "fix" -> FIXES;
            default -> OTHER;
        };
    }
}
