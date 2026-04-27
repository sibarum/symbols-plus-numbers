package spn.type;

/**
 * The binary operations that can appear in algebraic rules and constrained arithmetic.
 *
 * Each operation knows how to compute its result from two primitive values.
 * The perform() method handles type dispatch (long x long, double x double, etc.)
 * and overflow promotion (long overflow → double).
 *
 * Truffle performance note:
 * Because Operation is stored as a compilation-final field in SpnConstrainedBinaryNode,
 * Graal sees the exact enum constant at compile time. The perform() virtual call is
 * devirtualized, and the method body is inlined. The type-dispatch switches inside
 * perform() are further simplified because Graal often knows the operand types from
 * upstream specialization.
 */
public enum Operation {

    ADD("+") {
        @Override
        public Object perform(Object left, Object right) {
            if (left instanceof Long l1 && right instanceof Long l2) {
                try {
                    return Math.addExact(l1, l2);
                } catch (ArithmeticException e) {
                    return (double) l1 + (double) l2;
                }
            }
            if (left instanceof String || right instanceof String) {
                return String.valueOf(left) + String.valueOf(right);
            }
            return toDouble(left) + toDouble(right);
        }
    },

    SUB("-") {
        @Override
        public Object perform(Object left, Object right) {
            if (left instanceof Long l1 && right instanceof Long l2) {
                try {
                    return Math.subtractExact(l1, l2);
                } catch (ArithmeticException e) {
                    return (double) l1 - (double) l2;
                }
            }
            return toDouble(left) - toDouble(right);
        }
    },

    MUL("*") {
        @Override
        public Object perform(Object left, Object right) {
            if (left instanceof Long l1 && right instanceof Long l2) {
                try {
                    return Math.multiplyExact(l1, l2);
                } catch (ArithmeticException e) {
                    return (double) l1 * (double) l2;
                }
            }
            return toDouble(left) * toDouble(right);
        }
    },

    DIV("/") {
        @Override
        public Object perform(Object left, Object right) {
            if (left instanceof Long l1 && right instanceof Long l2) {
                if (l2 == 0) {
                    throw new ArithmeticException("Division by zero");
                }
                if (l1 % l2 == 0) {
                    return l1 / l2;  // exact integer division
                }
                return (double) l1 / (double) l2;  // promote to double
            }
            double divisor = toDouble(right);
            if (divisor == 0.0) {
                throw new ArithmeticException("Division by zero");
            }
            return toDouble(left) / divisor;
        }
    },

    MOD("%") {
        @Override
        public Object perform(Object left, Object right) {
            if (left instanceof Long l1 && right instanceof Long l2) {
                if (l2 == 0) {
                    throw new ArithmeticException("Modulo by zero");
                }
                return l1 % l2;
            }
            double divisor = toDouble(right);
            if (divisor == 0.0) {
                throw new ArithmeticException("Modulo by zero");
            }
            return toDouble(left) % divisor;
        }
    };

    private final String symbol;

    Operation(String symbol) {
        this.symbol = symbol;
    }

    /** Computes the result of this operation on two primitive values. */
    public abstract Object perform(Object left, Object right);

    public String getSymbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }

    /** Extracts a double from a Long or Double value. */
    protected static double toDouble(Object value) {
        return switch (value) {
            case Long l -> (double) l;
            case Double d -> d;
            default -> throw new ArithmeticException(
                    "Expected a number, got " + value.getClass().getSimpleName());
        };
    }
}
