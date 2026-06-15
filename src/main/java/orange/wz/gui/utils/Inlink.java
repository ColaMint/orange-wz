package orange.wz.gui.utils;

import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.MainFrame;
import orange.wz.provider.*;
import orange.wz.provider.properties.WzCanvasProperty;
import orange.wz.provider.properties.WzStringProperty;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class Inlink {
    public record Result(List<String> successPaths, List<String> failedPaths) {
    }

    public static Result replace(List<WzObject> objects) {
        List<WzCanvasProperty> targets = new ArrayList<>();
        collect(objects, targets);

        List<String> successPaths = new ArrayList<>();
        List<String> failedPaths = new ArrayList<>();

        int current = 0;
        int total = targets.size();
        for (WzCanvasProperty to : targets) {
            current++;
            MainFrame.getInstance().updateProgress(current, total);

            String err = replaceOne(to);
            if (err == null) {
                successPaths.add(to.getPath());
            } else {
                failedPaths.add(to.getPath() + "  ->  " + err);
            }
        }
        return new Result(successPaths, failedPaths);
    }

    private static void collect(List<? extends WzObject> objects, List<WzCanvasProperty> out) {
        for (WzObject o : objects) {
            if (o instanceof WzCanvasProperty p) {
                if (p.getChild("_inlink") != null) out.add(p);
            } else if (o instanceof WzImage img) {
                if (!img.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(
                            MainFrame.i18n.get("error.parse", img.getName(), img.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                collect(img.getChildren(), out);
            } else if (o instanceof WzDirectory dir) {
                collect(dir.getChildren(), out);
            } else if (o instanceof WzImageProperty p && p.isListProperty()) {
                collect(p.getChildren(), out);
            }
        }
    }

    /**
     * @return null 表示成功；非 null 表示失败原因（用于弹窗显示）
     */
    private static String replaceOne(WzCanvasProperty to) {
        WzImageProperty inlinkNode = to.getChild("_inlink");
        if (!(inlinkNode instanceof WzStringProperty sp)) {
            return "_inlink is not a string property";
        }
        String raw = sp.getValue();
        if (raw == null || raw.isEmpty()) return "_inlink value is empty";

        List<String> path = new ArrayList<>();
        for (String s : raw.split("/")) {
            if (!s.isEmpty()) path.add(s);
        }
        if (path.isEmpty()) return "_inlink value is empty: " + raw;

        WzImage img = to.getWzImage();
        if (img == null) return "no parent WzImage";
        if (!img.parse()) return "WzImage parse failed: " + img.getName();

        WzCanvasProperty from = resolve(img.getChildren(), path, 0);
        if (from == null) return "_inlink target not found: " + raw;
        if (from == to) return "_inlink points to itself: " + raw;

        to.setPng(from.getPngImage(false), from.getFormat(), from.getScale());
        to.removeChild("_inlink");
        return null;
    }

    private static WzCanvasProperty resolve(List<? extends WzObject> children, List<String> path, int step) {
        for (WzObject o : children) {
            if (!o.getName().equals(path.get(step))) continue;
            if (step == path.size() - 1) {
                return (o instanceof WzCanvasProperty c) ? c : null;
            }
            if (o instanceof WzImageProperty p) {
                return resolve(p.getChildren(), path, step + 1);
            }
        }
        return null;
    }
}
