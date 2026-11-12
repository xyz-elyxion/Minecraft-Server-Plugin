package dev.crackedauth.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal version comparison for {@code major.minor[.patch]} strings.
 *
 * <p>Anything before the first number is ignored, so {@code v1.2.3},
 * {@code CrackedAuth-1.2.3.jar} and {@code 1.2.3} all parse to the same
 * version.</p>
 */
public final class Version {

    private static final Pattern PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private final int major;
    private final int minor;
    private final int patch;

    private Version(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static Version parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), patch);
    }

    /**
     * @return negative when {@code left} is older than {@code right}, positive when
     *         it is newer, and 0 when they are equal or either side is unparseable
     */
    public static int compare(String left, String right) {
        Version first = parse(left);
        Version second = parse(right);
        if (first == null || second == null) {
            return 0;
        }
        if (first.major != second.major) {
            return Integer.compare(first.major, second.major);
        }
        if (first.minor != second.minor) {
            return Integer.compare(first.minor, second.minor);
        }
        return Integer.compare(first.patch, second.patch);
    }
}
