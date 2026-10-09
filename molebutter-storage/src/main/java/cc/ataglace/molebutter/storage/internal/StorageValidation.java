package cc.ataglace.molebutter.storage.internal;

import cc.ataglace.molebutter.storage.api.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

final class StorageValidation {
    private StorageValidation() { }

    static void key(String key) {
        path(key, false);
    }

    static void prefix(String prefix) {
        path(prefix, true);
    }

    private static void path(String value, boolean prefix) {
        require(value != null && (prefix || !value.isEmpty()));
        require(value.getBytes(StandardCharsets.UTF_8).length <= 1024 && !value.startsWith("/")
                && !value.contains("\\") && validUnicode(value));
        if (value.isEmpty()) return;
        String[] segments = value.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean finalPrefixSlash = prefix && i == segments.length - 1 && segment.isEmpty();
            require(finalPrefixSlash || (!segment.isEmpty() && !segment.equals(".") && !segment.equals("..")));
        }
    }

    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    static void contentType(String value) {
        require(value != null && value.length() <= 255
                && value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[\\x20-\\x7e]+)?"));
    }

    static Map<String, String> metadata(Map<String, String> values) {
        require(values != null && values.size() <= 32);
        int bytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            require(entry.getKey() != null && entry.getKey().matches("[a-z0-9][a-z0-9_-]{0,63}"));
            require(entry.getValue() != null && entry.getValue().length() <= 1024
                    && entry.getValue().chars().allMatch(c -> c >= 32 && c <= 126));
            bytes += entry.getKey().length() + entry.getValue().length();
        }
        require(bytes <= 2048);
        return Map.copyOf(values);
    }

    static void eTag(String value) {
        require(value != null && (value.matches("[A-Za-z0-9_-]{1,256}")
                || value.matches("\"[\\x21\\x23-\\x7e]{1,256}\"")));
    }

    static void token(String value) {
        require(value == null || (!value.isEmpty() && value.length() <= 8192 && validUnicode(value)));
    }

    static String encodedKey(String key) {
        StringBuilder encoded = new StringBuilder();
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(hex[c >>> 4]).append(hex[c & 15]);
            }
        }
        return encoded.toString();
    }

    static void require(boolean condition) {
        if (!condition) throw new StorageException(StorageException.Code.INVALID_ARGUMENT);
    }
}
