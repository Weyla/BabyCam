package com.babycam;

/** Release tags use vMAJOR.MINOR[.PATCH], with missing components treated as zero. */
final class UpdateVersion {
    private UpdateVersion() {}

    static boolean isNewer(String candidate, String installed) {
        int[] next = parse(candidate);
        int[] current = parse(installed);
        for (int i = 0; i < 3; i++) {
            if (next[i] != current[i]) return next[i] > current[i];
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null || !version.matches("v?[0-9]+(\\.[0-9]+){0,2}")) {
            throw new IllegalArgumentException("Unsupported release version");
        }
        String[] parts = version.replaceFirst("^v", "").split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
        return result;
    }
}
