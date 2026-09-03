package orange.wz.provider.tools;

import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFile;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;

import java.util.ArrayList;
import java.util.List;

public final class WzMergeService {
    public enum Action {
        ADD,
        REPLACE
    }

    public record Candidate(String path, WzObject source, WzObject targetParent,
                            WzObject existingTarget, Action action) {
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
            if (candidate.action() == Action.REPLACE) {
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
                result.add(new Candidate(path, newChild, oldParent, null, Action.ADD));
                continue;
            }
            if (oldChild.getType() != newChild.getType()) {
                result.add(new Candidate(path, newChild, oldParent, oldChild, Action.REPLACE));
                continue;
            }
            if (canHaveChildren(newChild)) {
                compareChildren(oldChild, newChild, path, result);
            }
        }
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
