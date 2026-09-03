package orange.wz.provider.tools;

import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFile;
import orange.wz.provider.WzImage;
import orange.wz.provider.properties.WzIntProperty;
import orange.wz.provider.properties.WzListProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
    void matchingContainersAreComparedThroughImageProperties() {
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

        assertEquals(1, candidates.size());
        assertEquals("/A.img/info/added", candidates.getFirst().path());
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
        WzImage image = new WzImage(name, file.getWzDirectory(), file.getReader());
        file.getWzDirectory().addChild(image);
        return image;
    }
}
