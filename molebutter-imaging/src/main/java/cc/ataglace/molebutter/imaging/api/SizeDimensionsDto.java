package cc.ataglace.molebutter.imaging.api;

/** 가로/폭/높이 치수(cm, 문자열). 값이 없으면 "-". */
public record SizeDimensionsDto(String width, String depth, String height) {

    public static final String MISSING = "-";

    public static SizeDimensionsDto empty() {
        return new SizeDimensionsDto(MISSING, MISSING, MISSING);
    }

    public boolean hasWidth() {
        return hasValue(width);
    }

    public boolean hasDepth() {
        return hasValue(depth);
    }

    public boolean hasHeight() {
        return hasValue(height);
    }

    public static boolean hasValue(String value) {
        return value != null && !value.isBlank() && !MISSING.equals(value.trim());
    }
}
