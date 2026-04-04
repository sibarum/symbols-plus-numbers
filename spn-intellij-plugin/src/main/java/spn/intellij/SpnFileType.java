package spn.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;

import javax.swing.*;

public final class SpnFileType extends LanguageFileType {

    public static final SpnFileType INSTANCE = new SpnFileType();

    private SpnFileType() {
        super(SpnLanguage.INSTANCE);
    }

    @Override
    public String getName() {
        return "SPN";
    }

    @Override
    public String getDescription() {
        return "SPN language file";
    }

    @Override
    public String getDefaultExtension() {
        return "spn";
    }

    @Override
    public Icon getIcon() {
        return SpnIcons.FILE;
    }
}
