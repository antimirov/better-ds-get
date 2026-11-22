package com.antimirov.betterdsget.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.antimirov.betterdsget.R;
import com.antimirov.betterdsget.TorrentIntentHelper.IncomingPayload;
import com.antimirov.betterdsget.api.Models.ConnectionInfo;
import com.antimirov.betterdsget.api.Models.FolderEntry;
import com.antimirov.betterdsget.api.Models.TaskFileItem;
import com.antimirov.betterdsget.api.SessionManager;
import com.antimirov.betterdsget.util.AndroidPrefsStore;
import com.antimirov.betterdsget.util.FormatUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Dialogs and modals for Better DS Get:
 * - Settings & Actions Dialog
 * - About Dialog (navigates back to Settings on close)
 * - Connection Info Dialog (navigates back to Settings on close)
 * - Folder Picker Dialog (Recent Folders + NAS share browser)
 * - File Selection Dialog (Selective torrent download)
 * - Add Task Dialog (URL / Magnet / .torrent file upload)
 */
public final class AppDialogs {
    public static final String APP_VERSION = "1.6.0";

    public static String getAppVersion(android.content.Context ctx) {
        if (ctx != null) {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                if (pi != null && pi.versionName != null && !pi.versionName.isEmpty()) {
                    return pi.versionName;
                }
            } catch (Exception ignored) {}
        }
        return APP_VERSION;
    }

    public interface SettingsActionsListener {
        void onResumeAll();
        void onPauseAll();
        void onClearCompleted();
        void onClearFailed();
        void onRemoveAll();
        void onChangeDefaultFolder();
        void onLogout();
    }

    public interface FolderSelectedCallback {
        void onFolderSelected(String path);
    }

    public interface FileSelectionCallback {
        void onConfirmSelectedFiles(List<Integer> wantedIndices);
    }

    public interface AddTaskCallbacks {
        void onPickTorrentFileRequested(Runnable refreshDialogUi);
        void onSubmitUrlTask(String url, String destination, Dialog dialog, Runnable resetButtonState);
        void onSubmitTorrentFile(IncomingPayload filePayload, String destination, Dialog dialog, Runnable resetButtonState);
    }

    private AppDialogs() {}

    // ── Settings & Actions Modal ───────────────────────────────────

    public static void showSettingsDialog(
            Activity activity,
            SessionManager sessionManager,
            String currentDefaultFolder,
            SettingsActionsListener listener
    ) {
        Dialog dialog = createBaseDialog(activity);
        LinearLayout root = createModalCard(activity);

        TextView title = UiTheme.makeText(activity, "Settings & Actions", 20, UiTheme.COLOR_TEXT_PRIMARY, true);
        root.addView(title);

        // Logged-in account row + Connection Info button
        LinearLayout userRow = new LinearLayout(activity);
        userRow.setOrientation(LinearLayout.HORIZONTAL);
        userRow.setGravity(Gravity.CENTER_VERTICAL);
        userRow.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 4));

        TextView uIcon = UiTheme.makeText(activity, "👤", 16, UiTheme.COLOR_TEXT_MUTED, false);
        uIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams uIconLp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 28), ViewGroup.LayoutParams.WRAP_CONTENT);
        uIcon.setLayoutParams(uIconLp);

        String accountName = sessionManager.getCredentials() != null
                ? sessionManager.getCredentials().account
                : "Unknown";
        TextView userTv = UiTheme.makeText(activity, "Logged in as: " + accountName, 13, UiTheme.COLOR_TEXT_MUTED, false);
        userTv.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
        userTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView infoBtn = UiTheme.makeText(activity, "ⓘ", 18, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        infoBtn.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 4), UiTheme.dp(activity, 4), UiTheme.dp(activity, 4));
        infoBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showConnectionInfoDialog(activity, sessionManager, () ->
                    showSettingsDialog(activity, sessionManager, currentDefaultFolder, listener));
        });

        userRow.addView(uIcon);
        userRow.addView(userTv);
        userRow.addView(infoBtn);
        root.addView(userRow);

        root.addView(UiTheme.makeDivider(activity, 8));

        root.addView(makeSettingsActionRow(activity, "▶", "Resume All", UiTheme.COLOR_TEXT_SECONDARY, () -> {
            dialog.dismiss();
            listener.onResumeAll();
        }));
        root.addView(makeSettingsActionRow(activity, "⏸", "Pause All", UiTheme.COLOR_TEXT_SECONDARY, () -> {
            dialog.dismiss();
            listener.onPauseAll();
        }));
        root.addView(makeSettingsActionRow(activity, "✓", "Clear Completed", UiTheme.COLOR_GREEN, () -> {
            dialog.dismiss();
            listener.onClearCompleted();
        }));
        root.addView(makeSettingsActionRow(activity, "⚠", "Remove Failed Tasks", UiTheme.COLOR_AMBER, () -> {
            dialog.dismiss();
            listener.onClearFailed();
        }));
        root.addView(makeSettingsActionRow(activity, "🗑", "Remove All", UiTheme.COLOR_RED, () -> {
            dialog.dismiss();
            listener.onRemoveAll();
        }));

        root.addView(UiTheme.makeDivider(activity, 8));

        // Change Default Download Folder
        LinearLayout folderRow = new LinearLayout(activity);
        folderRow.setOrientation(LinearLayout.HORIZONTAL);
        folderRow.setGravity(Gravity.CENTER_VERTICAL);
        folderRow.setPadding(0, UiTheme.dp(activity, 11), 0, UiTheme.dp(activity, 11));
        android.graphics.drawable.Drawable fSel = UiTheme.makeSelectableBackground(activity);
        if (fSel != null) folderRow.setBackground(fSel);
        folderRow.setClickable(true);
        folderRow.setFocusable(true);
        folderRow.setOnClickListener(v -> {
            dialog.dismiss();
            listener.onChangeDefaultFolder();
        });

        TextView fIcon = UiTheme.makeText(activity, "📁", 18, UiTheme.COLOR_SYNOLOGY_BLUE, false);
        fIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fIconLp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 28), ViewGroup.LayoutParams.WRAP_CONTENT);
        fIcon.setLayoutParams(fIconLp);

        LinearLayout fCol = new LinearLayout(activity);
        fCol.setOrientation(LinearLayout.VERTICAL);
        fCol.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
        fCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        fCol.addView(UiTheme.makeText(activity, "Change Default Download Folder", 15, UiTheme.COLOR_SYNOLOGY_BLUE, true));
        if (currentDefaultFolder != null && !currentDefaultFolder.isEmpty()) {
            TextView sub = UiTheme.makeText(activity, "Current: " + currentDefaultFolder, 12, UiTheme.COLOR_TEXT_MUTED, false);
            sub.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
            fCol.addView(sub);
        }

        folderRow.addView(fIcon);
        folderRow.addView(fCol);
        root.addView(folderRow);

        root.addView(UiTheme.makeDivider(activity, 8));

        // About Better DS Get (opens About popup, and returns to Settings when closed!)
        LinearLayout aboutRow = new LinearLayout(activity);
        aboutRow.setOrientation(LinearLayout.HORIZONTAL);
        aboutRow.setGravity(Gravity.CENTER_VERTICAL);
        aboutRow.setPadding(0, UiTheme.dp(activity, 11), 0, UiTheme.dp(activity, 11));
        android.graphics.drawable.Drawable aSel = UiTheme.makeSelectableBackground(activity);
        if (aSel != null) aboutRow.setBackground(aSel);
        aboutRow.setClickable(true);
        aboutRow.setFocusable(true);
        aboutRow.setOnClickListener(v -> {
            dialog.dismiss();
            showAboutDialog(activity, () ->
                    showSettingsDialog(activity, sessionManager, currentDefaultFolder, listener));
        });

        TextView aIcon = UiTheme.makeText(activity, "ⓘ", 18, UiTheme.COLOR_SYNOLOGY_BLUE, false);
        aIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aIconLp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 28), ViewGroup.LayoutParams.WRAP_CONTENT);
        aIcon.setLayoutParams(aIconLp);

        TextView aboutLabel = UiTheme.makeText(activity, "About Better DS Get", 15, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        aboutLabel.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
        aboutLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView versionLabel = UiTheme.makeText(activity, "v" + getAppVersion(activity) + "  ›", 13, UiTheme.COLOR_TEXT_MUTED, false);

        aboutRow.addView(aIcon);
        aboutRow.addView(aboutLabel);
        aboutRow.addView(versionLabel);
        root.addView(aboutRow);

        root.addView(UiTheme.makeDivider(activity, 8));

        // Logout
        root.addView(makeSettingsActionRow(activity, "⎋", "Logout", UiTheme.COLOR_RED, () -> {
            dialog.dismiss();
            listener.onLogout();
        }));

        // Close button
        TextView closeBtn = UiTheme.makeButton(activity, "Close", Color.parseColor("#444444"), UiTheme.COLOR_TEXT_PRIMARY, v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = UiTheme.dp(activity, 14);
        closeBtn.setLayoutParams(closeLp);
        root.addView(closeBtn);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        dialog.setContentView(scroll);
        showAndFormatDialog(dialog, activity);
    }

    // ── About Sub-Modal (Returns to Settings on Close/Back) ────────

    public static void showAboutDialog(Activity activity, Runnable onReturnToSettings) {
        Dialog dialog = createBaseDialog(activity);
        dialog.setOnCancelListener(d -> {
            if (onReturnToSettings != null) onReturnToSettings.run();
        });

        LinearLayout root = createModalCard(activity);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.app_icon);
        int iconSize = UiTheme.dp(activity, 64);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.bottomMargin = UiTheme.dp(activity, 12);
        iconLp.topMargin = UiTheme.dp(activity, 8);
        icon.setLayoutParams(iconLp);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), UiTheme.dp(activity, 16));
            }
        });
        icon.setClipToOutline(true);
        root.addView(icon);

        TextView appTitle = UiTheme.makeText(activity, "Better DS Get", 18, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        appTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = UiTheme.dp(activity, 4);
        appTitle.setLayoutParams(titleLp);
        root.addView(appTitle);

        TextView verLine = UiTheme.makeText(activity, "Version " + getAppVersion(activity), 13, UiTheme.COLOR_TEXT_MUTED, false);
        verLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.bottomMargin = UiTheme.dp(activity, 2);
        verLine.setLayoutParams(verLp);
        root.addView(verLine);

        TextView authorLine = UiTheme.makeText(activity, "by Yevgen Antymyrov", 13, UiTheme.COLOR_TEXT_MUTED, false);
        authorLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authorLine.setLayoutParams(authLp);
        root.addView(authorLine);

        TextView emailLink = UiTheme.makeText(activity, "android@antimirov.net", 13, UiTheme.COLOR_SYNOLOGY_BLUE, false);
        emailLink.setGravity(Gravity.CENTER);
        emailLink.setPaintFlags(emailLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams emailLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emailLp.topMargin = UiTheme.dp(activity, 6);
        emailLp.bottomMargin = UiTheme.dp(activity, 16);
        emailLink.setLayoutParams(emailLp);
        emailLink.setOnClickListener(v -> {
            try {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:android@antimirov.net"));
                activity.startActivity(emailIntent);
            } catch (Exception ignored) {}
        });
        root.addView(emailLink);

        TextView closeBtn = UiTheme.makeButton(activity, "Close", Color.parseColor("#444444"), UiTheme.COLOR_TEXT_PRIMARY, v -> {
            dialog.dismiss();
            if (onReturnToSettings != null) onReturnToSettings.run();
        });
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(closeBtn);

        dialog.setContentView(root);
        showAndFormatDialog(dialog, activity);
    }

    // ── Connection Info Sub-Modal ──────────────────────────────────

    public static void showConnectionInfoDialog(Activity activity, SessionManager sessionManager, Runnable onReturnToSettings) {
        Dialog dialog = createBaseDialog(activity);
        dialog.setOnCancelListener(d -> {
            if (onReturnToSettings != null) onReturnToSettings.run();
        });

        LinearLayout root = createModalCard(activity);
        ConnectionInfo info = sessionManager.getConnectionInfo();

        TextView title = UiTheme.makeText(activity, "Connection Details", 20, UiTheme.COLOR_TEXT_PRIMARY, true);
        title.setPadding(0, 0, 0, UiTheme.dp(activity, 12));
        root.addView(title);

        String account = sessionManager.getCredentials() != null ? sessionManager.getCredentials().account : "Unknown";
        root.addView(UiTheme.makeInfoRow(activity, "Account", account));
        root.addView(UiTheme.makeDivider(activity, 4));

        if (info.originalAddress != null && !info.originalAddress.equals(info.baseUrl)) {
            root.addView(makeInfoColumn(activity, "Address You Typed", info.originalAddress));
            root.addView(UiTheme.makeDivider(activity, 4));
        }

        root.addView(makeInfoColumn(activity, "Resolved NAS Address", info.baseUrl != null ? info.baseUrl : "N/A"));
        root.addView(UiTheme.makeDivider(activity, 4));

        // QuickConnect Relay with active dot indicator
        LinearLayout qcRow = new LinearLayout(activity);
        qcRow.setOrientation(LinearLayout.HORIZONTAL);
        qcRow.setGravity(Gravity.CENTER_VERTICAL);
        qcRow.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 5));
        TextView qcLabel = UiTheme.makeText(activity, "QuickConnect Relay", 13, UiTheme.COLOR_TEXT_MUTED, false);
        qcLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        qcRow.addView(qcLabel);
        if (info.isQuickConnect) {
            View dot = new View(activity);
            int dotSize = UiTheme.dp(activity, 8);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.rightMargin = UiTheme.dp(activity, 8);
            dot.setLayoutParams(dotLp);
            dot.setBackground(UiTheme.roundedRect(UiTheme.COLOR_GREEN, dotSize / 2));
            qcRow.addView(dot);
        }
        TextView qcVal = UiTheme.makeText(activity, info.isQuickConnect ? "ACTIVE" : "NO", 13,
                info.isQuickConnect ? UiTheme.COLOR_GREEN : UiTheme.COLOR_TEXT_MUTED, true);
        qcRow.addView(qcVal);
        root.addView(qcRow);
        root.addView(UiTheme.makeDivider(activity, 4));

        // Encryption SSL
        LinearLayout sslRow = new LinearLayout(activity);
        sslRow.setOrientation(LinearLayout.HORIZONTAL);
        sslRow.setGravity(Gravity.CENTER_VERTICAL);
        sslRow.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 5));
        TextView sslLabel = UiTheme.makeText(activity, "Encryption (SSL)", 13, UiTheme.COLOR_TEXT_MUTED, false);
        sslLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sslRow.addView(sslLabel);
        TextView sslVal = UiTheme.makeText(activity, info.isHttps ? "HTTPS / SECURE" : "HTTP / INSECURE", 13,
                info.isHttps ? UiTheme.COLOR_GREEN : UiTheme.COLOR_AMBER, true);
        sslRow.addView(sslVal);
        root.addView(sslRow);
        root.addView(UiTheme.makeDivider(activity, 4));

        root.addView(UiTheme.makeInfoRow(activity, "Session ID", FormatUtils.maskSid(info.sid)));

        TextView closeBtn = UiTheme.makeButton(activity, "Close", Color.parseColor("#444444"), UiTheme.COLOR_TEXT_PRIMARY, v -> {
            dialog.dismiss();
            if (onReturnToSettings != null) onReturnToSettings.run();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = UiTheme.dp(activity, 16);
        closeBtn.setLayoutParams(closeLp);
        root.addView(closeBtn);

        dialog.setContentView(root);
        showAndFormatDialog(dialog, activity);
    }

    // ── Folder Picker Modal ────────────────────────────────────────

    public static void showFolderPickerDialog(
            Activity activity,
            SessionManager sessionManager,
            AndroidPrefsStore prefsStore,
            ExecutorService bgExecutor,
            String modalTitle,
            String currentDefaultFolder,
            FolderSelectedCallback callback
    ) {
        Dialog dialog = createBaseDialog(activity);
        LinearLayout root = createBottomSheetCard(activity, UiTheme.COLOR_BG_CARD);

        TextView titleTv = UiTheme.makeText(activity, modalTitle, 20, UiTheme.COLOR_TEXT_PRIMARY, true);
        titleTv.setGravity(Gravity.CENTER);
        root.addView(titleTv);

        TextView pathTv = UiTheme.makeText(activity, "", 13, UiTheme.COLOR_TEXT_MUTED, false);
        pathTv.setGravity(Gravity.CENTER);
        pathTv.setPadding(0, UiTheme.dp(activity, 6), 0, UiTheme.dp(activity, 12));
        root.addView(pathTv);

        ScrollView listScroll = new ScrollView(activity);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiTheme.dp(activity, 320)
        );
        listScroll.setLayoutParams(scrollLp);
        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listContainer);
        root.addView(listScroll);

        LinearLayout actionContainer = new LinearLayout(activity);
        actionContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionContainer);

        TextView cancelBtn = UiTheme.makeButton(activity, "Cancel", Color.TRANSPARENT, UiTheme.COLOR_RED, v -> dialog.dismiss());
        root.addView(cancelBtn);

        final String[] currentPathHolder = new String[]{""};

        final Runnable[] loadFolderRunner = new Runnable[1];
        loadFolderRunner[0] = () -> {
            final String path = currentPathHolder[0];
            pathTv.setText(!path.isEmpty()
                    ? "Path: /" + path
                    : (currentDefaultFolder != null && !currentDefaultFolder.isEmpty()
                    ? "Current default: " + currentDefaultFolder + "\nSelect a share to navigate:"
                    : "Root (Select a share first)"));

            listContainer.removeAllViews();
            actionContainer.removeAllViews();

            ProgressBar spinner = new ProgressBar(activity);
            listContainer.addView(spinner);

            if (!path.isEmpty()) {
                String folderShort = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                TextView selectBtn = UiTheme.makeButton(
                        activity,
                        "Select \"" + folderShort + "\"",
                        UiTheme.COLOR_SYNOLOGY_BLUE,
                        UiTheme.COLOR_TEXT_PRIMARY,
                        v -> {
                            prefsStore.addRecentFolder(path);
                            dialog.dismiss();
                            callback.onFolderSelected(path);
                        }
                );
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.topMargin = UiTheme.dp(activity, 8);
                selectBtn.setLayoutParams(lp);
                actionContainer.addView(selectBtn);
            }

            bgExecutor.execute(() -> {
                try {
                    List<FolderEntry> entries = sessionManager.execute(() ->
                            sessionManager.getDs().listFolders(path));
                    activity.runOnUiThread(() -> {
                        listContainer.removeAllViews();

                        // Recent folders at root
                        if (path.isEmpty()) {
                            List<String> recents = prefsStore.getRecentFolders();
                            if (!recents.isEmpty()) {
                                listContainer.addView(UiTheme.makeText(activity, "RECENT FOLDERS:", 12, UiTheme.COLOR_SYNOLOGY_BLUE, true));
                                for (String recentPath : recents) {
                                    TextView rRow = UiTheme.makeText(activity, "🕒  " + recentPath, 15, UiTheme.COLOR_TEXT_SECONDARY, false);
                                    rRow.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
                                    rRow.setOnClickListener(v -> {
                                        prefsStore.addRecentFolder(recentPath);
                                        dialog.dismiss();
                                        callback.onFolderSelected(recentPath);
                                    });
                                    listContainer.addView(rRow);
                                }
                                listContainer.addView(UiTheme.makeDivider(activity, 6));
                                listContainer.addView(UiTheme.makeText(activity, "ALL SHARES:", 12, UiTheme.COLOR_SYNOLOGY_BLUE, true));
                            }
                        } else {
                            // Go up row
                            TextView upRow = UiTheme.makeText(activity, "↰  .. (Go up)", 15, UiTheme.COLOR_TEXT_MUTED, true);
                            upRow.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
                            upRow.setOnClickListener(v -> {
                                String trimmed = path.startsWith("/") ? path.substring(1) : path;
                                int idx = trimmed.lastIndexOf('/');
                                currentPathHolder[0] = idx > 0 ? "/" + trimmed.substring(0, idx) : "";
                                loadFolderRunner[0].run();
                            });
                            listContainer.addView(upRow);
                        }

                        if (entries.isEmpty()) {
                            TextView empty = UiTheme.makeText(activity, "No subfolders found.", 14, UiTheme.COLOR_TEXT_MUTED, false);
                            empty.setPadding(0, UiTheme.dp(activity, 16), 0, UiTheme.dp(activity, 16));
                            listContainer.addView(empty);
                        } else {
                            for (FolderEntry entry : entries) {
                                LinearLayout fRow = new LinearLayout(activity);
                                fRow.setOrientation(LinearLayout.VERTICAL);
                                fRow.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
                                fRow.addView(UiTheme.makeText(activity, "📁  " + entry.name, 15, UiTheme.COLOR_TEXT_SECONDARY, true));
                                fRow.addView(UiTheme.makeText(activity, "      " + entry.path, 12, UiTheme.COLOR_TEXT_MUTED, false));
                                fRow.setOnClickListener(v -> {
                                    currentPathHolder[0] = entry.path;
                                    loadFolderRunner[0].run();
                                });
                                listContainer.addView(fRow);
                            }
                        }
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        listContainer.removeAllViews();
                        Toast.makeText(activity, "Failed to fetch folders: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        };

        loadFolderRunner[0].run();
        dialog.setContentView(root);
        showAndFormatBottomSheet(dialog, activity);
    }

    // ── File Selection Modal (Selective Torrent Download) ──────────

    public static void showFileSelectionDialog(
            Activity activity,
            List<TaskFileItem> files,
            FileSelectionCallback callback
    ) {
        Dialog dialog = createBaseDialog(activity);
        LinearLayout root = createBottomSheetCard(activity, UiTheme.COLOR_BG_CARD);

        final Set<Integer> selectedIndices = new HashSet<>();
        for (TaskFileItem f : files) {
            selectedIndices.add(f.index);
        }

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = UiTheme.makeText(activity, "Select Files (" + selectedIndices.size() + "/" + files.size() + ")", 18, UiTheme.COLOR_TEXT_PRIMARY, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView toggleAllBtn = UiTheme.makeText(activity, "Deselect All", 14, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        header.addView(title);
        header.addView(toggleAllBtn);
        root.addView(header);

        ListView listView = new ListView(activity);
        listView.setDivider(new ColorDrawable(Color.parseColor("#2A2A2A")));
        listView.setDividerHeight(UiTheme.dp(activity, 1));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));

        int listHeight = Math.min(
                UiTheme.dp(activity, 340),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.50)
        );
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
        );
        listLp.topMargin = UiTheme.dp(activity, 10);
        listLp.bottomMargin = UiTheme.dp(activity, 10);
        listView.setLayoutParams(listLp);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return files.size();
            }

            @Override
            public TaskFileItem getItem(int position) {
                return files.get(position);
            }

            @Override
            public long getItemId(int position) {
                return files.get(position).index;
            }

            class ViewHolder {
                TextView nameTv;
                TextView sizeTv;
                Switch sw;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 8));

                    LinearLayout infoCol = new LinearLayout(activity);
                    infoCol.setOrientation(LinearLayout.VERTICAL);
                    infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    TextView nameTv = UiTheme.makeText(activity, "", 14, UiTheme.COLOR_TEXT_SECONDARY, false);
                    nameTv.setMaxLines(2);
                    nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    TextView sizeTv = UiTheme.makeText(activity, "", 12, UiTheme.COLOR_TEXT_MUTED, false);
                    infoCol.addView(nameTv);
                    infoCol.addView(sizeTv);

                    Switch sw = new Switch(activity);
                    UiTheme.styleSwitch(sw);
                    sw.setFocusable(false);
                    sw.setClickable(false);

                    row.addView(infoCol);
                    row.addView(sw);

                    holder = new ViewHolder();
                    holder.nameTv = nameTv;
                    holder.sizeTv = sizeTv;
                    holder.sw = sw;
                    row.setTag(holder);
                    convertView = row;
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                TaskFileItem item = getItem(position);
                holder.nameTv.setText(item.name);
                holder.sizeTv.setText(FormatUtils.formatBytes(item.size));
                holder.sw.setChecked(selectedIndices.contains(item.index));

                convertView.setOnClickListener(v -> {
                    if (selectedIndices.contains(item.index)) {
                        selectedIndices.remove(item.index);
                        holder.sw.setChecked(false);
                    } else {
                        selectedIndices.add(item.index);
                        holder.sw.setChecked(true);
                    }
                    toggleAllBtn.setText(selectedIndices.size() == files.size() ? "Deselect All" : "Select All");
                    title.setText("Select Files (" + selectedIndices.size() + "/" + files.size() + ")");
                });

                return convertView;
            }
        };

        listView.setAdapter(adapter);
        root.addView(listView);

        toggleAllBtn.setOnClickListener(v -> {
            boolean selectAll = selectedIndices.size() != files.size();
            selectedIndices.clear();
            if (selectAll) {
                for (TaskFileItem f : files) {
                    selectedIndices.add(f.index);
                }
                toggleAllBtn.setText("Deselect All");
            } else {
                toggleAllBtn.setText("Select All");
            }
            title.setText("Select Files (" + selectedIndices.size() + "/" + files.size() + ")");
            adapter.notifyDataSetChanged();
        });

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelBtn = UiTheme.makeButton(activity, "Cancel", Color.parseColor("#333333"), UiTheme.COLOR_TEXT_SECONDARY, v -> dialog.dismiss());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = UiTheme.dp(activity, 8);
        cancelBtn.setLayoutParams(lp1);

        final TextView[] confirmBtnHolder = new TextView[1];
        TextView confirmBtn = UiTheme.makeButton(activity, "Start Download", UiTheme.COLOR_SYNOLOGY_BLUE, UiTheme.COLOR_TEXT_PRIMARY, v -> {
            if (selectedIndices.isEmpty()) {
                Toast.makeText(activity, "Please select at least one file", Toast.LENGTH_SHORT).show();
                return;
            }
            if (confirmBtnHolder[0] != null) {
                confirmBtnHolder[0].setEnabled(false);
                confirmBtnHolder[0].setText("Starting...");
            }
            dialog.dismiss();
            callback.onConfirmSelectedFiles(new ArrayList<>(selectedIndices));
        });
        confirmBtnHolder[0] = confirmBtn;
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmBtn.setLayoutParams(lp2);

        footer.addView(cancelBtn);
        footer.addView(confirmBtn);
        root.addView(footer);

        dialog.setContentView(root);
        showAndFormatBottomSheet(dialog, activity);
    }

    public static Dialog showLoadingDialog(Activity activity, String message) {
        Dialog dialog = createBaseDialog(activity);
        dialog.setCancelable(false);
        LinearLayout root = createModalCard(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UiTheme.dp(activity, 20);
        root.setPadding(pad, pad, pad, pad);

        ProgressBar pb = new ProgressBar(activity);
        pb.setIndeterminate(true);
        root.addView(pb);

        TextView tv = UiTheme.makeText(activity, message, 15, UiTheme.COLOR_TEXT_PRIMARY, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = UiTheme.dp(activity, 16);
        tv.setLayoutParams(lp);
        root.addView(tv);

        dialog.setContentView(root);
        showAndFormatDialog(dialog, activity);
        return dialog;
    }

    // ── Add Task Modal (URL / Magnet / .torrent File) ──────────────

    public static void showAddTaskDialog(
            Activity activity,
            SessionManager sessionManager,
            AndroidPrefsStore prefsStore,
            ExecutorService bgExecutor,
            String initialDestination,
            String defaultDestination,
            IncomingPayload[] activePayloadHolder,
            boolean isIntentMode,
            AddTaskCallbacks callbacks
    ) {
        Dialog dialog = createBaseDialog(activity);
        LinearLayout root = createBottomSheetCard(activity, UiTheme.COLOR_BG_MODAL);

        final String[] chosenFolder = new String[]{
                (initialDestination != null && !initialDestination.isEmpty()) ? initialDestination : defaultDestination
        };
        final boolean[] hasCustomFolderSelected = new boolean[]{false};

        final Runnable[] renderBody = new Runnable[1];
        renderBody[0] = () -> {
            root.removeAllViews();
            IncomingPayload payload = activePayloadHolder[0];
            boolean hasTorrentFile = payload != null && payload.isTorrentFile;

            String headerTitle = hasTorrentFile
                    ? "Add Torrent File"
                    : (isIntentMode ? "Add Magnet Task" : "Add New Task");
            TextView titleTv = UiTheme.makeText(activity, headerTitle, 20, UiTheme.COLOR_SYNOLOGY_BLUE, true);
            titleTv.setGravity(Gravity.CENTER);
            titleTv.setPadding(0, 0, 0, UiTheme.dp(activity, 16));
            root.addView(titleTv);

            final EditText urlInput = UiTheme.makeInput(activity, "magnet:?xt=urn:btih:...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            if (payload != null && !payload.isTorrentFile && payload.urlOrMagnet != null) {
                urlInput.setText(payload.urlOrMagnet);
            }

            if (hasTorrentFile) {
                root.addView(UiTheme.makeText(activity, "Selected Torrent File", 13, UiTheme.COLOR_TEXT_MUTED, true));
                LinearLayout fileBox = new LinearLayout(activity);
                fileBox.setOrientation(LinearLayout.VERTICAL);
                int p = UiTheme.dp(activity, 12);
                fileBox.setPadding(p, p, p, p);
                fileBox.setBackground(UiTheme.roundedRectWithBorder(UiTheme.COLOR_BG_INPUT, Color.parseColor("#444444"), UiTheme.dp(activity, 1), UiTheme.dp(activity, 8)));
                LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                boxLp.topMargin = UiTheme.dp(activity, 6);
                boxLp.bottomMargin = UiTheme.dp(activity, 14);
                fileBox.setLayoutParams(boxLp);

                fileBox.addView(UiTheme.makeText(activity, "📄  " + payload.fileName, 14, UiTheme.COLOR_TEXT_PRIMARY, true));
                fileBox.addView(UiTheme.makeText(activity, "Ready to upload (" + FormatUtils.formatBytes(payload.fileBytes != null ? payload.fileBytes.length : 0) + ")", 11, UiTheme.COLOR_TEXT_MUTED, false));
                root.addView(fileBox);
            } else {
                root.addView(UiTheme.makeText(activity, "URL or Magnet Link", 13, UiTheme.COLOR_TEXT_MUTED, true));
                LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                urlLp.topMargin = UiTheme.dp(activity, 6);
                urlLp.bottomMargin = UiTheme.dp(activity, 14);
                urlInput.setLayoutParams(urlLp);
                root.addView(urlInput);
            }

            // Destination Folder selector
            root.addView(UiTheme.makeText(activity, "Download Destination", 13, UiTheme.COLOR_TEXT_MUTED, true));
            String folderDisplay;
            if (chosenFolder[0] != null && !chosenFolder[0].isEmpty()) {
                boolean isDefault = !hasCustomFolderSelected[0]
                        && defaultDestination != null
                        && chosenFolder[0].equals(defaultDestination);
                folderDisplay = isDefault ? chosenFolder[0] + " (Default)" : chosenFolder[0];
            } else {
                folderDisplay = "Select Folder...";
            }
            TextView folderBtn = UiTheme.makeText(activity, "📁  " + folderDisplay + "   ›", 14, UiTheme.COLOR_TEXT_PRIMARY, false);
            int pad = UiTheme.dp(activity, 12);
            folderBtn.setPadding(pad, pad, pad, pad);
            folderBtn.setBackground(UiTheme.roundedRectWithBorder(UiTheme.COLOR_BG_CARD, Color.parseColor("#444444"), UiTheme.dp(activity, 1), UiTheme.dp(activity, 8)));
            LinearLayout.LayoutParams folderLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            folderLp.topMargin = UiTheme.dp(activity, 6);
            folderLp.bottomMargin = UiTheme.dp(activity, 16);
            folderBtn.setLayoutParams(folderLp);
            folderBtn.setOnClickListener(v -> showFolderPickerDialog(
                    activity,
                    sessionManager,
                    prefsStore,
                    bgExecutor,
                    "Select Destination",
                    defaultDestination,
                    selected -> {
                        chosenFolder[0] = selected;
                        hasCustomFolderSelected[0] = true;
                        renderBody[0].run();
                    }
            ));
            root.addView(folderBtn);

            // Primary Submit Button
            if (hasTorrentFile) {
                final TextView[] uploadBtnHolder = new TextView[1];
                TextView uploadBtn = UiTheme.makeButton(activity, "Upload Torrent", UiTheme.COLOR_GREEN, UiTheme.COLOR_TEXT_PRIMARY, v -> {
                    UiTheme.hideKeyboard(activity, null);
                    if (uploadBtnHolder[0] != null) {
                        uploadBtnHolder[0].setEnabled(false);
                        uploadBtnHolder[0].setText("Uploading Torrent...");
                    }
                    callbacks.onSubmitTorrentFile(payload, chosenFolder[0], dialog, () -> {
                        if (uploadBtnHolder[0] != null) {
                            uploadBtnHolder[0].setEnabled(true);
                            uploadBtnHolder[0].setText("Upload Torrent");
                        }
                    });
                });
                uploadBtnHolder[0] = uploadBtn;
                root.addView(uploadBtn);
            } else {
                final TextView[] dlUrlBtnHolder = new TextView[1];
                TextView dlUrlBtn = UiTheme.makeButton(activity, "Download from URL", UiTheme.COLOR_GREEN, UiTheme.COLOR_TEXT_PRIMARY, v -> {
                    UiTheme.hideKeyboard(activity, null);
                    String trimmedUrl = urlInput.getText().toString().trim();
                    if (trimmedUrl.isEmpty()) {
                        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Error")
                                .setMessage("Please enter a URL or magnet link")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                    if (dlUrlBtnHolder[0] != null) {
                        dlUrlBtnHolder[0].setEnabled(false);
                        dlUrlBtnHolder[0].setText("Adding Task...");
                    }
                    callbacks.onSubmitUrlTask(trimmedUrl, chosenFolder[0], dialog, () -> {
                        if (dlUrlBtnHolder[0] != null) {
                            dlUrlBtnHolder[0].setEnabled(true);
                            dlUrlBtnHolder[0].setText("Download from URL");
                        }
                    });
                });
                dlUrlBtnHolder[0] = dlUrlBtn;
                root.addView(dlUrlBtn);
            }

            // Secondary "Upload .torrent File" button when not opened via external intent
            if (!isIntentMode && !hasTorrentFile) {
                root.addView(UiTheme.makeDivider(activity, 12));
                TextView pickFileBtn = UiTheme.makeButton(activity, "Upload .torrent File", UiTheme.COLOR_SYNOLOGY_BLUE, UiTheme.COLOR_TEXT_PRIMARY, v -> {
                    UiTheme.hideKeyboard(activity, null);
                    callbacks.onPickTorrentFileRequested(renderBody[0]);
                });
                root.addView(pickFileBtn);
            }

            TextView cancelBtn = UiTheme.makeButton(activity, "Cancel", Color.TRANSPARENT, UiTheme.COLOR_TEXT_MUTED, v -> {
                UiTheme.hideKeyboard(activity, null);
                dialog.dismiss();
            });
            LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cancelLp.topMargin = UiTheme.dp(activity, 8);
            cancelBtn.setLayoutParams(cancelLp);
            root.addView(cancelBtn);
        };

        renderBody[0].run();
        dialog.setContentView(root);
        showAndFormatBottomSheet(dialog, activity);
    }

    public static void showConfirmDialog(Activity activity, String title, String message, String confirmLabel, Runnable onConfirm) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(confirmLabel, (d, w) -> {
                    if (onConfirm != null) onConfirm.run();
                })
                .setCancelable(true)
                .show();
    }

    // ── Internal Dialog Builders ───────────────────────────────────

    public static void showAndFormatDialog(Dialog dialog, Activity activity) {
        dialog.show();
        if (dialog.getWindow() != null) {
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int targetWidth = (int) (screenWidth * 0.90);
            int maxWidth = UiTheme.dp(activity, 420);
            if (targetWidth > maxWidth && maxWidth > 0) {
                targetWidth = maxWidth;
            }
            dialog.getWindow().setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }
    }

    public static void showAndFormatBottomSheet(Dialog dialog, Activity activity) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setWindowAnimations(android.R.style.Animation_InputMethod);
        }
    }

    private static Dialog createBaseDialog(Activity activity) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private static LinearLayout createBottomSheetCard(Activity activity, int bgColor) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = UiTheme.dp(activity, 24);
        card.setPadding(pad, pad, pad, UiTheme.dp(activity, 28));
        card.setBackground(UiTheme.topRoundedRect(bgColor, UiTheme.dp(activity, 20)));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return card;
    }

    private static LinearLayout createModalCard(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = UiTheme.dp(activity, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_MODAL, UiTheme.dp(activity, 16)));
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int minW = (int) (screenWidth * 0.85);
        int maxW = UiTheme.dp(activity, 400);
        card.setMinimumWidth(Math.min(minW, maxW));
        return card;
    }

    private static LinearLayout makeInfoColumn(Activity activity, String label, String value) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 4));

        TextView lbl = UiTheme.makeText(activity, label, 13, UiTheme.COLOR_TEXT_MUTED, false);
        col.addView(lbl);

        TextView val = UiTheme.makeText(activity, value, 13, UiTheme.COLOR_TEXT_SECONDARY, false);
        val.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
        col.addView(val);
        return col;
    }

    private static View makeSettingsActionRow(Activity activity, String icon, String label, int color, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiTheme.dp(activity, 11), 0, UiTheme.dp(activity, 11));
        row.setClickable(true);
        row.setFocusable(true);
        android.graphics.drawable.Drawable sel = UiTheme.makeSelectableBackground(activity);
        if (sel != null) row.setBackground(sel);

        TextView iconTv = UiTheme.makeText(activity, icon, 18, color, false);
        iconTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 28), ViewGroup.LayoutParams.WRAP_CONTENT);
        iconTv.setLayoutParams(icLp);

        TextView lbl = UiTheme.makeText(activity, label, 15, color, true);
        lbl.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(iconTv);
        row.addView(lbl);
        row.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return row;
    }
}

