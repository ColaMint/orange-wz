package orange.wz.provider.tools;

import lombok.extern.slf4j.Slf4j;
import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.properties.WzIntProperty;
import orange.wz.provider.properties.WzStringProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class MapIntegrityChecker {

    public static Result check(WzImage mapImg, WzDirectory wzRoot) {
        List<String> checkedPaths = new ArrayList<>();
        Set<String> missingPaths = new LinkedHashSet<>();

        for (WzImageProperty child : mapImg.getChildren()) {
            String name = child.getName();

            if ("back".equals(name) && child.isListProperty()) {
                checkBack(child, wzRoot, checkedPaths, missingPaths);
            }

            if (child.isListProperty() && name.matches("\\d")) {
                checkLayer(child, wzRoot, checkedPaths, missingPaths);
            }
        }

        return new Result(checkedPaths.size(), missingPaths);
    }

    private static void checkBack(WzImageProperty backNode, WzDirectory wzRoot,
                                  List<String> checkedPaths, Set<String> missingPaths) {
        for (WzImageProperty entry : backNode.getChildren()) {
            if (!entry.isListProperty()) continue;

            String bS = getStringValue(entry, "bS");
            if (bS == null || bS.isEmpty()) continue;

            int no = getIntValue(entry, "no", -1);
            boolean ani = getIntValue(entry, "ani", 0) != 0;

            String subDir = ani ? "ani" : "back";
            String path = "Back/" + bS + ".img/" + subDir + "/" + no;
            checkedPaths.add(path);

            if (!resolvePath(wzRoot, path)) {
                missingPaths.add(path);
            }
        }
    }

    private static void checkLayer(WzImageProperty layerNode, WzDirectory wzRoot,
                                   List<String> checkedPaths, Set<String> missingPaths) {
        WzImageProperty info = layerNode.getChild("info");
        String tS = info != null ? getStringValue(info, "tS") : null;

        WzImageProperty tileNode = layerNode.getChild("tile");
        if (tileNode != null && tileNode.isListProperty() && tS != null && !tS.isEmpty()) {
            for (WzImageProperty entry : tileNode.getChildren()) {
                if (!entry.isListProperty()) continue;

                String u = getStringValue(entry, "u");
                int no = getIntValue(entry, "no", -1);
                if (u == null || u.isEmpty()) continue;

                String path = "Tile/" + tS + ".img/" + u + "/" + no;
                checkedPaths.add(path);

                if (!resolvePath(wzRoot, path)) {
                    missingPaths.add(path);
                }
            }
        }

        WzImageProperty objNode = layerNode.getChild("obj");
        if (objNode != null && objNode.isListProperty()) {
            for (WzImageProperty entry : objNode.getChildren()) {
                if (!entry.isListProperty()) continue;

                String oS = getStringValue(entry, "oS");
                String l0 = getStringValue(entry, "l0");
                String l1 = getStringValue(entry, "l1");
                String l2 = getStringValue(entry, "l2");
                if (oS == null || oS.isEmpty()) continue;

                String path = "Obj/" + oS + ".img/" + l0 + "/" + l1 + "/" + l2;
                checkedPaths.add(path);

                if (!resolvePath(wzRoot, path)) {
                    missingPaths.add(path);
                }
            }
        }
    }

    /**
     * 沿路径在 WZ 目录树中解析，检查资源是否存在。
     * 路径相对于 .wz 根目录，格式如 "Tile/woodMarble.img/bsc/4"。
     */
    private static boolean resolvePath(WzDirectory root, String path) {
        String[] parts = path.split("/");
        WzDirectory current = root;
        int i = 0;

        // 逐段导航目录
        for (; i < parts.length; i++) {
            if (parts[i].endsWith(".img")) break;
            current = current.getDirectory(parts[i]);
            if (current == null) return false;
        }

        if (i >= parts.length) return true;

        // 获取并解析 .img
        WzImage img = current.getImage(parts[i]);
        if (img == null) return false;
        if (!img.parse()) {
            log.warn("图片解析失败: {} (路径: {})", parts[i], path);
            return false;
        }
        i++;

        // 在 img 属性树中导航剩余段
        Object currentProp = null;
        for (; i < parts.length; i++) {
            if (currentProp instanceof WzImageProperty p) {
                if (!p.isListProperty()) return false;
                currentProp = p.getChild(parts[i]);
            } else {
                currentProp = img.getChild(parts[i]);
            }
            if (currentProp == null) return false;
        }

        return true;
    }

    private static String getStringValue(WzImageProperty parent, String name) {
        WzImageProperty child = parent.getChild(name);
        if (child instanceof WzStringProperty str) return str.getValue();
        return null;
    }

    private static int getIntValue(WzImageProperty parent, String name, int defaultValue) {
        WzImageProperty child = parent.getChild(name);
        if (child instanceof WzIntProperty i) return i.getValue();
        return defaultValue;
    }

    public record Result(int checkedCount, Set<String> missingPaths) {
    }
}
