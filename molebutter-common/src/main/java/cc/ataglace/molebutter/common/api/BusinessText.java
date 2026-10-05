package cc.ataglace.molebutter.common.api;

/** Identical trimming/length rules; callers retain their own business messages. */
public final class BusinessText {
    private BusinessText() {}
    public static String checked(String value, int max, boolean required, String message) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || required && result.isEmpty()) throw new InputValidationFailure(message);
        return result;
    }
}
