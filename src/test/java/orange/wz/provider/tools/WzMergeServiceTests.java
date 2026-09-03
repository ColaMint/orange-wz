package orange.wz.provider.tools;

import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFile;
import orange.wz.provider.WzImage;
import orange.wz.provider.properties.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WzMergeServiceTests {
    private static final byte[] IV = new byte[4];
    private static final byte[] KEY = new byte[128];
    private final WzMergeService service = new WzMergeService();

    @Test
    void missingParentProducesOneCandidateAndStopsAtItsSubtree() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        WzDirectory added = new WzDirectory("Added", newFile.getWzDirectory(), newFile);
        added.addChild(new WzDirectory("Nested", added, newFile));
        newFile.getWzDirectory().addChild(added);

        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        assertEquals(1, candidates.size());
        assertEquals("/Added", candidates.getFirst().path());
        assertEquals(WzMergeService.Action.ADD, candidates.getFirst().action());
    }

    @Test
    void matchingContainersCompareExistingValuesAndFindAddedProperties() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        WzImage oldImage = image("A.img", oldFile);
        WzImage newImage = image("A.img", newFile);
        WzListProperty oldList = new WzListProperty("info", oldImage, oldImage);
        WzListProperty newList = new WzListProperty("info", newImage, newImage);
        oldImage.addChild(oldList);
        newImage.addChild(newList);
        oldList.addChild(new WzIntProperty("same", 1, oldList, oldImage));
        newList.addChild(new WzIntProperty("same", 2, newList, newImage));
        newList.addChild(new WzIntProperty("added", 3, newList, newImage));

        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        assertEquals(Set.of("/A.img/info/same", "/A.img/info/added"), candidates.stream()
                .map(WzMergeService.Candidate::path)
                .collect(Collectors.toSet()));
        WzMergeService.Candidate updated = candidates.stream()
                .filter(candidate -> candidate.path().endsWith("/same"))
                .findFirst()
                .orElseThrow();
        assertEquals(WzMergeService.Action.UPDATE, updated.action());
        assertEquals("Int: 1", updated.oldContent());
        assertEquals("Int: 2", updated.newContent());

        service.apply(oldFile, List.of(updated));
        WzListProperty mergedList = (WzListProperty) oldImage.getChild("info");
        assertEquals(2, ((WzIntProperty) mergedList.getChild("same")).getValue());
    }

    @Test
    void comparesLeafContentAccordingToItsType() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        WzImage oldImage = image("A.img", oldFile);
        WzImage newImage = image("A.img", newFile);
        oldImage.addChild(new WzStringProperty("string", "old", oldImage, oldImage));
        newImage.addChild(new WzStringProperty("string", "new", newImage, newImage));
        oldImage.addChild(new WzVectorProperty("vector", 1, 2, oldImage, oldImage));
        newImage.addChild(new WzVectorProperty("vector", 1, 3, newImage, newImage));
        WzLuaProperty oldLua = new WzLuaProperty("lua", new byte[0], oldImage, oldImage);
        WzLuaProperty newLua = new WzLuaProperty("lua", new byte[0], newImage, newImage);
        oldLua.setString("old");
        newLua.setString("new");
        oldImage.addChild(oldLua);
        newImage.addChild(newLua);
        oldImage.addChild(new WzSoundProperty("sound", 100, new byte[]{1}, new byte[]{2}, oldImage, oldImage));
        newImage.addChild(new WzSoundProperty("sound", 101, new byte[]{1}, new byte[]{2}, newImage, newImage));
        oldImage.addChild(new WzRawDataProperty("raw", (byte) 1, 0, oldImage, oldImage));
        newImage.addChild(new WzRawDataProperty("raw", (byte) 2, 0, newImage, newImage));

        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        assertEquals(Set.of("/A.img/string", "/A.img/vector", "/A.img/lua", "/A.img/sound", "/A.img/raw"),
                candidates.stream().map(WzMergeService.Candidate::path).collect(Collectors.toSet()));
        assertTrue(candidates.stream().allMatch(candidate -> candidate.action() == WzMergeService.Action.UPDATE));
    }

    @Test
    void changedCanvasReplacesItsWholeSubtree() throws Exception {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        WzImage oldImage = image("A.img", oldFile);
        WzImage newImage = image("A.img", newFile);
        WzCanvasProperty oldCanvas = new WzCanvasProperty("canvas", WzPngFormat.ARGB8888.getValue(),
                0, png(0xff000000), oldImage, oldImage);
        WzCanvasProperty newCanvas = new WzCanvasProperty("canvas", WzPngFormat.ARGB8888.getValue(),
                0, png(0xffffffff), newImage, newImage);
        oldCanvas.addChild(new WzIntProperty("child", 1, oldCanvas, oldImage));
        newCanvas.addChild(new WzIntProperty("child", 2, newCanvas, newImage));
        oldImage.addChild(oldCanvas);
        newImage.addChild(newCanvas);

        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        assertEquals(1, candidates.size());
        assertEquals("/A.img/canvas", candidates.getFirst().path());
        assertEquals(WzMergeService.Action.UPDATE, candidates.getFirst().action());
        assertNotEquals(candidates.getFirst().oldContent(), candidates.getFirst().newContent());
    }

    @Test
    void sameNameWithDifferentTypeProducesReplacementAndStops() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        oldFile.getWzDirectory().addChild(new WzDirectory("Node", oldFile.getWzDirectory(), oldFile));
        WzImage replacement = image("node", newFile);
        replacement.addChild(new WzIntProperty("child", 1, replacement, replacement));

        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        assertEquals(1, candidates.size());
        assertEquals(WzMergeService.Action.REPLACE, candidates.getFirst().action());
        assertEquals("/node", candidates.getFirst().path());
    }

    @Test
    void applyCopiesOnlySelectedNodesAndRebindsTheirOwners() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        WzDirectory selectedDir = new WzDirectory("Selected", newFile.getWzDirectory(), newFile);
        WzImage selectedImage = new WzImage("Inside.img", selectedDir, newFile.getReader());
        selectedImage.addChild(new WzIntProperty("value", 7, selectedImage, selectedImage));
        selectedDir.addChild(selectedImage);
        newFile.getWzDirectory().addChild(selectedDir);
        newFile.getWzDirectory().addChild(new WzDirectory("Skipped", newFile.getWzDirectory(), newFile));
        List<WzMergeService.Candidate> candidates = service.compare(oldFile, newFile);

        WzFile result = service.apply(oldFile, List.of(candidates.getFirst()));

        assertEquals("Base.merged.wz", result.getName());
        assertTrue(result.isNewFile());
        assertNull(result.getWzDirectory().getDirectory("Skipped"));
        WzDirectory copiedDir = result.getWzDirectory().getDirectory("Selected");
        assertNotNull(copiedDir);
        assertSame(result, copiedDir.getWzFile());
        WzImage copiedImage = copiedDir.getImage("Inside.img");
        assertSame(result.getReader(), copiedImage.getReader());
        assertSame(copiedImage, copiedImage.getChild("value").getWzImage());
        assertEquals("Base.merged.wz/Selected/Inside.img/value", copiedImage.getChild("value").getPath());
        assertNotNull(newFile.getWzDirectory().getDirectory("Selected"));
    }

    @Test
    void applyReplacesAConflictingNodeType() {
        WzFile oldFile = newFile("Base.wz");
        WzFile newFile = newFile("Next.wz");
        oldFile.getWzDirectory().addChild(new WzDirectory("Node", oldFile.getWzDirectory(), oldFile));
        image("Node", newFile);
        WzMergeService.Candidate candidate = service.compare(oldFile, newFile).getFirst();

        service.apply(oldFile, List.of(candidate));

        assertNull(oldFile.getWzDirectory().getDirectory("Node"));
        assertNotNull(oldFile.getWzDirectory().getImage("Node"));
    }

    @Test
    void mergedResultCanBeSavedAndParsed(@TempDir Path tempDir) {
        Path output = tempDir.resolve("Base.merged.wz");
        WzFile oldFile = WzFile.createNewFile(tempDir.resolve("Base.wz").toString(),
                (short) 95, "test", IV, KEY);
        WzFile newFile = newFile("Next.wz");
        WzImage added = image("Added.img", newFile);
        added.addChild(new WzIntProperty("value", 7, added, added));
        WzMergeService.Candidate candidate = service.compare(oldFile, newFile).getFirst();

        WzFile merged = service.apply(oldFile, List.of(candidate));
        merged.setFilePath(output.toString());

        assertTrue(merged.save());
        WzFile reopened = new WzFile(output.toString(), (short) -1, "test", IV, KEY);
        assertTrue(reopened.parse());
        WzImage reopenedImage = reopened.getWzDirectory().getImage("Added.img");
        assertNotNull(reopenedImage);
        assertTrue(reopenedImage.parse());
        assertEquals(7, ((WzIntProperty) reopenedImage.getChild("value")).getValue());
    }

    private WzFile newFile(String name) {
        return WzFile.createNewFile(name, (short) 95, "test", IV, KEY);
    }

    private WzImage image(String name, WzFile file) {
        WzImage image = new WzImage(name, file.getWzDirectory(), new BinaryReader(new byte[0]));
        file.getWzDirectory().addChild(image);
        return image;
    }

    private byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
