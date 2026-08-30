package com.ebonreach.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LauncherApp extends Application {

    private final Config config = Config.load();
    private final Label status = new Label("Ready.");
    private final Label detail = new Label("");
    private final ProgressBar bar = new ProgressBar(0);
    private final Button action = new Button("Check for updates");

    private List<Patcher.FileEntry> pending = null;

    @Override
    public void start(Stage stage) {
        Label title = new Label("EbonReach");
        title.setFont(Font.font("Georgia", 32));
        title.setStyle("-fx-text-fill: #d8d0e8;");

        status.setStyle("-fx-text-fill: #b8b0c8;");
        detail.setStyle("-fx-text-fill: #7a7488; -fx-font-size: 11px;");

        bar.setPrefWidth(460);
        bar.setStyle("-fx-accent: #8a6fb0;");

        action.setPrefWidth(160);
        action.setStyle("-fx-background-color: #4a3d5c; -fx-text-fill: #e8e0f0;"
                      + "-fx-font-size: 14px; -fx-padding: 8 20 8 20;");
        action.setOnAction(e -> onAction(stage));

        VBox root = new VBox(14, title, status, bar, detail, action);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(36));
        root.setStyle("-fx-background-color: #1a1620;");

        stage.setScene(new Scene(root, 540, 300));
        stage.setTitle("EbonReach Launcher");
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

        Task<List<Patcher.FileEntry>> task = new Task<>() {
            protected List<Patcher.FileEntry> call() throws Exception {
                Patcher p = new Patcher(config.serverUrl, config.installPath());
                return p.findOutdated(p.fetchManifest());
            }
        };

        task.setOnSucceeded(e -> {
            pending = task.getValue();
            bar.setProgress(0);
            action.setDisable(false);
            if (pending.isEmpty()) {
                status.setText("Up to date.");
                detail.setText(config.installPath().toString());
                bar.setProgress(1);
                action.setText("Play");
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

    private void play(Stage stage) {
        Path exe = config.installPath().resolve("sb.exe");
        if (!Files.exists(exe)) {
            status.setText("sb.exe not found.");
            detail.setText(exe.toString());
            return;
        }
        try {
            new ProcessBuilder(exe.toString())
                .directory(new File(config.installPath().toString()))
                .start();
            Platform.exit();
        } catch (Exception ex) {
            status.setText("Could not start the game.");
            detail.setText(ex.getMessage());
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    public static void main(String[] args) { launch(args); }
}
