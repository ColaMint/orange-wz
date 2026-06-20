package orange.wz.gui.utils;

import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.MainFrame;
import orange.wz.provider.*;
import orange.wz.provider.properties.WzCanvasProperty;
import orange.wz.provider.properties.WzStringProperty;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public final class Outlink {
    private static String lastCanvasPath;
    private static final List<WzDirectory> canvasCache = new ArrayList<>();

    public record Result(List<String> successPaths, List<String> failedPaths) {
    }

    record Data(WzCanvasProperty object, List<String> path, boolean canvasMode) {
    }

    private record ParsedOutlink(List<String> path, boolean canvasMode) {
    }

    public static Result replace(List<WzObject> objects, DefaultMutableTreeNode treeRoot) {
        Map<String, List<Data>> canvasCollector = new HashMap<>();
        List<Data> normalData = new ArrayList<>();
        collect(objects, canvasCollector, normalData);

        List<String> successPaths = new ArrayList<>();
        List<String> failedPaths = new ArrayList<>();

        int canvasTotal = canvasCollector.values().stream().mapToInt(List::size).sum();
        int total = canvasTotal + normalData.size();
        int current = 0;

        for (Data data : normalData) {
            WzCanvasProperty to = data.object();
            WzCanvasProperty from = findInOpenedWzFiles(data.path(), treeRoot);
            if (from == null) {
                failedPaths.add(to.getPath() + "  ->  " + MainFrame.i18n.get("error.outlink.cant_find_wz", String.join("/", data.path())));
            } else {
                applyPng(to, from);
                successPaths.add(to.getPath());
            }
            current++;
            if (total > 0) MainFrame.getInstance().updateProgress(current, total);
        }

        if (!canvasCollector.isEmpty()) {
            boolean loaded = loadCanvasFiles(objects.getFirst());
            if (!loaded) {
                resetCache();
                for (List<Data> dataList : canvasCollector.values()) {
                    for (Data d : dataList) {
                        failedPaths.add(d.object().getPath() + "  ->  " + MainFrame.i18n.get("error.outlink.collect", ""));
                    }
                }
            } else {
                current = processCanvas(canvasCollector, canvasCache, successPaths, failedPaths, total, current);
            }
        }

        return new Result(successPaths, failedPaths);
    }

    private static int processCanvas(Map<String, List<Data>> canvasCollector, List<WzDirectory> cache,
                                     List<String> successPaths, List<String> failedPaths, int total, int current) {
        for (var entry : canvasCollector.entrySet()) {
            String imageStr = entry.getKey();
            List<Data> dataList = entry.getValue();
            WzImage image = getCanvasImage(imageStr, cache);
            if (image == null) {
                log.error(MainFrame.i18n.get("error.outlink.cant_find_image", imageStr));
                for (Data d : dataList) {
                    failedPaths.add(d.object().getPath() + "  ->  " + MainFrame.i18n.get("error.outlink.cant_find_image", imageStr));
                }
                current += dataList.size();
                if (total > 0) MainFrame.getInstance().updateProgress(current, total);
                continue;
            }
            if (!image.parse()) {
                String parseErr = MainFrame.i18n.get("error.parse", image.getName(), image.getStatus().getMessage());
                MainFrame.getInstance().setStatusTextWithErrLog(parseErr);
                for (Data d : dataList) {
                    failedPaths.add(d.object().getPath() + "  ->  " + parseErr);
                }
                current += dataList.size();
                if (total > 0) MainFrame.getInstance().updateProgress(current, total);
                continue;
            }

            for (Data data : dataList) {
                WzCanvasProperty to = data.object();
                List<String> paths = data.path();

                int step;
                for (step = 0; step < paths.size(); step++) {
                    if (paths.get(step).equals(imageStr)) break;
                }
                step++;

                WzCanvasProperty from = getCanvasProperty(image.getChildren(), paths, step);
                if (from == null) {
                    log.error(MainFrame.i18n.get("error.outlink.cant_find_from", to.getPath(), paths));
                    failedPaths.add(to.getPath() + "  ->  " + MainFrame.i18n.get("error.outlink.cant_find_from", to.getPath(), paths));
                } else {
                    applyPng(to, from);
                    successPaths.add(to.getPath());
                }
                current++;
                if (total > 0) MainFrame.getInstance().updateProgress(current, total);
            }
        }
        return current;
    }

    private static void applyPng(WzCanvasProperty to, WzCanvasProperty from) {
        to.setPng(from.getPngImage(false), from.getFormat(), from.getScale());
        to.setTempChanged(true);
        to.getWzImage().setChanged(true);
        to.removeChild("_outlink");
    }

    private static void resetCache() {
        lastCanvasPath = null;
        canvasCache.clear();
    }

    private static boolean loadCanvasFiles(WzObject wzObject) {
        WzFile wzFile = getWzFile(wzObject);
        if (wzFile == null) return false;

        File file = new File(wzFile.getFilePath());
        String dirPath = file.getParent();
        String canvasPath = Path.of(dirPath, "_Canvas").toString();

        if (lastCanvasPath != null && lastCanvasPath.equals(canvasPath)) return true;
        resetCache();

        // 确保version已经生成
        if (!wzFile.parse()) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
            return false;
        }

        short version = wzFile.getHeader().getFileVersion();
        String keyBoxName = wzFile.getKeyBoxName();
        byte[] iv = wzFile.getIv();
        byte[] key = wzFile.getKey();

        lastCanvasPath = canvasPath;

        try (Stream<Path> pathStream = Files.walk(Path.of(canvasPath))) {
            for (Path path : pathStream.toList()) {
                if (!Files.isRegularFile(path)) continue;

                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(".wz")) continue;
                if (!fileName.startsWith("_Canvas_")) continue;

                WzFile canvasWz = new WzFile(path.toString(), version, keyBoxName, iv, key);
                if (!canvasWz.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", canvasWz.getName(), canvasWz.getStatus().getMessage()));
                    return false;
                }
                canvasCache.add(canvasWz.getWzDirectory());
            }
            return true;
        } catch (IOException e) {
            log.error(MainFrame.i18n.get("error.outlink.collect", e.getMessage()));
            return false;
        }
    }

    private static WzFile getWzFile(WzObject wzObject) {
        if (wzObject instanceof WzFile wz) {
            return wz;
        } else if (wzObject instanceof WzDirectory wzDir) {
            return wzDir.getWzFile();
        } else if (wzObject instanceof WzImage wzImg) {
            return getWzFile(wzImg.getParent());
        } else if (wzObject instanceof WzImageProperty property) {
            return getWzFile(property.getWzImage());
        }

        return null;
    }

    private static void collect(List<? extends WzObject> objects, Map<String, List<Data>> canvasCollector, List<Data> normalCollector) {
        for (WzObject wzObject : objects) {
            if (wzObject instanceof WzCanvasProperty property) {
                ParsedOutlink parsed = getOutlink(property);
                if (parsed == null) continue;

                if (parsed.canvasMode) {
                    String image = null;
                    for (String pathStr : parsed.path) {
                        if (pathStr.endsWith(".img")) {
                            image = pathStr;
                            break;
                        }
                    }
                    if (image == null) continue;

                    canvasCollector.computeIfAbsent(image, k -> new ArrayList<>())
                            .add(new Data(property, parsed.path, true));
                } else {
                    normalCollector.add(new Data(property, parsed.path, false));
                }
            } else if (wzObject instanceof WzDirectory wzDir) {
                collect(wzDir.getChildren(), canvasCollector, normalCollector);
            } else if (wzObject instanceof WzImage wzImg) {
                if (!wzImg.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImg.getName(), wzImg.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                collect(wzImg.getChildren(), canvasCollector, normalCollector);
            } else if (wzObject instanceof WzImageProperty property && property.isListProperty()) {
                collect(property.getChildren(), canvasCollector, normalCollector);
            }
        }
    }

    private static ParsedOutlink getOutlink(WzImageProperty property) {
        WzImageProperty outlinkNode = property.getChild("_outlink");
        if (!(outlinkNode instanceof WzStringProperty sp)) return null;

        String outlink = sp.getValue();
        if (outlink == null || outlink.isEmpty()) return null;

        List<String> parts = Arrays.stream(outlink.split("/"))
                .map(p -> {
                    if (p.equals("??")) return "碟喻";
                    if (p.equals("奢辨_??00")) return "奢辨_碟喻00";
                    return p;
                })
                .collect(Collectors.toList());

        int canvasIdx = parts.indexOf("_Canvas");
        if (canvasIdx >= 0 && canvasIdx + 1 < parts.size()) {
            return new ParsedOutlink(new ArrayList<>(parts.subList(canvasIdx + 1, parts.size())), true);
        }
        if (parts.isEmpty()) return null;
        return new ParsedOutlink(parts, false);
    }

    private static WzCanvasProperty findInOpenedWzFiles(List<String> path, DefaultMutableTreeNode treeRoot) {
        if (path.isEmpty()) return null;
        String wzName = path.get(0);

        int childCount = treeRoot.getChildCount();
        for (int i = 0; i < childCount; i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            Object userObj = child.getUserObject();
            if (!(userObj instanceof WzDirectory wzDir)) continue;
            if (!wzDir.isWzFile()) continue;

            String stripped = wzDir.getName().replaceAll("(?i)\\.wz$", "");
            if (!stripped.equals(wzName)) continue;

            WzCanvasProperty found = resolveInWz(wzDir.getChildren(), path, 1);
            if (found != null) return found;
        }
        return null;
    }

    private static WzCanvasProperty resolveInWz(List<? extends WzObject> children, List<String> path, int step) {
        if (step >= path.size()) return null;
        for (WzObject o : children) {
            if (!o.getName().equals(path.get(step))) continue;
            if (step == path.size() - 1) {
                return (o instanceof WzCanvasProperty c) ? c : null;
            }
            if (o instanceof WzImage img) {
                if (!img.parse()) return null;
                return resolveInWz(img.getChildren(), path, step + 1);
            }
            if (o instanceof WzImageProperty p) {
                return resolveInWz(p.getChildren(), path, step + 1);
            }
            return null;
        }
        return null;
    }

    private static WzImage getCanvasImage(String name, List<? extends WzObject> objects) {
        for (WzObject wzObject : objects) {
            if (wzObject instanceof WzDirectory wzDir) {
                WzImage wzImg = getCanvasImage(name, wzDir.getChildren());
                if (wzImg != null) {
                    return wzImg;
                }
            } else if (wzObject instanceof WzImage wzImg) {
                if (wzImg.getName().equals(name)) {
                    return wzImg;
                }
            }
        }
        return null;
    }

    private static WzCanvasProperty getCanvasProperty(List<? extends WzObject> objects, List<String> path, int step) {
        for (WzObject wzObject : objects) {
            if (wzObject.getName().equals(path.get(step))) {
                if (step == path.size() - 1 && wzObject instanceof WzCanvasProperty canvas) {
                    return canvas;
                }
                step++;
                return getCanvasProperty(((WzImageProperty) wzObject).getChildren(), path, step);
            }
        }
        return null;
    }
}
