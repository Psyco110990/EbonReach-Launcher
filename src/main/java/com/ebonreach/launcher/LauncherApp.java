package com.ebonreach.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LauncherApp extends Application {

    private static final int W = 800, H = 450;

    private final Config config = Config.load();
    private final Label status = new Label("Ready.");
    private final Label detail = new Label("");
    private final ProgressBar bar = new ProgressBar(0);
    private final Button action = new Button("Check for updates");
    private final Label updateText = new Label();
    private final Hyperlink updateLink = new Hyperlink("Download it");
    private final HBox updateBanner = new HBox(8, updateText, updateLink);
    private final CheckBox dxvkBox = new CheckBox("Modern graphics (DXVK)");
    private final CheckBox hdBox = new CheckBox("HD textures (large download)");

    private List<Patcher.FileEntry> pending = null;

    /** Number of game clients this launcher has started that are still alive. */
    private final AtomicInteger running = new AtomicInteger(0);

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();

        InputStream bg = getClass().getResourceAsStream("/background.png");
        if (bg != null) {
            Image img = new Image(bg);
            ImageView iv = new ImageView(img);
            double keep = 0.66;
            iv.setViewport(new javafx.geometry.Rectangle2D(
                    0, 0, img.getWidth(), img.getHeight() * keep));
            iv.setFitWidth(W);
            iv.setFitHeight(H);
            iv.setPreserveRatio(false);
            root.getChildren().add(iv);
        } else {
            root.setStyle("-fx-background-color: #1a1620;");
        }


        Region scrim = new Region();
        scrim.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0.0, Color.rgb(8, 6, 12, 0.10)),
                        new Stop(0.55, Color.rgb(8, 6, 12, 0.45)),
                        new Stop(1.0, Color.rgb(8, 6, 12, 0.97))),
                null, null)));
        root.getChildren().add(scrim);

        status.setStyle("-fx-text-fill: #ddd4ea; -fx-font-size: 14px;");
        detail.setStyle("-fx-text-fill: #9d94ad; -fx-font-size: 11px;");

        bar.setPrefWidth(520);
        bar.setStyle("-fx-accent: #9b7fc4;");

        action.setPrefWidth(180);
        action.setStyle("-fx-background-color: rgba(74,61,92,0.92);"
                      + "-fx-text-fill: #ece5f5; -fx-font-size: 15px;"
                      + "-fx-padding: 10 24 10 24; -fx-background-radius: 3;"
                      + "-fx-border-color: rgba(155,127,196,0.5); -fx-border-radius: 3;");
        action.setOnAction(e -> onAction(stage));

        Label brand = new Label("EbonReach");
        brand.setFont(Font.font("Georgia", 40));
        brand.setStyle("-fx-text-fill: #e6dcf2;"
                     + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.9), 14, 0.4, 0, 2);");
        StackPane.setAlignment(brand, Pos.TOP_CENTER);
        StackPane.setMargin(brand, new Insets(26, 0, 0, 0));
        root.getChildren().add(brand);

        // Shown only when the manifest announces a newer launcher than this one.
        updateText.setStyle("-fx-text-fill: #ece5f5; -fx-font-size: 13px;");
        updateLink.setStyle("-fx-text-fill: #c9b3ec; -fx-font-size: 13px; -fx-border-color: transparent;");
        updateBanner.setAlignment(Pos.CENTER);
        updateBanner.setPadding(new Insets(6, 14, 6, 14));
        updateBanner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        updateBanner.setStyle("-fx-background-color: rgba(74,61,92,0.92); -fx-background-radius: 3;"
                            + "-fx-border-color: rgba(155,127,196,0.6); -fx-border-radius: 3;");
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);
        StackPane.setAlignment(updateBanner, Pos.TOP_CENTER);
        StackPane.setMargin(updateBanner, new Insets(84, 0, 0, 0));
        root.getChildren().add(updateBanner);

        dxvkBox.setSelected(config.dxvk);
        dxvkBox.setStyle("-fx-text-fill: #9d94ad; -fx-font-size: 12px;");
        dxvkBox.setTooltip(new Tooltip("Runs the game through Vulkan: usually smoother and fewer glitches on modern\n"
                + "graphics cards. Needs a Vulkan-capable GPU. Untick it if the game won't start."));
        dxvkBox.setOnAction(e -> {
            config.dxvk = dxvkBox.isSelected();
            config.save();
            applyDxvk();
        });

        hdBox.setSelected(config.hdTextures);
        hdBox.setStyle("-fx-text-fill: #9d94ad; -fx-font-size: 12px;");
        hdBox.setTooltip(new Tooltip("Higher-resolution textures for the whole world. Only downloaded if you tick this;\n"
                + "untick it to go back to the original textures without downloading them again."));
        hdBox.setVisible(false);   // shown once the patch server says it offers the pack
        hdBox.setManaged(false);
        hdBox.setOnAction(e -> {
            if (running.get() > 0) {
                hdBox.setSelected(config.hdTextures);
                detail.setText("Close the game before changing textures.");
                return;
            }
            config.hdTextures = hdBox.isSelected();
            config.save();
            checkForUpdates();     // swaps the files and lists any download needed
        });

        VBox controls = new VBox(12, status, bar, detail, action, dxvkBox, hdBox);
        controls.setAlignment(Pos.BOTTOM_CENTER);
        controls.setPadding(new Insets(0, 0, 28, 0));
        controls.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        root.getChildren().add(controls);

        stage.setScene(new Scene(root, W, H));
        stage.setTitle("EbonReach");
        stage.setResizable(false);
        stage.show();

        checkForUpdates();
    }

    private void onAction(Stage stage) {
        if (pending == null)        checkForUpdates();
        else if (pending.isEmpty()) play(stage);
        else                        downloadPending();
    }

    private void checkForUpdates() {
        action.setDisable(true);
        status.setText("Checking for updates...");
        detail.setText("");
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        java.util.concurrent.atomic.AtomicReference<Patcher.LauncherInfo> info =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> packNote =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean hdOffered = new java.util.concurrent.atomic.AtomicBoolean();

        Task<List<Patcher.FileEntry>> task = new Task<>() {
            protected List<Patcher.FileEntry> call() throws Exception {
                Patcher p = new Patcher(config.serverUrl, config.installPath());
                List<Patcher.FileEntry> manifest = p.fetchManifest();
                info.set(p.launcherInfo());

                // HD textures (3.2): swap files to match the tickbox before
                // checking, then check the manifest as the tickbox sees it.
                OptionalPack hd = OptionalPack.HD_TEXTURES;
                hdOffered.set(hd.offered(manifest));
                try {
                    packNote.set(hd.apply(config.installPath(), manifest, config.hdTextures));
                } catch (Exception ex) {
                    packNote.set("Couldn't switch textures: " + ex.getMessage());
                }
                return p.findOutdated(hd.filter(manifest, config.hdTextures));
            }
        };

        task.setOnSucceeded(e -> {
            showLauncherUpdate(info.get());
            hdBox.setVisible(hdOffered.get());
            hdBox.setManaged(hdOffered.get());
            pending = task.getValue();
            if (pending.isEmpty()) applyDxvk();   // install is complete; make DXVK match the tickbox
            bar.setProgress(0);
            action.setDisable(false);
            if (pending.isEmpty()) {
                status.setText("Up to date.");
                detail.setText(packNote.get() != null ? packNote.get() : config.installPath().toString());
                bar.setProgress(1);
                updateActionLabel();
            } else {
                long bytes = pending.stream().mapToLong(Patcher.FileEntry::size).sum();
                status.setText(pending.size() + " file(s) to update");
                detail.setText(human(bytes) + " to download");
                action.setText("Update");
            }
        });

        task.setOnFailed(e -> {
            bar.setProgress(0);
            action.setDisable(false);
            action.setText("Retry");
            pending = null;
            status.setText("Could not reach the update server.");
            Throwable ex = task.getException();
            detail.setText(ex == null ? "" : ex.getMessage());
        });

        new Thread(task, "check").start();
    }

    private void downloadPending() {
        action.setDisable(true);
        status.setText("Downloading...");
        bar.setProgress(0);

        Task<Void> task = new Task<>() {
            protected Void call() throws Exception {
                new Patcher(config.serverUrl, config.installPath())
                    .download(pending, new Patcher.Progress() {
                        public void onFile(String path, int i, int total) {
                            Platform.runLater(() ->
                                detail.setText("[" + i + "/" + total + "] " + path));
                        }
                        public void onBytes(long done, long total) {
                            Platform.runLater(() -> {
                                bar.setProgress((double) done / total);
                                status.setText("Downloading  "
                                    + human(done) + " / " + human(total));
                            });
                        }
                    });
                return null;
            }
        };

        task.setOnSucceeded(e -> checkForUpdates());
        task.setOnFailed(e -> {
            action.setDisable(false);
            action.setText("Retry");
            pending = null;
            status.setText("Download failed.");
            Throwable ex = task.getException();
            detail.setText(ex == null ? "" : ex.getMessage());
        });

        new Thread(task, "download").start();
    }

    /** Puts DXVK in place or removes it, to match the tickbox. */
    private void applyDxvk() {
        try {
            String msg = Dxvk.apply(config.installPath(), config.dxvk);
            if (msg != null) detail.setText(msg);
        } catch (Exception ex) {
            detail.setText("Couldn't change graphics mode: " + ex.getMessage()
                    + (running.get() > 0 ? " (close the game first)" : ""));
        }
    }

    private void play(Stage stage) {
        Path exe = config.installPath().resolve("sb.exe");
        if (!Files.exists(exe)) {
            status.setText("sb.exe not found.");
            detail.setText(exe.toString());
            return;
        }
        try {
            Process proc = new ProcessBuilder(exe.toString())
                .directory(new File(config.installPath().toString()))
                .start();

            running.incrementAndGet();
            status.setText("Game launched.");
            updateActionLabel();
            updateRunningDetail();

            // Keep the launcher open. When a client exits, drop the count.
            proc.onExit().thenRun(() -> Platform.runLater(() -> {
                running.decrementAndGet();
                updateActionLabel();
                updateRunningDetail();
            }));
        } catch (Exception ex) {
            status.setText("Could not start the game.");
            detail.setText(ex.getMessage());
        }
    }

    /** Shows the "new launcher available" banner if the server announces a newer version. */
    private void showLauncherUpdate(Patcher.LauncherInfo li) {
        boolean show = li != null && Version.isNewer(li.version(), Version.CURRENT);
        if (show) {
            String text = "A new launcher (v" + li.version() + ") is available.";
            if (!li.notes().isBlank()) text += " " + li.notes();
            updateText.setText(text);
            updateLink.setOnAction(e -> getHostServices().showDocument(li.url()));
        }
        updateBanner.setVisible(show);
        updateBanner.setManaged(show);
    }

    private void updateActionLabel() {
        int n = running.get();
        action.setText(n == 0 ? "Play" : "Play (" + n + " running)");
    }

    private void updateRunningDetail() {
        int n = running.get();
        detail.setText(n == 0
                ? config.installPath().toString()
                : n + (n == 1 ? " client running" : " clients running"));
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    public static void main(String[] args) { launch(args); }
}




