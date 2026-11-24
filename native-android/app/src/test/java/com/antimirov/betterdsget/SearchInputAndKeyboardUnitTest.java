package com.antimirov.betterdsget;

import com.antimirov.betterdsget.ui.AppDialogs;
import com.antimirov.betterdsget.ui.UiTheme;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying search input cursor styling, theme configuration,
 * and keyboard dismissal helper safety.
 */
public class SearchInputAndKeyboardUnitTest {

    @Test
    public void testHideKeyboardNullSafety() {
        // Must safely execute with null activity or null view without throwing any NPE
        UiTheme.hideKeyboard(null, null);
    }

    @Test
    public void testCursorCyanDrawableResourceConfigured() throws Exception {
        File drawableFile = new File("src/main/res/drawable/cursor_cyan.xml");
        assertTrue("cursor_cyan.xml must exist in res/drawable", drawableFile.exists());

        String content = new String(Files.readAllBytes(drawableFile.toPath()));
        assertTrue("Drawable must specify shape rectangle", content.contains("android:shape=\"rectangle\""));
        assertTrue("Drawable must have width 2dp for clear cursor visibility", content.contains("android:width=\"2dp\""));
        assertTrue("Drawable must use Synology blue #00A1E4", content.contains("#00A1E4"));
    }

    @Test
    public void testStylesAppThemeConfiguresCyanCursor() throws Exception {
        File stylesFile = new File("src/main/res/values/styles.xml");
        assertTrue("styles.xml must exist in res/values", stylesFile.exists());

        String content = new String(Files.readAllBytes(stylesFile.toPath()));
        assertTrue("AppTheme must configure android:textCursorDrawable",
                content.contains("<item name=\"android:textCursorDrawable\">@drawable/cursor_cyan</item>"));
        assertTrue("AppTheme must configure android:colorControlActivated",
                content.contains("<item name=\"android:colorControlActivated\">#00A1E4</item>"));
    }

    @Test
    public void testManifestConfiguresSoftInputMode() throws Exception {
        File manifestFile = new File("src/main/AndroidManifest.xml");
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists());

        String content = new String(Files.readAllBytes(manifestFile.toPath()));
        assertTrue("MainActivity must configure windowSoftInputMode with adjustResize|stateHidden",
                content.contains("android:windowSoftInputMode=\"adjustResize|stateHidden\""));
    }

    @Test
    public void testUiThemeMakeInputSetsCursorDrawable() throws Exception {
        File uiThemeFile = new File("src/main/java/com/antimirov/betterdsget/ui/UiTheme.java");
        assertTrue("UiTheme.java must exist", uiThemeFile.exists());

        String content = new String(Files.readAllBytes(uiThemeFile.toPath()));
        assertTrue("UiTheme.makeInput must set cursor visible", content.contains("et.setCursorVisible(true)"));
        assertTrue("UiTheme.makeInput must configure cursor cyan drawable",
                content.contains("setTextCursorDrawable(R.drawable.cursor_cyan)"));
        assertTrue("UiTheme must include hideKeyboard method",
                content.contains("public static void hideKeyboard("));
    }

    @Test
    public void testSearchScreenVectorDrawablesExist() throws Exception {
        String[] requiredDrawables = {
                "ic_search.xml",
                "ic_close.xml",
                "ic_arrow_up.xml",
                "ic_arrow_down.xml",
                "ic_check_circle.xml",
                "ic_chevron_down.xml",
                "ic_chevron_up.xml",
                "ic_stop_circle.xml",
                "ic_folder.xml",
                "ic_edit.xml",
                "ic_download.xml"
        };
        for (String name : requiredDrawables) {
            File f = new File("src/main/res/drawable/" + name);
            assertTrue("Vector drawable must exist: " + name, f.exists());
            String content = new String(Files.readAllBytes(f.toPath()));
            assertTrue("Must be a vector drawable: " + name, content.contains("<vector"));
            assertTrue("Must specify pathData: " + name, content.contains("android:pathData="));
        }
    }

    @Test
    public void testMainActivitySearchUiRestoresReactParity() throws Exception {
        File mainFile = new File("src/main/java/com/antimirov/betterdsget/MainActivity.java");
        assertTrue("MainActivity.java must exist", mainFile.exists());

        String content = new String(Files.readAllBytes(mainFile.toPath()));
        // 1. Colored and right-aligned seed/peer icons
        assertTrue("Seeds up icon must use COLOR_GREEN",
                content.contains("makeTintedIcon(R.drawable.ic_arrow_up, 12, UiTheme.COLOR_GREEN)"));
        assertTrue("Peers down icon must use COLOR_AMBER",
                content.contains("makeTintedIcon(R.drawable.ic_arrow_down, 12, UiTheme.COLOR_AMBER)"));
        // 2. Search bar left magnifying glass and right clear X button
        assertTrue("Search bar must include left magnifying glass icon",
                content.contains("makeTintedIcon(R.drawable.ic_search, 20, UiTheme.COLOR_TEXT_MUTED)"));
        assertTrue("Search bar must include right clear button",
                content.contains("makeTintedIcon(R.drawable.ic_close, 20, UiTheme.COLOR_TEXT_MUTED)"));
        assertTrue("Search bar must wire clear button click to clear input",
                content.contains("clearInputBtn.setOnClickListener(v -> searchInput.setText(\"\"))"));
        // 3. Green check-circle icon for finished status
        assertTrue("Search status must use green check-circle icon",
                content.contains("makeTintedIcon(R.drawable.ic_check_circle, 16, UiTheme.COLOR_GREEN)"));
        // 4. Expandable status chevron always rendered
        assertTrue("Search status must render expandable chevron icon",
                content.contains("showEngineDetails ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down"));
        // 5. Centered empty / waiting state texts
        assertTrue("Search idle/waiting/empty texts must center horizontally via Gravity.CENTER",
                content.contains("findTv.setGravity(Gravity.CENTER)")
                        && content.contains("waitTv.setGravity(Gravity.CENTER)")
                        && content.contains("noResTv.setGravity(Gravity.CENTER)"));
    }

    @Test
    public void testAboutVersionAndTasksViewColoredUi() throws Exception {
        assertEquals("1.6.0", AppDialogs.APP_VERSION);
        assertEquals("1.6.0", AppDialogs.getAppVersion(null));

        File dialogsFile = new File("src/main/java/com/antimirov/betterdsget/ui/AppDialogs.java");
        String dialogsContent = new String(Files.readAllBytes(dialogsFile.toPath()));
        assertTrue("About dialog must read version dynamically via getAppVersion",
                dialogsContent.contains("\"Version \" + getAppVersion(activity)"));

        File mainFile = new File("src/main/java/com/antimirov/betterdsget/MainActivity.java");
        String mainContent = new String(Files.readAllBytes(mainFile.toPath()));
        assertTrue("Tasks speed container must use colored up/down vector icons",
                mainContent.contains("populateSpeedContainer("));
        assertTrue("Task card progress must use SpannableStringBuilder coloring",
                mainContent.contains("formatTaskProgressSpan("));
        assertTrue("Task tracker tab must use colored up/down vector icons",
                mainContent.contains("makeTintedIcon(R.drawable.ic_arrow_up, 11, UiTheme.COLOR_GREEN)"));
    }

    @Test
    public void testNotificationColoredAppIconInSmartDownloadService() throws Exception {
        File mipmapXml = new File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml");
        assertTrue("Adaptive ic_launcher.xml must exist in res/mipmap-anydpi-v26", mipmapXml.exists());

        File serviceFile = new File("src/main/java/com/antimirov/betterdsget/service/SmartDownloadService.java");
        assertTrue("SmartDownloadService.java must exist", serviceFile.exists());
        String serviceContent = new String(Files.readAllBytes(serviceFile.toPath()));
        assertTrue("SmartDownloadService must use adaptive R.mipmap.ic_launcher matching Notifee default",
                serviceContent.contains(".setSmallIcon(R.mipmap.ic_launcher)"));
        assertTrue("SmartDownloadService must not use uncropped flat R.drawable.app_icon",
                !serviceContent.contains("R.drawable.app_icon"));
        assertTrue("SmartDownloadService must not use monochrome ic_stat_download",
                !serviceContent.contains("ic_stat_download"));
        assertTrue("SmartDownloadService must attach tap-to-open PendingIntent to alert notifications",
                serviceContent.contains("createOpenAppPendingIntent(")
                        && serviceContent.contains(".setContentIntent(createOpenAppPendingIntent(notifId))"));
        assertTrue("SmartDownloadService must remove notification debug leftovers (timestamps, grace period, test method)",
                !serviceContent.contains("SimpleDateFormat")
                        && !serviceContent.contains("Grace period")
                        && !serviceContent.contains("sendTestNotification"));

        String dialogsContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/ui/AppDialogs.java").toPath()));
        assertTrue("Settings dialog must remove Test Notification debug row",
                !dialogsContent.contains("Test Notification"));
    }

    @Test
    public void testBottomSheetDialogsSwitchStylingAndAddTaskErrorRecovery() throws Exception {
        String uiThemeContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/ui/UiTheme.java").toPath()));
        assertTrue("UiTheme must define topRoundedRect for bottom sheets",
                uiThemeContent.contains("public static GradientDrawable topRoundedRect("));
        assertTrue("UiTheme must define styleSwitch for custom cyan Switch tinting",
                uiThemeContent.contains("public static void styleSwitch(Switch sw)"));
        assertTrue("UiTheme must define makeInfoRow overload with maxLines",
                uiThemeContent.contains("public static LinearLayout makeInfoRow(Context ctx, String label, String value, int maxLines)"));

        String dialogsContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/ui/AppDialogs.java").toPath()));
        assertTrue("AppDialogs must define createBottomSheetCard",
                dialogsContent.contains("createBottomSheetCard("));
        assertTrue("AppDialogs must define showAndFormatBottomSheet anchored at Gravity.BOTTOM",
                dialogsContent.contains("showAndFormatBottomSheet(") && dialogsContent.contains("Gravity.BOTTOM"));
        assertTrue("File selection sheet must style checkboxes as cyan Switches via UiTheme.styleSwitch",
                dialogsContent.contains("UiTheme.styleSwitch(sw)"));
        assertTrue("Add Task sheet must display (Default) suffix when using default destination",
                dialogsContent.contains("+ \" (Default)\""));
        assertTrue("AddTaskCallbacks must pass resetButtonState runnable for URL and torrent submission recovery",
                dialogsContent.contains("void onSubmitUrlTask(String url, String destination, Dialog dialog, Runnable resetButtonState)")
                        && dialogsContent.contains("void onSubmitTorrentFile(IncomingPayload filePayload, String destination, Dialog dialog, Runnable resetButtonState)"));
    }

    @Test
    public void testMaterialCircularPullToRefreshAndTaskDetailEnhancements() throws Exception {
        String mainContent = new String(Files.readAllBytes(
                new File("src/main/java/com/antimirov/betterdsget/MainActivity.java").toPath()));

        // Material floating circular pull-to-refresh + initial loading spinner
        assertTrue("Tasks tab must configure floating tasksRefreshCircle",
                mainContent.contains("FrameLayout tasksRefreshCircle"));
        assertTrue("Tasks ScrollView must disable stretch overscroll (OVER_SCROLL_NEVER) during custom pull-to-refresh",
                mainContent.contains("tasksScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER)"));
        assertTrue("Manual pull-to-refresh must enforce minimum spinner visibility via finishManualRefreshSpinner",
                mainContent.contains("finishManualRefreshSpinner()") && mainContent.contains("650L - elapsed"));
        assertTrue("Tasks tab must show initial loading indicator before first poll completes",
                mainContent.contains("isInitialTasksLoading"));

        // 2FA draft preservation
        assertTrue("Login screen must preserve form drafts across 2FA rebuilds",
                mainContent.contains("loginDraftUrl")
                        && mainContent.contains("loginDraftAccount")
                        && mainContent.contains("loginDraftPassword")
                        && mainContent.contains("attachDraftWatcher("));

        // Compact TaskCard footerRow (progress bar + DL/UL side-by-side)
        assertTrue("Task cards must place barTrack and speedRow side-by-side inside footerRow",
                mainContent.contains("footerRow.addView(barTrack)") && mainContent.contains("footerRow.addView(speedRow)"));

        // Tracker protocol pill badges & virtualized File ListView
        assertTrue("Tracker cards must render UDP (#673AB7) and HTTP (#757575) protocol pill badges",
                mainContent.contains("\"#673AB7\"") && mainContent.contains("\"#757575\""));
        assertTrue("TaskDetail File tab must render via virtualized ListView and BaseAdapter",
                mainContent.contains("ListView listView = new ListView(this)") && mainContent.contains("new BaseAdapter()"));
        assertTrue("TaskDetail File tab must no longer truncate after 200 items",
                !mainContent.contains("Display capped at 200 items"));

        // Live step-by-step progress during session restore & QuickConnect resolution
        assertTrue("MainActivity must stream live restore status updates to restoreStatusTv",
                mainContent.contains("restoreStatusTv") && mainContent.contains("updateRestoreStatus("));
    }
}
