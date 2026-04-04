package spn.stdlib.option;

import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;

/**
 * Defines the Result ADT: Ok(value) | Err(error).
 *
 * <pre>
 *   data Result<T, E> = Ok(value: T) | Err(error: E)
 *
 *   match parseNumber(input)
 *     | Ok(n)  -> n + 1
 *     | Err(e) -> "failed: " ++ e
 * </pre>
 */
public final class SpnResultDescriptors {

    public static final SpnStructDescriptor OK =
            SpnStructDescriptor.builder("Ok")
                    .field("value")
                    .build();

    public static final SpnStructDescriptor ERR =
            SpnStructDescriptor.builder("Err")
                    .field("error")
                    .build();

    /** Wraps a value in Ok. */
    public static SpnStructValue ok(Object value) {
        return new SpnStructValue(OK, value);
    }

    /** Wraps an error in Err. */
    public static SpnStructValue err(Object error) {
        return new SpnStructValue(ERR, error);
    }

    private SpnResultDescriptors() {}
}
