package spn.stdlib.option;

import spn.type.FieldType;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;

/**
 * Defines the Option ADT: Some(value) | None.
 *
 * Formalizes the (:ok, val) / (:error, _) pattern into proper nominal types
 * that participate in pattern matching via struct descriptor identity.
 *
 * <pre>
 *   data Option<T> = Some(value: T) | None
 *
 *   match result
 *     | Some(v) -> "got " ++ show(v)
 *     | None    -> "nothing"
 * </pre>
 */
public final class SpnOptionDescriptors {

    public static final SpnStructDescriptor SOME =
            SpnStructDescriptor.builder("Some")
                    .field("value")
                    .build();

    public static final SpnStructDescriptor NONE =
            new SpnStructDescriptor("None");

    private static final SpnStructValue NONE_INSTANCE =
            new SpnStructValue(NONE);

    /** Wraps a value in Some. */
    public static SpnStructValue some(Object value) {
        return new SpnStructValue(SOME, value);
    }

    /** Returns the singleton None. */
    public static SpnStructValue none() {
        return NONE_INSTANCE;
    }

    private SpnOptionDescriptors() {}
}
