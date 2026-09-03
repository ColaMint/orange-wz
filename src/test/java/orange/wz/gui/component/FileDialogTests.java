package orange.wz.gui.component;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileDialogTests {
    @Test
    void keepsExactlyOneMatchingExtension() {
        assertEquals("Base.merged.wz", FileDialog.normalizeSaveFileExtension(
                new File("Base.merged.wz.wz"), new String[]{"wz"}).getName());
        assertEquals("Base.merged.wz", FileDialog.normalizeSaveFileExtension(
                new File("Base.merged.wz"), new String[]{"wz"}).getName());
        assertEquals("Base.merged.wz", FileDialog.normalizeSaveFileExtension(
                new File("Base.merged"), new String[]{"wz"}).getName());
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertEquals("Base.WZ", FileDialog.normalizeSaveFileExtension(
                new File("Base.WZ"), new String[]{"wz"}).getName());
    }
}
