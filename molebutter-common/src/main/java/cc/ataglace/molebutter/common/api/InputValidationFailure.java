package cc.ataglace.molebutter.common.api;


/** An application-owned, safe explanation of invalid input. Never wrap external exception messages. */
public class InputValidationFailure extends IllegalArgumentException {
    public InputValidationFailure(String message) { super(message); }
}
