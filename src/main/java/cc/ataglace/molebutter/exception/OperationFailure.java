package cc.ataglace.molebutter.exception;
/** A user-visible business state conflict, distinct from input validation and infrastructure exceptions. */
public class OperationFailure extends IllegalStateException {
    public OperationFailure(String message){super(message);}
}
