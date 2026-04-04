package spn.intellij;

import com.intellij.lang.Language;

public final class SpnLanguage extends Language {

    public static final SpnLanguage INSTANCE = new SpnLanguage();

    private SpnLanguage() {
        super("SPN");
    }
}
