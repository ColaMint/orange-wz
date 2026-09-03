package orange.wz.provider.tools;

import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFile;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;
import orange.wz.provider.properties.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class WzMergeService {
    public enum Action {
        ADD,
        UPDATE,
        REPLACE
    }

    public record Candidate(String path, WzObject source, WzObject targetParent,
                            WzObject existingTarget, Action action,
                            String oldContent, String newContent) {
    }

    private record PreparedCandidate(Candidate candidate, WzObject copiedNode) {
    }

    public List<Candidate> compare(WzFile oldFile, WzFile newFile) {
        parseFile(oldFile);
        parseFile(newFile);

        List<Candidate> result = new ArrayList<>();
        compareChildren(oldFile.getWzDirectory(), newFile.getWzDirectory(), "", result);
        return result;
    }

    public WzFile apply(WzFile oldFile, List<Candidate> selected) {
        List<PreparedCandidate> prepared = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            prepared.add(new PreparedCandidate(candidate, candidate.source().deepClone(null)));
        }

        for (PreparedCandidate item : prepared) {
            Candidate candidate = item.candidate();
            if (candidate.action() != Action.ADD) {
                removeChild(candidate.targetParent(), candidate.existingTarget());
            }

            WzObject clone = item.copiedNode();
            clone.setParent(candidate.targetParent());
            bindClone(clone, oldFile, findOwningImage(candidate.targetParent()));
            if (!addChild(candidate.targetParent(), clone)) {
                throw new IllegalStateException("Unable to merge node: " + candidate.path());
            }
        }

        renameMergedFile(oldFile);
        oldFile.setNewFile(true);
        oldFile.getWzDirectory().setTempChanged(true);
        return oldFile;
    }

    private void compareChildren(WzObject oldParent, WzObject newParent, String parentPath,
                                 List<Candidate> result) {
        List<WzObject> oldChildren = childrenOf(oldParent);
        for (WzObject newChild : childrenOf(newParent)) {
            String path = parentPath + "/" + newChild.getName();
            WzObject oldChild = findByName(oldChildren, newChild.getName());
            if (oldChild == null) {
                result.add(candidate(path, newChild, oldParent, null, Action.ADD));
                continue;
            }
            if (oldChild.getType() != newChild.getType()) {
                result.add(candidate(path, newChild, oldParent, oldChild, Action.REPLACE));
                continue;
            }
            if (!contentEquals(oldChild, newChild)) {
                result.add(candidate(path, newChild, oldParent, oldChild, Action.UPDATE));
                continue;
            }
            if (canHaveChildren(newChild)) {
                compareChildren(oldChild, newChild, path, result);
            }
        }
    }

    private Candidate candidate(String path, WzObject source, WzObject targetParent,
                                WzObject existingTarget, Action action) {
        return new Candidate(path, source, targetParent, existingTarget, action,
                existingTarget == null ? null : describeContent(existingTarget), describeContent(source));
    }

    private boolean contentEquals(WzObject oldObject, WzObject newObject) {
        return switch (oldObject) {
            case WzDirectory ignored -> true;
            case WzImage ignored -> true;
            case WzListProperty ignored -> true;
            case WzConvexProperty ignored -> true;
            case WzCanvasProperty oldValue -> canvasEquals(oldValue, (WzCanvasProperty) newObject);
            case WzRawDataProperty oldValue -> {
                WzRawDataProperty newValue = (WzRawDataProperty) newObject;
                yield oldValue.getDataType() == newValue.getDataType()
                        && oldValue.getLength() == newValue.getLength()
                        && Arrays.equals(oldValue.getBytes(false), newValue.getBytes(false));
            }
            case WzSoundProperty oldValue -> {
                WzSoundProperty newValue = (WzSoundProperty) newObject;
                yield oldValue.getLenMs() == newValue.getLenMs()
                        && Arrays.equals(oldValue.getHeader(), newValue.getHeader())
                        && Arrays.equals(oldValue.getSoundBytes(false), newValue.getSoundBytes(false));
            }
            case WzIntProperty oldValue -> oldValue.getValue() == ((WzIntProperty) newObject).getValue();
            case WzShortProperty oldValue -> oldValue.getValue() == ((WzShortProperty) newObject).getValue();
            case WzLongProperty oldValue -> oldValue.getValue() == ((WzLongProperty) newObject).getValue();
            case WzFloatProperty oldValue ->
                    Float.compare(oldValue.getValue(), ((WzFloatProperty) newObject).getValue()) == 0;
            case WzDoubleProperty oldValue ->
                    Double.compare(oldValue.getValue(), ((WzDoubleProperty) newObject).getValue()) == 0;
            case WzStringProperty oldValue ->
                    Objects.equals(oldValue.getValue(), ((WzStringProperty) newObject).getValue());
            case WzUOLProperty oldValue ->
                    Objects.equals(oldValue.getValue(), ((WzUOLProperty) newObject).getValue());
            case WzLuaProperty oldValue ->
                    Objects.equals(oldValue.getString(), ((WzLuaProperty) newObject).getString());
            case WzVectorProperty oldValue -> {
                WzVectorProperty newValue = (WzVectorProperty) newObject;
                yield oldValue.getX() == newValue.getX() && oldValue.getY() == newValue.getY();
            }
            case WzNullProperty ignored -> true;
            default -> throw new IllegalStateException("Unsupported content type: " + oldObject.getType());
        };
    }

    private boolean canvasEquals(WzCanvasProperty oldCanvas, WzCanvasProperty newCanvas) {
        if (oldCanvas.getWidth() != newCanvas.getWidth()
                || oldCanvas.getHeight() != newCanvas.getHeight()
                || oldCanvas.getFormat() != newCanvas.getFormat()
                || oldCanvas.getScale() != newCanvas.getScale()) {
            return false;
        }

        BufferedImage oldImage = oldCanvas.getPngImage(false);
        BufferedImage newImage = newCanvas.getPngImage(false);
        if (oldImage == null || newImage == null) return oldImage == newImage;
        int[] oldRow = new int[oldImage.getWidth()];
        int[] newRow = new int[newImage.getWidth()];
        for (int y = 0; y < oldImage.getHeight(); y++) {
            oldImage.getRGB(0, y, oldImage.getWidth(), 1, oldRow, 0, oldImage.getWidth());
            newImage.getRGB(0, y, newImage.getWidth(), 1, newRow, 0, newImage.getWidth());
            if (!Arrays.equals(oldRow, newRow)) return false;
        }
        return true;
    }

    private String describeContent(WzObject object) {
        return switch (object) {
            case WzDirectory value -> "Directory (children=" + value.getChildren().size() + ")";
            case WzImage value -> "Image (size=" + value.getDataSize() + ", checksum=" + value.getChecksum() + ")";
            case WzListProperty value -> "List (children=" + value.getChildren().size() + ")";
            case WzConvexProperty value -> "Convex (children=" + value.getChildren().size() + ")";
            case WzCanvasProperty value -> "Canvas (" + value.getWidth() + "x" + value.getHeight()
                    + ", format=" + value.getFormat() + ", scale=" + value.getScale()
                    + ", pixels=" + imageHash(value.getPngImage(false)) + ")";
            case WzRawDataProperty value -> "RawData (type=" + value.getDataType() + ", length="
                    + value.getLength() + ", SHA-256=" + hash(value.getBytes(false)) + ")";
            case WzSoundProperty value -> "Sound (duration=" + value.getLenMs() + "ms, length="
                    + byteLength(value.getSoundBytes(false)) + ", SHA-256=" + hash(value.getSoundBytes(false)) + ")";
            case WzIntProperty value -> "Int: " + value.getValue();
            case WzShortProperty value -> "Short: " + value.getValue();
            case WzLongProperty value -> "Long: " + value.getValue();
            case WzFloatProperty value -> "Float: " + value.getValue();
            case WzDoubleProperty value -> "Double: " + value.getValue();
            case WzStringProperty value -> "String: " + summarizeText(value.getValue());
            case WzUOLProperty value -> "UOL: " + summarizeText(value.getValue());
            case WzLuaProperty value -> "Lua: " + summarizeText(value.getString());
            case WzVectorProperty value -> "Vector: (" + value.getX() + ", " + value.getY() + ")";
            case WzNullProperty ignored -> "Null";
            default -> object.getType().name();
        };
    }

    private String summarizeText(String value) {
        if (value == null) return "null";
        if (value.length() <= 200) return value;
        return value.substring(0, 200) + "... (length=" + value.length()
                + ", SHA-256=" + hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ")";
    }

    private String imageHash(BufferedImage image) {
        if (image == null) return "null";
        MessageDigest digest = sha256();
        byte[] bytes = new byte[4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            for (int pixel : row) {
                buffer.clear();
                buffer.putInt(pixel);
                digest.update(bytes);
            }
        }
        return toHex(digest.digest());
    }

    private String hash(byte[] bytes) {
        if (bytes == null) return "null";
        return toHex(sha256().digest(bytes));
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 8));
    }

    private int byteLength(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    private List<WzObject> childrenOf(WzObject object) {
        if (object instanceof WzDirectory directory) {
            return directory.getChildren();
        }
        if (object instanceof WzImage image) {
            parseImage(image);
            return new ArrayList<>(image.getChildren());
        }
        if (object instanceof WzImageProperty property && property.isListProperty()) {
            return new ArrayList<>(property.getChildren());
        }
        return List.of();
    }

    private boolean canHaveChildren(WzObject object) {
        return object instanceof WzDirectory
                || object instanceof WzImage
                || object instanceof WzImageProperty property && property.isListProperty();
    }

    private WzObject findByName(List<WzObject> children, String name) {
        for (WzObject child : children) {
            if (child.getName().equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    private void parseFile(WzFile file) {
        if (!file.parse()) {
            throw new IllegalStateException(file.getName() + ": " + file.getStatus().getMessage());
        }
    }

    private void parseImage(WzImage image) {
        if (!image.parse()) {
            throw new IllegalStateException(image.getPath() + ": " + image.getStatus().getMessage());
        }
    }

    private void bindClone(WzObject object, WzFile targetFile, WzImage targetImage) {
        if (object instanceof WzDirectory directory) {
            directory.setWzFile(targetFile);
            for (WzObject child : directory.getChildren()) {
                child.setParent(directory);
                bindClone(child, targetFile, null);
            }
        } else if (object instanceof WzImage image) {
            image.setReader(targetFile.getReader());
            for (WzImageProperty property : image.getChildren()) {
                property.setParent(image);
                bindClone(property, targetFile, image);
            }
        } else if (object instanceof WzImageProperty property) {
            WzImage owner = targetImage != null ? targetImage : property.getWzImage();
            property.setWzImage(owner);
            if (property.isListProperty()) {
                for (WzImageProperty child : property.getChildren()) {
                    child.setParent(property);
                    bindClone(child, targetFile, owner);
                }
            }
        }
    }

    private WzImage findOwningImage(WzObject object) {
        WzObject current = object;
        while (current != null) {
            if (current instanceof WzImage image) {
                return image;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean addChild(WzObject parent, WzObject child) {
        return switch (parent) {
            case WzDirectory directory when child instanceof WzDirectory childDirectory ->
                    directory.addChild(childDirectory);
            case WzDirectory directory when child instanceof WzImage childImage -> directory.addChild(childImage);
            case WzImage image when child instanceof WzImageProperty property -> image.addChild(property);
            case WzImageProperty property when property.isListProperty()
                    && child instanceof WzImageProperty childProperty -> property.addChild(childProperty);
            default -> throw new IllegalStateException("Unsupported merge parent: " + parent.getClass().getSimpleName());
        };
    }

    private void removeChild(WzObject parent, WzObject child) {
        boolean removed = switch (parent) {
            case WzDirectory directory when child instanceof WzDirectory ->
                    directory.removeDirectoryChild(child.getName());
            case WzDirectory directory when child instanceof WzImage -> directory.removeImageChild(child.getName());
            case WzImage image when child instanceof WzImageProperty -> image.removeChild(child.getName());
            case WzImageProperty property when property.isListProperty() -> property.removeChild(child.getName());
            default -> false;
        };
        if (!removed) {
            throw new IllegalStateException("Unable to replace node: " + child.getPath());
        }
    }

    private void renameMergedFile(WzFile file) {
        String name = file.getName();
        String mergedName = name.toLowerCase().endsWith(".wz")
                ? name.substring(0, name.length() - 3) + ".merged.wz"
                : name + ".merged.wz";

        file.setNameAnyway(mergedName);
        file.setParent(null);
        WzDirectory root = file.getWzDirectory();
        root.setNameAnyway(mergedName);
        root.setParent(file);
        refreshPaths(root);
    }

    private void refreshPaths(WzObject parent) {
        for (WzObject child : childrenWithoutParsing(parent)) {
            child.setParent(parent);
            refreshPaths(child);
        }
    }

    private List<? extends WzObject> childrenWithoutParsing(WzObject object) {
        if (object instanceof WzDirectory directory) {
            return directory.getChildren();
        }
        if (object instanceof WzImage image) {
            return image.getChildren();
        }
        if (object instanceof WzImageProperty property && property.isListProperty()) {
            return property.getChildren();
        }
        return List.of();
    }
}
