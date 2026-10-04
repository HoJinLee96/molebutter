package cc.ataglace.molebutter.exception;

/** An application-owned, safe explanation of invalid input. Never wrap external exception messages. */
public class InputValidationFailure extends IllegalArgumentException {
    public InputValidationFailure(String message) { super(message); }
}
