package spn.clifford;

public class CliffordIncompatibleArithmeticException extends RuntimeException {

    public CliffordIncompatibleArithmeticException(CliffordNumber first, CliffordNumber second) {
        String message;
        if (first == null || second == null) {
            message = "Cannot perform arithmetic on null";
        } else {
            message = "Incompatible clifford types: %s and %s".formatted(first.getClass().getSimpleName(), second.getClass().getSimpleName());
        }
        super(message);
    }

}
