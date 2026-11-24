package com.antimirov.betterdsget;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.antimirov.betterdsget.TorrentIntentHelper.IncomingPayload;
import com.antimirov.betterdsget.api.Models.BtSearchPage;
import com.antimirov.betterdsget.api.Models.CreateTaskResult;
import com.antimirov.betterdsget.api.Models.EngineStatus;
import com.antimirov.betterdsget.api.Models.PeerItem;
import com.antimirov.betterdsget.api.Models.SearchResultItem;
import com.antimirov.betterdsget.api.Models.StoredCredentials;
import com.antimirov.betterdsget.api.Models.TaskFileItem;
import com.antimirov.betterdsget.api.Models.TaskItem;
import com.antimirov.betterdsget.api.Models.TaskListResult;
import com.antimirov.betterdsget.api.Models.TrackerItem;
import com.antimirov.betterdsget.api.Models.TransferStats;
import com.antimirov.betterdsget.api.QuickConnectResolver;
import com.antimirov.betterdsget.api.SearchController;
import com.antimirov.betterdsget.api.SessionManager;
import com.antimirov.betterdsget.api.SessionManager.ConnectionState;
import com.antimirov.betterdsget.api.SynologyError;
import com.antimirov.betterdsget.service.SmartDownloadService;
import com.antimirov.betterdsget.ui.AppDialogs;
import com.antimirov.betterdsget.ui.UiTheme;
import com.antimirov.betterdsget.util.AndroidPrefsStore;
import com.antimirov.betterdsget.util.FormatUtils;
import com.antimirov.betterdsget.util.GeolocationHelper;
import com.antimirov.betterdsget.util.GeolocationHelper.GeoInfo;

import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Native Android MainActivity for Better DS Get.
 * Zero React Native / Hermes overhead: instant cold start, <1 MB footprint,
 * direct Intent handling, and full parity with all v1.5.1 features.
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK_TORRENT_FILE = 9001;
    private static final int REQ_POST_NOTIFICATIONS = 9002;

    private enum Screen {
        LOGIN,
        TASKS,
        TASK_DETAIL,
        SEARCH
    }

    private AndroidPrefsStore prefsStore;
    private SessionManager sessionManager;
    private SearchController searchController;
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Screen currentScreen = Screen.LOGIN;
    private boolean isRestoringSession = true;
    private boolean needsOtp = false;
    private String loginDraftUrl = null;
    private String loginDraftAccount = null;
    private String loginDraftPassword = null;
    private String loginDraftOtp = "";

    // Tasks state
    private List<TaskItem> tasks = new ArrayList<>();
    private TransferStats transferStats = new TransferStats(0, 0);
    private String defaultDestination = "";
    private String searchDestination = "";
    private boolean isInitialTasksLoading = true;
    private boolean isManualRefreshing = false;
    private long manualRefreshStartMs = 0L;
    private float pullDownStartY = -1f;
    private float activePullSlop = 0f;
    private boolean isPullingDown = false;

    // Task detail state
    private TaskItem activeDetailTask = null;
    private List<TaskFileItem> activeDetailFiles = new ArrayList<>();
    private String activeDetailFileError = null;
    private String activeDetailTab = "Info"; // "Info", "Tracker", "Peers", "File"
    private boolean isDetailLoading = false;

    // Search UI state
    private boolean showEngineDetails = false;
    private long searchStartTimestamp = 0L;

    // Pending Intent / File Picker state
    private IncomingPayload queuedIntentPayload = null;
    private IncomingPayload[] activeAddDialogPayloadHolder = new IncomingPayload[]{null};
    private Runnable activeAddDialogRefreshCallback = null;

    // Root views
    private LinearLayout rootLayout;
    private FrameLayout contentFrame;
    private LinearLayout bottomTabBar;
    private ImageView tabTasksIcon;
    private TextView tabTasksLabel;
    private ImageView tabSearchIcon;
    private TextView tabSearchLabel;
    private int systemTopInset = 0;
    private int systemBottomInset = 0;

    // Live-updatable containers
    private TextView restoreStatusTv;
    private String restoreStatusText = "Restoring session...";
    private TextView tasksHeaderTitle;
    private LinearLayout tasksHeaderStats;
    private FrameLayout tasksRefreshCircle;
    private ProgressBar tasksRefreshSpinner;
    private ScrollView tasksScrollView;
    private LinearLayout tasksListContainer;
    private LinearLayout detailActionBar;
    private final TextView[] detailTabLabels = new TextView[4];
    private final View[] detailTabIndicators = new View[4];
    private FrameLayout detailContentFrame;
    private LinearLayout searchResultsContainer;
    private LinearLayout searchStatusBanner;
    private LinearLayout searchSortBar;

    private final Runnable taskPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (sessionManager.isConnected()) {
                if (currentScreen == Screen.TASKS) {
                    refreshTasksAsync();
                    uiHandler.postDelayed(this, 3000);
                } else if (currentScreen == Screen.TASK_DETAIL && activeDetailTask != null) {
                    refreshTaskDetailAsync(activeDetailTask.id, true);
                    uiHandler.postDelayed(this, 5000);
                }
            }
        }
    };

    private final Runnable searchPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (searchController == null || !searchController.isSearching() || searchController.isFinished()) {
                return;
            }
            bgExecutor.execute(() -> {
                try {
                    BtSearchPage page = searchController.pollOnce();
                    uiHandler.post(() -> {
                        if (page.finished) {
                            showEngineDetails = false;
                        }
                        if (currentScreen == Screen.SEARCH) {
                            renderSearchDynamicSections();
                        }
                        if (!page.finished && searchController.isSearching()) {
                            long elapsed = System.currentTimeMillis() - searchStartTimestamp;
                            long delay = elapsed < 10000L ? 1500L : 3000L;
                            uiHandler.postDelayed(this, delay);
                        }
                    });
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.US) : "";
                    if (msg.contains("time") && searchController.isSearching()) {
                        uiHandler.postDelayed(this, 3000L);
                    } else {
                        searchController.stopSearch();
                        uiHandler.post(() -> {
                            if (currentScreen == Screen.SEARCH) {
                                renderSearchDynamicSections();
                            }
                        });
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefsStore = new AndroidPrefsStore(this);
        sessionManager = new SessionManager(prefsStore);

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
            }
        }

        // Capture any cold-start magnet or .torrent file intent immediately
        IncomingPayload initialPayload = TorrentIntentHelper.extractPayload(this, getIntent());
        if (initialPayload != null) {
            queuedIntentPayload = initialPayload;
        }

        buildRootLayout();
        renderScreen(Screen.LOGIN);

        // Attempt auto-restore session in background with live step status
        SessionManager.StateListener restoreStateListener = (state, prev, err) -> {
            String msg = null;
            if (state == ConnectionState.AUTHORIZING) {
                msg = "Authorizing session...";
            } else if (state == ConnectionState.FETCHING_INFO) {
                msg = "Loading Download Station...";
            }
            if (msg != null) {
                final String finalMsg = msg;
                uiHandler.post(() -> updateRestoreStatus(finalMsg));
            }
        };
        sessionManager.addStateListener(restoreStateListener);
        bgExecutor.execute(() -> {
            boolean restored = sessionManager.tryRestore(status ->
                    uiHandler.post(() -> updateRestoreStatus(status)));
            sessionManager.removeStateListener(restoreStateListener);
            uiHandler.post(() -> {
                isRestoringSession = false;
                if (restored) {
                    onConnectedSuccess();
                } else {
                    renderScreen(Screen.LOGIN);
                }
            });
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        IncomingPayload payload = TorrentIntentHelper.extractPayload(this, intent);
        if (payload != null) {
            queuedIntentPayload = payload;
            if (sessionManager.isConnected()) {
                deliverQueuedIntent();
            }
            return;
        }
        if (intent != null && intent.getBooleanExtra(SmartDownloadService.EXTRA_OPEN_TASKS, false)
                && sessionManager != null && sessionManager.isConnected()) {
            if (currentScreen != Screen.TASKS) {
                navigateTo(Screen.TASKS);
            } else {
                refreshTasksAsync();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionManager != null && sessionManager.isConnected()) {
            scheduleActivePoller();
        }
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(taskPollRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == Screen.TASK_DETAIL) {
            navigateTo(Screen.TASKS);
            return;
        }
        if (currentScreen == Screen.SEARCH) {
            navigateTo(Screen.TASKS);
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_TORRENT_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            IncomingPayload picked = TorrentIntentHelper.readTorrentUri(this, uri);
            if (picked != null) {
                activeAddDialogPayloadHolder[0] = picked;
                if (activeAddDialogRefreshCallback != null) {
                    activeAddDialogRefreshCallback.run();
                }
            } else {
                Toast.makeText(this, "Could not read selected .torrent file", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Root Shell & Navigation ────────────────────────────────────

    private void buildRootLayout() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(UiTheme.COLOR_BG_MAIN);

        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }

            systemTopInset = top;
            systemBottomInset = bottom;

            // Apply safe area top padding so content never collides with status bar / camera notch
            rootLayout.setPadding(0, systemTopInset, 0, 0);

            // Dynamically adjust bottom bar height / content padding for navigation bar / gesture pill
            updateInsetsLayout();

            return insets;
        });

        contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        rootLayout.addView(contentFrame);

        bottomTabBar = new LinearLayout(this);
        bottomTabBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabBar.setBackgroundColor(UiTheme.COLOR_BG_CARD);
        bottomTabBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomTabBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiTheme.dp(this, 58) + systemBottomInset
        ));

        // Tasks tab item (Feather-like list icon + label)
        LinearLayout tabTasks = new LinearLayout(this);
        tabTasks.setOrientation(LinearLayout.VERTICAL);
        tabTasks.setGravity(Gravity.CENTER);
        tabTasks.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        android.graphics.drawable.Drawable selBg1 = UiTheme.makeSelectableBackground(this);
        if (selBg1 != null) tabTasks.setBackground(selBg1);
        tabTasks.setClickable(true);
        tabTasks.setFocusable(true);

        tabTasksIcon = new ImageView(this);
        tabTasksIcon.setImageResource(R.drawable.ic_tasks);
        tabTasksIcon.setColorFilter(UiTheme.COLOR_SYNOLOGY_BLUE);
        int iconSize = UiTheme.dp(this, 22);
        LinearLayout.LayoutParams icLp1 = new LinearLayout.LayoutParams(iconSize, iconSize);
        icLp1.bottomMargin = UiTheme.dp(this, 3);
        tabTasksIcon.setLayoutParams(icLp1);

        tabTasksLabel = UiTheme.makeText(this, "Tasks", 11, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        tabTasksLabel.setGravity(Gravity.CENTER);

        tabTasks.addView(tabTasksIcon);
        tabTasks.addView(tabTasksLabel);
        tabTasks.setOnClickListener(v -> navigateTo(Screen.TASKS));

        // Search tab item (Feather-like search icon + label)
        LinearLayout tabSearch = new LinearLayout(this);
        tabSearch.setOrientation(LinearLayout.VERTICAL);
        tabSearch.setGravity(Gravity.CENTER);
        tabSearch.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        android.graphics.drawable.Drawable selBg2 = UiTheme.makeSelectableBackground(this);
        if (selBg2 != null) tabSearch.setBackground(selBg2);
        tabSearch.setClickable(true);
        tabSearch.setFocusable(true);

        tabSearchIcon = new ImageView(this);
        tabSearchIcon.setImageResource(R.drawable.ic_search);
        tabSearchIcon.setColorFilter(UiTheme.COLOR_TEXT_MUTED);
        LinearLayout.LayoutParams icLp2 = new LinearLayout.LayoutParams(iconSize, iconSize);
        icLp2.bottomMargin = UiTheme.dp(this, 3);
        tabSearchIcon.setLayoutParams(icLp2);

        tabSearchLabel = UiTheme.makeText(this, "Search", 11, UiTheme.COLOR_TEXT_MUTED, true);
        tabSearchLabel.setGravity(Gravity.CENTER);

        tabSearch.addView(tabSearchIcon);
        tabSearch.addView(tabSearchLabel);
        tabSearch.setOnClickListener(v -> navigateTo(Screen.SEARCH));

        bottomTabBar.addView(tabTasks);
        bottomTabBar.addView(tabSearch);
        bottomTabBar.setVisibility(View.GONE);
        rootLayout.addView(bottomTabBar);

        setContentView(rootLayout);
    }

    private void updateInsetsLayout() {
        boolean showBottomBar = sessionManager != null && sessionManager.isConnected()
                && (currentScreen == Screen.TASKS || currentScreen == Screen.SEARCH);
        if (showBottomBar) {
            contentFrame.setPadding(0, 0, 0, 0);
            bottomTabBar.setPadding(0, 0, 0, systemBottomInset);
            ViewGroup.LayoutParams lp = bottomTabBar.getLayoutParams();
            if (lp != null) {
                lp.height = UiTheme.dp(this, 58) + systemBottomInset;
                bottomTabBar.setLayoutParams(lp);
            }
        } else {
            contentFrame.setPadding(0, 0, 0, systemBottomInset);
        }
    }

    private void navigateTo(Screen screen) {
        uiHandler.removeCallbacks(taskPollRunnable);
        renderScreen(screen);
        scheduleActivePoller();
    }

    private void scheduleActivePoller() {
        uiHandler.removeCallbacks(taskPollRunnable);
        if (!sessionManager.isConnected()) return;
        if (currentScreen == Screen.TASKS || currentScreen == Screen.TASK_DETAIL) {
            uiHandler.post(taskPollRunnable);
        }
    }

    private void onConnectedSuccess() {
        isInitialTasksLoading = true;
        searchController = new SearchController(sessionManager.getDs());
        SmartDownloadService.startOrKick(this);

        // Load default destination and search modules in background
        bgExecutor.execute(() -> {
            try {
                String def = sessionManager.execute(() -> sessionManager.getDs().getDefaultDestination());
                if (def != null && !def.isEmpty()) {
                    defaultDestination = def;
                    if (searchDestination.isEmpty()) {
                        searchDestination = def;
                    }
                }
            } catch (Exception ignored) {}

            try {
                searchController.loadModules();
            } catch (Exception ignored) {}
        });

        navigateTo(Screen.TASKS);
        if (queuedIntentPayload != null) {
            deliverQueuedIntent();
        }
    }

    private void deliverQueuedIntent() {
        IncomingPayload payload = queuedIntentPayload;
        queuedIntentPayload = null;
        if (payload == null) return;

        navigateTo(Screen.TASKS);
        openAddTaskDialog(payload, true);
    }

    private void renderScreen(Screen screen) {
        UiTheme.hideKeyboard(this, null);
        this.currentScreen = screen;
        contentFrame.removeAllViews();

        boolean showBottomBar = sessionManager.isConnected() && (screen == Screen.TASKS || screen == Screen.SEARCH);
        bottomTabBar.setVisibility(showBottomBar ? View.VISIBLE : View.GONE);
        updateInsetsLayout();

        if (showBottomBar) {
            boolean isTasks = screen == Screen.TASKS;
            boolean isSearch = screen == Screen.SEARCH;
            int tasksColor = isTasks ? UiTheme.COLOR_SYNOLOGY_BLUE : UiTheme.COLOR_TEXT_MUTED;
            int searchColor = isSearch ? UiTheme.COLOR_SYNOLOGY_BLUE : UiTheme.COLOR_TEXT_MUTED;

            tabTasksIcon.setColorFilter(tasksColor);
            tabTasksLabel.setTextColor(tasksColor);

            tabSearchIcon.setColorFilter(searchColor);
            tabSearchLabel.setTextColor(searchColor);
        }

        switch (screen) {
            case LOGIN:
                contentFrame.addView(buildLoginView());
                break;
            case TASKS:
                contentFrame.addView(buildTasksView());
                break;
            case TASK_DETAIL:
                contentFrame.addView(buildTaskDetailView());
                break;
            case SEARCH:
                contentFrame.addView(buildSearchView());
                break;
        }
    }

    // ── 1. Login View ──────────────────────────────────────────────

    private void attachDraftWatcher(EditText input, java.util.function.Consumer<String> onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onChanged.accept(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void updateRestoreStatus(String status) {
        restoreStatusText = status;
        if (restoreStatusTv != null && isRestoringSession) {
            restoreStatusTv.setText(status);
        }
    }

    private void showErrorAlert(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private View buildLoginView() {
        ScrollView loginScroll = new ScrollView(this);
        loginScroll.setBackgroundColor(UiTheme.COLOR_BG_CARD);
        loginScroll.setFillViewport(true);
        loginScroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        int pad = UiTheme.dp(this, 20);
        outer.setPadding(pad, pad, pad, pad);
        outer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = UiTheme.dp(this, 24);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_MODAL, UiTheme.dp(this, 12)));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (isRestoringSession) {
            card.setGravity(Gravity.CENTER);
            ProgressBar pb = new ProgressBar(this);
            pb.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
            card.addView(pb);
            TextView title = UiTheme.makeText(this, "Better DS Get", 22, UiTheme.COLOR_SYNOLOGY_BLUE, true);
            title.setPadding(0, UiTheme.dp(this, 12), 0, UiTheme.dp(this, 6));
            card.addView(title);
            restoreStatusTv = UiTheme.makeText(this, restoreStatusText, 14, UiTheme.COLOR_TEXT_MUTED, false);
            restoreStatusTv.setGravity(Gravity.CENTER);
            card.addView(restoreStatusTv);
            outer.addView(card);
            loginScroll.addView(outer);
            return loginScroll;
        }

        TextView title = UiTheme.makeText(this, "Better DS Get", 26, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, UiTheme.dp(this, 24));
        card.addView(title);

        StoredCredentials saved = prefsStore.loadCredentials();
        if (loginDraftUrl == null && saved != null && saved.originalAddress != null) {
            loginDraftUrl = saved.originalAddress;
        }
        if (loginDraftAccount == null && saved != null && saved.account != null) {
            loginDraftAccount = saved.account;
        }
        if (loginDraftPassword == null && saved != null && saved.password != null) {
            loginDraftPassword = saved.password;
        }

        card.addView(UiTheme.makeText(this, "Synology URL (IP/QuickConnect)", 13, UiTheme.COLOR_TEXT_MUTED, true));
        EditText urlInput = UiTheme.makeInput(this, "e.g. 192.168.1.100:5000", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (loginDraftUrl != null) {
            urlInput.setText(loginDraftUrl);
        }
        attachDraftWatcher(urlInput, s -> loginDraftUrl = s);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = UiTheme.dp(this, 6);
        inputLp.bottomMargin = UiTheme.dp(this, 14);
        urlInput.setLayoutParams(inputLp);
        card.addView(urlInput);

        card.addView(UiTheme.makeText(this, "Account", 13, UiTheme.COLOR_TEXT_MUTED, true));
        EditText accountInput = UiTheme.makeInput(this, "admin", InputType.TYPE_CLASS_TEXT);
        if (loginDraftAccount != null) {
            accountInput.setText(loginDraftAccount);
        }
        attachDraftWatcher(accountInput, s -> loginDraftAccount = s);
        accountInput.setLayoutParams(inputLp);
        card.addView(accountInput);

        card.addView(UiTheme.makeText(this, "Password", 13, UiTheme.COLOR_TEXT_MUTED, true));
        EditText passwordInput = UiTheme.makeInput(this, "password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (loginDraftPassword != null) {
            passwordInput.setText(loginDraftPassword);
        }
        attachDraftWatcher(passwordInput, s -> loginDraftPassword = s);
        passwordInput.setLayoutParams(inputLp);
        card.addView(passwordInput);

        final EditText otpInput = UiTheme.makeInput(this, "123456", InputType.TYPE_CLASS_NUMBER);
        if (needsOtp) {
            card.addView(UiTheme.makeText(this, "🔐 Authenticator Code", 13, Color.parseColor("#FFB300"), true));
            otpInput.setBackground(UiTheme.roundedRectWithBorder(
                    UiTheme.COLOR_BG_CARD,
                    Color.parseColor("#FFB300"),
                    UiTheme.dp(this, 1),
                    UiTheme.dp(this, 8)
            ));
            otpInput.setGravity(Gravity.CENTER);
            otpInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                otpInput.setLetterSpacing(0.25f);
            }
            otpInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
            otpInput.setText(loginDraftOtp);
            attachDraftWatcher(otpInput, s -> loginDraftOtp = s);
            otpInput.setLayoutParams(inputLp);
            card.addView(otpInput);
            otpInput.requestFocus();
        }

        // Connect button with inline ProgressBar spinner
        LinearLayout connectBtnContainer = new LinearLayout(this);
        connectBtnContainer.setOrientation(LinearLayout.HORIZONTAL);
        connectBtnContainer.setGravity(Gravity.CENTER);
        connectBtnContainer.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));
        connectBtnContainer.setBackground(UiTheme.roundedRect(UiTheme.COLOR_SYNOLOGY_BLUE, UiTheme.dp(this, 8)));
        connectBtnContainer.setClickable(true);
        connectBtnContainer.setFocusable(true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = UiTheme.dp(this, 6);
        connectBtnContainer.setLayoutParams(btnLp);

        ProgressBar connectSpinner = new ProgressBar(this);
        int spSize = UiTheme.dp(this, 18);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(spSize, spSize);
        spLp.rightMargin = UiTheme.dp(this, 10);
        connectSpinner.setLayoutParams(spLp);
        connectSpinner.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_TEXT_PRIMARY));
        connectSpinner.setVisibility(View.GONE);

        TextView connectBtnText = UiTheme.makeText(
                this,
                needsOtp ? "Verify & Connect" : "Connect to NAS",
                17,
                UiTheme.COLOR_TEXT_PRIMARY,
                true
        );

        connectBtnContainer.addView(connectSpinner);
        connectBtnContainer.addView(connectBtnText);

        connectBtnContainer.setOnClickListener(v -> {
            UiTheme.hideKeyboard(this, null);
            String rawUrl = urlInput.getText().toString().trim();
            String acc = accountInput.getText().toString().trim();
            String pass = passwordInput.getText().toString();
            String otpVal = needsOtp ? otpInput.getText().toString().trim() : null;

            loginDraftUrl = rawUrl;
            loginDraftAccount = acc;
            loginDraftPassword = pass;

            if (rawUrl.isEmpty() || acc.isEmpty() || pass.isEmpty()) {
                showErrorAlert("Missing Info", "Please fill in all required fields.");
                return;
            }

            connectBtnContainer.setEnabled(false);
            connectBtnContainer.setBackground(UiTheme.roundedRect(Color.parseColor("#005F87"), UiTheme.dp(this, 8)));
            connectSpinner.setVisibility(View.VISIBLE);
            connectBtnText.setText("Connecting...");

            bgExecutor.execute(() -> {
                try {
                    String finalUrl;
                    if (FormatUtils.isQuickConnectId(rawUrl)) {
                        uiHandler.post(() -> connectBtnText.setText("Resolving QuickConnect..."));
                        finalUrl = QuickConnectResolver.resolveQuickConnect(rawUrl, status ->
                                uiHandler.post(() -> connectBtnText.setText(status)));
                    } else {
                        finalUrl = FormatUtils.normalizeDirectUrl(rawUrl);
                    }

                    uiHandler.post(() -> connectBtnText.setText("Authorizing..."));
                    sessionManager.connect(finalUrl, acc, pass, otpVal, true, rawUrl);

                    uiHandler.post(() -> {
                        needsOtp = false;
                        loginDraftOtp = "";
                        onConnectedSuccess();
                    });
                } catch (Exception e) {
                    uiHandler.post(() -> {
                        connectBtnContainer.setEnabled(true);
                        connectBtnContainer.setBackground(UiTheme.roundedRect(UiTheme.COLOR_SYNOLOGY_BLUE, UiTheme.dp(this, 8)));
                        connectSpinner.setVisibility(View.GONE);
                        if (e instanceof SynologyError && ((SynologyError) e).isOtpRequired()) {
                            boolean wasAlreadyNeedsOtp = needsOtp;
                            needsOtp = true;
                            loginDraftOtp = "";
                            renderScreen(Screen.LOGIN);
                            showErrorAlert(
                                    "2FA Required",
                                    wasAlreadyNeedsOtp
                                            ? "The code you entered was incorrect. Please try again."
                                            : "This account requires two-factor authentication. Please enter the code from your authenticator app."
                            );
                        } else {
                            connectBtnText.setText(needsOtp ? "Verify & Connect" : "Connect to NAS");
                            showErrorAlert("Login Failed", e.getMessage() != null ? e.getMessage() : "Unknown error occurred");
                        }
                    });
                }
            });
        });

        card.addView(connectBtnContainer);
        outer.addView(card);
        loginScroll.addView(outer);
        return loginScroll;
    }

    // ── 2. Tasks List View ─────────────────────────────────────────

    private View buildTasksView() {
        FrameLayout frame = new FrameLayout(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = UiTheme.dp(this, 20);
        int padV = UiTheme.dp(this, 14);
        header.setPadding(padH, padV, padH, padV);
        header.setBackgroundColor(UiTheme.COLOR_BG_CARD);

        LinearLayout titleAndStats = new LinearLayout(this);
        titleAndStats.setOrientation(LinearLayout.HORIZONTAL);
        titleAndStats.setGravity(Gravity.CENTER_VERTICAL);
        titleAndStats.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tasksHeaderTitle = UiTheme.makeText(this, "Downloads: " + tasks.size(), 18, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        tasksHeaderStats = new LinearLayout(this);
        tasksHeaderStats.setOrientation(LinearLayout.HORIZONTAL);
        tasksHeaderStats.setGravity(Gravity.CENTER_VERTICAL);
        tasksHeaderStats.setPadding(UiTheme.dp(this, 14), 0, 0, 0);
        updateHeaderStatsLabel();

        titleAndStats.addView(tasksHeaderTitle);
        titleAndStats.addView(tasksHeaderStats);

        ImageView settingsBtn = new ImageView(this);
        settingsBtn.setImageResource(R.drawable.ic_settings);
        settingsBtn.setColorFilter(UiTheme.COLOR_TEXT_MUTED);
        int setSize = UiTheme.dp(this, 24);
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(setSize, setSize);
        settingsBtn.setLayoutParams(setLp);
        settingsBtn.setClickable(true);
        settingsBtn.setFocusable(true);
        android.graphics.drawable.Drawable setSel = UiTheme.makeSelectableBackground(this);
        if (setSel != null) settingsBtn.setBackground(setSel);
        settingsBtn.setOnClickListener(v -> openSettingsModal());

        header.addView(titleAndStats);
        header.addView(settingsBtn);
        page.addView(header);

        // Overlaid list container with floating Material-style refresh disc (no layout reflow)
        FrameLayout listAreaFrame = new FrameLayout(this);
        listAreaFrame.setClipChildren(true);
        listAreaFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        tasksScrollView = new ScrollView(this);
        tasksScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tasksScrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        tasksListContainer = new LinearLayout(this);
        tasksListContainer.setOrientation(LinearLayout.VERTICAL);
        int listPad = UiTheme.dp(this, 16);
        tasksListContainer.setPadding(listPad, listPad, listPad, UiTheme.dp(this, 90));
        tasksScrollView.addView(tasksListContainer);
        listAreaFrame.addView(tasksScrollView);

        // Floating circular refresh indicator (matches Android SwipeRefreshLayout look & feel)
        int circleSize = UiTheme.dp(this, 40);
        tasksRefreshCircle = new FrameLayout(this);
        FrameLayout.LayoutParams circleLp = new FrameLayout.LayoutParams(circleSize, circleSize);
        circleLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tasksRefreshCircle.setLayoutParams(circleLp);
        tasksRefreshCircle.setBackground(UiTheme.roundedRectWithBorder(
                Color.parseColor("#2C2C2C"),
                Color.parseColor("#3D3D3D"),
                UiTheme.dp(this, 1),
                circleSize / 2
        ));
        tasksRefreshCircle.setElevation(UiTheme.dp(this, 6));
        tasksRefreshCircle.setTranslationY(-UiTheme.dp(this, 48));
        tasksRefreshCircle.setAlpha(0f);
        tasksRefreshCircle.setScaleX(0.5f);
        tasksRefreshCircle.setScaleY(0.5f);
        tasksRefreshCircle.setVisibility(View.GONE);

        tasksRefreshSpinner = new ProgressBar(this);
        int spSize = UiTheme.dp(this, 22);
        FrameLayout.LayoutParams spLp = new FrameLayout.LayoutParams(spSize, spSize);
        spLp.gravity = Gravity.CENTER;
        tasksRefreshSpinner.setLayoutParams(spLp);
        tasksRefreshSpinner.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
        tasksRefreshCircle.addView(tasksRefreshSpinner);
        listAreaFrame.addView(tasksRefreshCircle);

        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float triggerDistancePx = UiTheme.dp(this, 64);
        final float maxPullPx = UiTheme.dp(this, 88);
        final float hiddenY = -UiTheme.dp(this, 48);
        final float restingY = UiTheme.dp(this, 16);

        tasksScrollView.setOnTouchListener((v, ev) -> {
            if (isManualRefreshing) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pullDownStartY = tasksScrollView.getScrollY() == 0 ? ev.getRawY() : -1f;
                    activePullSlop = touchSlop;
                    isPullingDown = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tasksScrollView.getScrollY() == 0) {
                        if (pullDownStartY < 0f) {
                            pullDownStartY = ev.getRawY();
                            // Drag was just handed off from a child view after touchSlop elapsed
                            activePullSlop = 0f;
                        }
                        float rawDy = ev.getRawY() - pullDownStartY;
                        float pull = rawDy - activePullSlop;
                        if (pull > 0f) {
                            isPullingDown = true;
                            tasksRefreshCircle.animate().cancel();
                            tasksListContainer.animate().cancel();
                            if (tasksRefreshCircle.getVisibility() != View.VISIBLE) {
                                tasksRefreshCircle.setVisibility(View.VISIBLE);
                            }
                            float damped = (float) (maxPullPx * (1.0 - Math.exp(-pull / (maxPullPx * 1.15f))));
                            float progress = Math.min(1f, pull / triggerDistancePx);
                            float scale = 0.55f + 0.45f * progress;
                            tasksRefreshCircle.setTranslationY(-UiTheme.dp(this, 44) + damped);
                            tasksRefreshCircle.setAlpha(0.35f + 0.65f * progress);
                            tasksRefreshCircle.setScaleX(scale);
                            tasksRefreshCircle.setScaleY(scale);
                            tasksListContainer.setTranslationY(damped * 0.4f);
                            return true;
                        } else if (isPullingDown) {
                            isPullingDown = false;
                            tasksRefreshCircle.setVisibility(View.GONE);
                            tasksRefreshCircle.setTranslationY(hiddenY);
                            tasksListContainer.setTranslationY(0f);
                        }
                    } else {
                        pullDownStartY = -1f;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isPullingDown) {
                        float pull = pullDownStartY >= 0f ? (ev.getRawY() - pullDownStartY - activePullSlop) : 0f;
                        isPullingDown = false;
                        pullDownStartY = -1f;
                        tasksListContainer.animate().translationY(0f).setDuration(200).start();
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && pull >= triggerDistancePx) {
                            isManualRefreshing = true;
                            manualRefreshStartMs = System.currentTimeMillis();
                            tasksRefreshCircle.animate()
                                    .translationY(restingY)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(180)
                                    .start();
                            refreshTasksAsync(true);
                        } else {
                            tasksRefreshCircle.animate()
                                    .translationY(hiddenY)
                                    .scaleX(0.5f)
                                    .scaleY(0.5f)
                                    .alpha(0f)
                                    .setDuration(180)
                                    .withEndAction(() -> {
                                        if (!isManualRefreshing && !isPullingDown && tasksRefreshCircle != null) {
                                            tasksRefreshCircle.setVisibility(View.GONE);
                                        }
                                    })
                                    .start();
                        }
                        return true;
                    }
                    pullDownStartY = -1f;
                    break;
            }
            return false;
        });

        page.addView(listAreaFrame);

        renderTaskCards();
        frame.addView(page);

        // Floating Action Button (+)
        TextView fab = UiTheme.makeText(this, "+", 30, UiTheme.COLOR_TEXT_PRIMARY, false);
        fab.setGravity(Gravity.CENTER);
        int fabSize = UiTheme.dp(this, 58);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(fabSize, fabSize);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = UiTheme.dp(this, 20);
        fabLp.bottomMargin = UiTheme.dp(this, 24);
        fab.setLayoutParams(fabLp);
        fab.setBackground(UiTheme.roundedRect(UiTheme.COLOR_SYNOLOGY_BLUE, fabSize / 2));
        fab.setElevation(UiTheme.dp(this, 6));
        fab.setOnClickListener(v -> openAddTaskDialog(null, false));
        frame.addView(fab);

        return frame;
    }

    private void populateSpeedContainer(LinearLayout container, long speedDownload, long speedUpload, int gapDp) {
        ImageView downIcon = makeTintedIcon(R.drawable.ic_arrow_down, 12, UiTheme.COLOR_AMBER);
        ((LinearLayout.LayoutParams) downIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
        container.addView(downIcon);

        TextView dlTv = UiTheme.makeText(this, FormatUtils.formatBytes(speedDownload) + "/s", 12, UiTheme.COLOR_TEXT_MUTED, false);
        dlTv.setPadding(0, 0, UiTheme.dp(this, gapDp), 0);
        container.addView(dlTv);

        ImageView upIcon = makeTintedIcon(R.drawable.ic_arrow_up, 12, UiTheme.COLOR_GREEN);
        ((LinearLayout.LayoutParams) upIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
        container.addView(upIcon);

        container.addView(UiTheme.makeText(this, FormatUtils.formatBytes(speedUpload) + "/s", 12, UiTheme.COLOR_TEXT_MUTED, false));
    }

    private void updateHeaderStatsLabel() {
        if (tasksHeaderTitle != null) {
            tasksHeaderTitle.setText("Downloads: " + tasks.size());
        }
        if (tasksHeaderStats != null) {
            tasksHeaderStats.removeAllViews();
            if (sessionManager.getState() == ConnectionState.RECONNECTING) {
                tasksHeaderStats.addView(UiTheme.makeText(this, "Reconnecting...", 12, UiTheme.COLOR_AMBER, false));
            } else {
                populateSpeedContainer(tasksHeaderStats, transferStats.speedDownload, transferStats.speedUpload, 10);
            }
        }
    }

    private SpannableStringBuilder formatTaskProgressSpan(long sizeDownloaded, long size, String pctText, int accentColor) {
        String dlStr = FormatUtils.formatBytes(sizeDownloaded);
        String sep = " / ";
        String totalStr = FormatUtils.formatBytes(size);
        String pctPart = " (" + pctText + ")";

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int s1 = sb.length();
        sb.append(dlStr);
        sb.setSpan(new ForegroundColorSpan(UiTheme.COLOR_GREEN), s1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int s2 = sb.length();
        sb.append(sep);
        sb.setSpan(new ForegroundColorSpan(UiTheme.COLOR_TEXT_MUTED), s2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int s3 = sb.length();
        sb.append(totalStr);
        sb.setSpan(new ForegroundColorSpan(UiTheme.COLOR_TEXT_SECONDARY), s3, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int s4 = sb.length();
        sb.append(pctPart);
        sb.setSpan(new ForegroundColorSpan(accentColor), s4, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), s4, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void renderTaskCards() {
        if (tasksListContainer == null) return;
        tasksListContainer.removeAllViews();

        if (tasks.isEmpty()) {
            if (isInitialTasksLoading) {
                LinearLayout loadingBox = new LinearLayout(this);
                loadingBox.setOrientation(LinearLayout.VERTICAL);
                loadingBox.setGravity(Gravity.CENTER_HORIZONTAL);
                loadingBox.setPadding(0, UiTheme.dp(this, 48), 0, 0);
                ProgressBar pb = new ProgressBar(this);
                pb.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
                loadingBox.addView(pb);
                tasksListContainer.addView(loadingBox);
            } else {
                TextView empty = UiTheme.makeText(this, "No download tasks active.", 15, UiTheme.COLOR_TEXT_MUTED, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, UiTheme.dp(this, 48), 0, 0);
                tasksListContainer.addView(empty);
            }
            return;
        }

        for (TaskItem item : tasks) {
            int accentColor = UiTheme.statusColor(item.status);
            double pct = item.size > 0 ? Math.min(100.0, (item.sizeDownloaded * 100.0) / item.size) : 0.0;
            String pctText = String.format(Locale.US, "%.1f%%", pct);

            LinearLayout outerCard = new LinearLayout(this);
            outerCard.setOrientation(LinearLayout.HORIZONTAL);
            outerCard.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_CARD, UiTheme.dp(this, 10)));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardLp.bottomMargin = UiTheme.dp(this, 12);
            outerCard.setLayoutParams(cardLp);

            // Colored left stripe
            View stripe = new View(this);
            stripe.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 4), ViewGroup.LayoutParams.MATCH_PARENT));
            stripe.setBackgroundColor(accentColor);
            outerCard.addView(stripe);

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            int p = UiTheme.dp(this, 14);
            body.setPadding(p, p, p, p);
            body.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView titleTv = UiTheme.makeText(this, item.title, 15, UiTheme.COLOR_TEXT_PRIMARY, true);
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(titleTv);

            LinearLayout statusRow = new LinearLayout(this);
            statusRow.setOrientation(LinearLayout.HORIZONTAL);
            statusRow.setPadding(0, UiTheme.dp(this, 6), 0, UiTheme.dp(this, 6));

            TextView statusTv = UiTheme.makeText(this, item.status.toUpperCase(Locale.US), 11, accentColor, true);
            statusTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView progTv = UiTheme.makeText(this, "", 12, UiTheme.COLOR_TEXT_MUTED, false);
            progTv.setText(formatTaskProgressSpan(item.sizeDownloaded, item.size, pctText, accentColor));
            statusRow.addView(statusTv);
            statusRow.addView(progTv);
            body.addView(statusRow);

            // Progress bar & speed row side-by-side (matching RN taskFooter)
            LinearLayout footerRow = new LinearLayout(this);
            footerRow.setOrientation(LinearLayout.HORIZONTAL);
            footerRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout barTrack = new LinearLayout(this);
            barTrack.setOrientation(LinearLayout.HORIZONTAL);
            barTrack.setBackground(UiTheme.roundedRect(Color.parseColor("#333333"), UiTheme.dp(this, 3)));
            barTrack.setLayoutParams(new LinearLayout.LayoutParams(0, UiTheme.dp(this, 6), 1f));

            View fill = new View(this);
            fill.setBackground(UiTheme.roundedRect(accentColor, UiTheme.dp(this, 3)));
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) pct));
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (100.0 - pct)));
            barTrack.addView(fill);
            barTrack.addView(rest);
            footerRow.addView(barTrack);

            boolean showSpeed = "downloading".equalsIgnoreCase(item.status)
                    || ("seeding".equalsIgnoreCase(item.status) && (item.speedDownload > 0 || item.speedUpload > 0));
            if (showSpeed) {
                LinearLayout speedRow = new LinearLayout(this);
                speedRow.setOrientation(LinearLayout.HORIZONTAL);
                speedRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams speedLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                speedLp.leftMargin = UiTheme.dp(this, 10);
                speedRow.setLayoutParams(speedLp);
                populateSpeedContainer(speedRow, item.speedDownload, item.speedUpload, 8);
                footerRow.addView(speedRow);
            }
            body.addView(footerRow);

            outerCard.addView(body);
            outerCard.setOnClickListener(v -> {
                activeDetailTask = item;
                activeDetailFiles = new ArrayList<>();
                activeDetailFileError = null;
                activeDetailTab = "Info";
                isDetailLoading = true;
                navigateTo(Screen.TASK_DETAIL);
            });
            tasksListContainer.addView(outerCard);
        }
    }

    private void refreshTasksAsync() {
        refreshTasksAsync(false);
    }

    private void finishManualRefreshSpinner() {
        long elapsed = System.currentTimeMillis() - manualRefreshStartMs;
        long waitMs = Math.max(0L, 650L - elapsed);
        uiHandler.postDelayed(() -> {
            isManualRefreshing = false;
            if (tasksRefreshCircle != null) {
                tasksRefreshCircle.animate()
                        .scaleX(0.4f)
                        .scaleY(0.4f)
                        .alpha(0f)
                        .translationY(-UiTheme.dp(this, 48))
                        .setDuration(220)
                        .withEndAction(() -> {
                            if (!isManualRefreshing && !isPullingDown && tasksRefreshCircle != null) {
                                tasksRefreshCircle.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }, waitMs);
    }

    private void refreshTasksAsync(boolean isManual) {
        bgExecutor.execute(() -> {
            try {
                TaskListResult res = sessionManager.execute(() ->
                        sessionManager.getDs().listTasks(0, 100, null, 15000));
                TransferStats st = transferStats;
                try {
                    st = sessionManager.execute(() -> sessionManager.getDs().getStatistics());
                } catch (Exception ignored) {}

                final List<TaskItem> newTasks = res.tasks;
                final TransferStats newStats = st;
                uiHandler.post(() -> {
                    isInitialTasksLoading = false;
                    tasks = newTasks;
                    transferStats = newStats;
                    if (isManual) {
                        finishManualRefreshSpinner();
                    }
                    if (currentScreen == Screen.TASKS) {
                        updateHeaderStatsLabel();
                        renderTaskCards();
                    }
                });
            } catch (Exception ignored) {
                uiHandler.post(() -> {
                    isInitialTasksLoading = false;
                    if (isManual) {
                        finishManualRefreshSpinner();
                    }
                    if (currentScreen == Screen.TASKS && tasks.isEmpty()) {
                        renderTaskCards();
                    }
                });
            }
        });
    }

    // ── 3. Task Detail View ────────────────────────────────────────

    private View buildTaskDetailView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        final TaskItem task = activeDetailTask != null ? activeDetailTask : new TaskItem();

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = UiTheme.dp(this, 16);
        int padV = UiTheme.dp(this, 12);
        header.setPadding(padH, padV, padH, padV);
        header.setBackgroundColor(UiTheme.COLOR_BG_CARD);

        TextView backBtn = UiTheme.makeText(this, "← Back", 16, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        backBtn.setPadding(0, UiTheme.dp(this, 4), UiTheme.dp(this, 16), UiTheme.dp(this, 4));
        backBtn.setOnClickListener(v -> navigateTo(Screen.TASKS));

        TextView titleTv = UiTheme.makeText(this, task.title, 15, UiTheme.COLOR_TEXT_PRIMARY, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(backBtn);
        header.addView(titleTv);
        page.addView(header);

        // Action Bar container (Resume / Pause / Delete or Clear)
        detailActionBar = new LinearLayout(this);
        detailActionBar.setOrientation(LinearLayout.HORIZONTAL);
        int aPad = UiTheme.dp(this, 12);
        detailActionBar.setPadding(aPad, aPad, aPad, aPad);
        detailActionBar.setBackgroundColor(UiTheme.COLOR_BG_CARD);
        page.addView(detailActionBar);

        renderTaskDetailActionBar();

        // Tabs: Info | Tracker | Peers | File
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(UiTheme.COLOR_BG_CARD);
        final String[] tabs = {"Info", "Tracker", "Peers", "File"};
        for (int i = 0; i < tabs.length; i++) {
            final String tName = tabs[i];
            boolean active = tName.equals(activeDetailTab);
            LinearLayout tabItem = new LinearLayout(this);
            tabItem.setOrientation(LinearLayout.VERTICAL);
            tabItem.setGravity(Gravity.CENTER);
            tabItem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabItem.setClickable(true);
            tabItem.setFocusable(true);
            android.graphics.drawable.Drawable tabSel = UiTheme.makeSelectableBackground(this);
            if (tabSel != null) tabItem.setBackground(tabSel);

            TextView tabTv = UiTheme.makeText(
                    this,
                    tName,
                    14,
                    active ? UiTheme.COLOR_SYNOLOGY_BLUE : UiTheme.COLOR_TEXT_MUTED,
                    true
            );
            tabTv.setGravity(Gravity.CENTER);
            tabTv.setPadding(0, UiTheme.dp(this, 12), 0, UiTheme.dp(this, 10));
            detailTabLabels[i] = tabTv;
            tabItem.addView(tabTv);

            View indicator = new View(this);
            indicator.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 2)));
            indicator.setBackgroundColor(active ? UiTheme.COLOR_SYNOLOGY_BLUE : Color.TRANSPARENT);
            detailTabIndicators[i] = indicator;
            tabItem.addView(indicator);

            tabItem.setOnClickListener(v -> {
                activeDetailTab = tName;
                updateDetailTabHighlight();
                renderTaskDetailTabContent();
            });
            tabRow.addView(tabItem);
        }
        page.addView(tabRow);

        detailContentFrame = new FrameLayout(this);
        detailContentFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(detailContentFrame);

        renderTaskDetailTabContent();
        return page;
    }

    private void updateDetailTabHighlight() {
        final String[] tabs = {"Info", "Tracker", "Peers", "File"};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = tabs[i].equals(activeDetailTab);
            if (detailTabLabels[i] != null) {
                detailTabLabels[i].setTextColor(active ? UiTheme.COLOR_SYNOLOGY_BLUE : UiTheme.COLOR_TEXT_MUTED);
            }
            if (detailTabIndicators[i] != null) {
                detailTabIndicators[i].setBackgroundColor(active ? UiTheme.COLOR_SYNOLOGY_BLUE : Color.TRANSPARENT);
            }
        }
    }

    private void renderTaskDetailActionBar() {
        if (detailActionBar == null || activeDetailTask == null) return;
        detailActionBar.removeAllViews();

        final TaskItem task = activeDetailTask;
        boolean isPaused = "paused".equals(task.status) || "error".equals(task.status);
        boolean isFinished = "finished".equals(task.status);

        if (isPaused) {
            TextView resumeBtn = UiTheme.makeButton(this, "▶ Resume", UiTheme.COLOR_GREEN, UiTheme.COLOR_TEXT_PRIMARY, v -> {
                task.status = "downloading";
                updateTaskStatusInList(task.id, "downloading");
                renderTaskDetailActionBar();
                renderTaskDetailTabContent();
                runSingleTaskAction(() -> sessionManager.getDs().resumeTasks(Collections.singletonList(task.id)), false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = UiTheme.dp(this, 8);
            resumeBtn.setLayoutParams(lp);
            detailActionBar.addView(resumeBtn);
        } else if (!isFinished) {
            TextView pauseBtn = UiTheme.makeButton(this, "⏸ Pause", UiTheme.COLOR_AMBER, UiTheme.COLOR_TEXT_PRIMARY, v -> {
                task.status = "paused";
                updateTaskStatusInList(task.id, "paused");
                renderTaskDetailActionBar();
                renderTaskDetailTabContent();
                runSingleTaskAction(() -> sessionManager.getDs().pauseTasks(Collections.singletonList(task.id)), false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = UiTheme.dp(this, 8);
            pauseBtn.setLayoutParams(lp);
            detailActionBar.addView(pauseBtn);
        }

        TextView delBtn = UiTheme.makeButton(
                this,
                isFinished ? "✕ Clear" : "🗑 Delete",
                UiTheme.COLOR_RED,
                UiTheme.COLOR_TEXT_PRIMARY,
                v -> AppDialogs.showConfirmDialog(
                        this,
                        "Remove Task",
                        "Are you sure you want to remove \"" + task.title + "\"?",
                        "Remove",
                        () -> runSingleTaskAction(() -> sessionManager.getDs().deleteTasks(Collections.singletonList(task.id), false), true)
                )
        );
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        detailActionBar.addView(delBtn);
    }

    private void updateTaskStatusInList(String taskId, String newStatus) {
        for (TaskItem t : tasks) {
            if (taskId.equals(t.id)) {
                t.status = newStatus;
                break;
            }
        }
    }

    private void renderTaskDetailTabContent() {
        if (detailContentFrame == null || activeDetailTask == null) return;
        detailContentFrame.removeAllViews();

        final TaskItem info = activeDetailTask;
        int outerPad = UiTheme.dp(this, 16);

        if ("File".equals(activeDetailTab)) {
            if (activeDetailFileError != null) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(outerPad, outerPad, outerPad, outerPad);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(outerPad, outerPad, outerPad, outerPad);
                card.setBackground(UiTheme.roundedRect(Color.parseColor("#1AFF6B6B"), UiTheme.dp(this, 8)));
                TextView errTv = UiTheme.makeText(this, activeDetailFileError, 14, UiTheme.COLOR_RED, false);
                errTv.setGravity(Gravity.CENTER);
                card.addView(errTv);
                box.addView(card);
                detailContentFrame.addView(box);
                return;
            }
            if (activeDetailFiles.isEmpty()) {
                LinearLayout emptyBox = new LinearLayout(this);
                emptyBox.setGravity(Gravity.CENTER);
                emptyBox.setPadding(outerPad, UiTheme.dp(this, 32), outerPad, outerPad);
                if (isDetailLoading) {
                    ProgressBar pb = new ProgressBar(this);
                    pb.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
                    emptyBox.addView(pb);
                } else {
                    emptyBox.addView(UiTheme.makeText(this, "No files found in this task.", 15, UiTheme.COLOR_TEXT_MUTED, false));
                }
                detailContentFrame.addView(emptyBox);
                return;
            }

            ListView listView = new ListView(this);
            listView.setPadding(outerPad, outerPad, outerPad, outerPad);
            listView.setClipToPadding(false);
            listView.setDivider(new ColorDrawable(UiTheme.COLOR_DIVIDER));
            listView.setDividerHeight(Math.max(1, UiTheme.dp(this, 1)));
            listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
            listView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            final List<TaskFileItem> filesSnapshot = new ArrayList<>(activeDetailFiles);
            listView.setAdapter(new BaseAdapter() {
                @Override
                public int getCount() {
                    return filesSnapshot.size();
                }

                @Override
                public TaskFileItem getItem(int position) {
                    return filesSnapshot.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return filesSnapshot.get(position).index;
                }

                class Holder {
                    TextView nameTv;
                    TextView statsTv;
                    Switch sw;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    Holder h;
                    if (convertView == null) {
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(0, UiTheme.dp(MainActivity.this, 12), 0, UiTheme.dp(MainActivity.this, 12));

                        LinearLayout col = new LinearLayout(MainActivity.this);
                        col.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        colLp.rightMargin = UiTheme.dp(MainActivity.this, 16);
                        col.setLayoutParams(colLp);

                        TextView nameTv = UiTheme.makeText(MainActivity.this, "", 14, UiTheme.COLOR_TEXT_SECONDARY, false);
                        nameTv.setMaxLines(2);
                        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);

                        TextView statsTv = UiTheme.makeText(MainActivity.this, "", 12, UiTheme.COLOR_TEXT_MUTED, false);
                        statsTv.setPadding(0, UiTheme.dp(MainActivity.this, 4), 0, 0);
                        col.addView(nameTv);
                        col.addView(statsTv);

                        Switch sw = new Switch(MainActivity.this);
                        UiTheme.styleSwitch(sw);

                        row.addView(col);
                        row.addView(sw);

                        h = new Holder();
                        h.nameTv = nameTv;
                        h.statsTv = statsTv;
                        h.sw = sw;
                        row.setTag(h);
                        convertView = row;
                    } else {
                        h = (Holder) convertView.getTag();
                    }

                    final TaskFileItem fileItem = getItem(position);
                    double fPct = fileItem.size > 0 ? (fileItem.sizeDownloaded * 100.0) / fileItem.size : 0.0;
                    h.nameTv.setText(fileItem.name);
                    h.statsTv.setText(
                            FormatUtils.formatBytes(fileItem.sizeDownloaded) + " / " + FormatUtils.formatBytes(fileItem.size)
                                    + String.format(Locale.US, " (%.1f%%)", fPct)
                    );

                    h.sw.setOnCheckedChangeListener(null);
                    h.sw.setChecked(fileItem.wanted);
                    h.sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        fileItem.wanted = isChecked;
                        bgExecutor.execute(() -> {
                            try {
                                sessionManager.execute(() -> {
                                    sessionManager.getDs().setTaskFileWanted(
                                            info.id,
                                            Collections.singletonList(fileItem.index),
                                            isChecked
                                    );
                                    return null;
                                });
                            } catch (Exception e) {
                                fileItem.wanted = !isChecked;
                                uiHandler.post(() -> {
                                    Toast.makeText(MainActivity.this, "Failed to update file priority", Toast.LENGTH_SHORT).show();
                                    notifyDataSetChanged();
                                });
                            }
                        });
                    });

                    return convertView;
                }
            });

            detailContentFrame.addView(listView);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        LinearLayout scrollInner = new LinearLayout(this);
        scrollInner.setOrientation(LinearLayout.VERTICAL);
        scrollInner.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(outerPad, outerPad, outerPad, outerPad);
        card.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_CARD, UiTheme.dp(this, 8)));

        switch (activeDetailTab) {
            case "Info": {
                double progress = info.size > 0 ? (info.sizeDownloaded * 100.0) / info.size : 0.0;
                card.addView(UiTheme.makeInfoRow(this, "File name:", info.title));
                card.addView(UiTheme.makeInfoRow(this, "Status:", info.status));
                card.addView(UiTheme.makeInfoRow(this, "File size:", FormatUtils.formatBytes(info.size)));
                card.addView(UiTheme.makeInfoRow(this, "Destination:", info.destination));
                card.addView(UiTheme.makeInfoRow(this, "Progress:",
                        FormatUtils.formatBytes(info.sizeDownloaded) + " / " + FormatUtils.formatBytes(info.size)
                                + String.format(Locale.US, " (%.1f%%)", progress)));

                if (!"finished".equals(info.status)) {
                    card.addView(UiTheme.makeInfoRow(this, "Speed (↓/↑):",
                            FormatUtils.formatBytes(info.speedDownload) + "/s / " + FormatUtils.formatBytes(info.speedUpload) + "/s"));

                    String etaStr = null;
                    if ("downloading".equals(info.status)) {
                        if (info.speedDownload > 0 && info.size > 0) {
                            etaStr = FormatUtils.formatEta((info.size - info.sizeDownloaded) / info.speedDownload);
                        } else if (info.eta > 0) {
                            etaStr = FormatUtils.formatEta(info.eta);
                        } else {
                            etaStr = "Unknown";
                        }
                    } else if ("waiting".equals(info.status) && info.waitingSeconds > 0) {
                        etaStr = "Waiting ~" + FormatUtils.formatEta(info.waitingSeconds);
                    }
                    if (etaStr != null) {
                        card.addView(UiTheme.makeInfoRow(this, "Time Left:", etaStr));
                    }

                    card.addView(UiTheme.makeInfoRow(this, "Connected Peers:", FormatUtils.formatConnectedPeers(info)));
                    int totalPeers = FormatUtils.computeTotalPeers(info);
                    if (totalPeers > 0) {
                        card.addView(UiTheme.makeInfoRow(this, "Total Peers:", String.valueOf(totalPeers)));
                    }
                }

                card.addView(UiTheme.makeInfoRow(this, "Transfer (↓/↑):",
                        FormatUtils.formatBytes(info.sizeDownloaded) + " / " + FormatUtils.formatBytes(info.sizeUploaded)));
                card.addView(UiTheme.makeInfoRow(this, "Downloaded Pieces:", FormatUtils.formatPieces(info)));
                card.addView(UiTheme.makeInfoRow(this, "URL:", info.uri, 1));

                DateFormat df = DateFormat.getDateTimeInstance();
                if (info.startedTime > 0) {
                    card.addView(UiTheme.makeInfoRow(this, "Started:", df.format(new Date(info.startedTime * 1000L))));
                }
                if (info.completedTime > 0) {
                    card.addView(UiTheme.makeInfoRow(this, "Completed:", df.format(new Date(info.completedTime * 1000L))));
                }
                if (info.createTime > 0 && info.startedTime == 0) {
                    card.addView(UiTheme.makeInfoRow(this, "Created:", df.format(new Date(info.createTime * 1000L))));
                }
                scrollInner.addView(card);
                break;
            }
            case "Tracker": {
                if (info.trackers == null || info.trackers.isEmpty()) {
                    TextView emptyTrackerTv = UiTheme.makeText(this, "No tracker information available.", 15, UiTheme.COLOR_TEXT_MUTED, false);
                    emptyTrackerTv.setGravity(Gravity.CENTER);
                    scrollInner.addView(emptyTrackerTv);
                    break;
                }
                List<TrackerItem> sortedTrackers = new ArrayList<>(info.trackers);
                sortedTrackers.sort((a, b) -> {
                    String aS = a.status != null ? a.status.toLowerCase(Locale.US) : "";
                    String bS = b.status != null ? b.status.toLowerCase(Locale.US) : "";
                    boolean aW = aS.contains("success") || aS.contains("working") || aS.contains("alive");
                    boolean bW = bS.contains("success") || bS.contains("working") || bS.contains("alive");
                    if (aW != bW) return aW ? -1 : 1;
                    return Integer.compare(b.seeds, a.seeds);
                });
                for (TrackerItem tr : sortedTrackers) {
                    String host = tr.url;
                    String protocol = "";
                    String port = "";
                    try {
                        URI u = new URI(tr.url);
                        if (u.getHost() != null) host = u.getHost();
                        if (u.getScheme() != null) protocol = u.getScheme().toUpperCase(Locale.US);
                        if (u.getPort() > 0) {
                            port = String.valueOf(u.getPort());
                        } else if ("HTTPS".equals(protocol)) {
                            port = "443";
                        } else if ("HTTP".equals(protocol)) {
                            port = "80";
                        }
                    } catch (Exception ignored) {}

                    final String finalHost = host;
                    GeoInfo cachedGeo = GeolocationHelper.getCached(finalHost);
                    if (cachedGeo == null) {
                        bgExecutor.execute(() -> {
                            GeoInfo resolved = GeolocationHelper.lookupIpCountry(finalHost);
                            if (resolved != null && currentScreen == Screen.TASK_DETAIL && "Tracker".equals(activeDetailTab)) {
                                uiHandler.post(this::renderTaskDetailTabContent);
                            }
                        });
                    }

                    String prefix = (cachedGeo != null && !cachedGeo.flag.isEmpty()) ? cachedGeo.flag + " " : "";
                    String hostWithPort = host + (port.isEmpty() ? "" : ":" + port);

                    LinearLayout titleRow = new LinearLayout(this);
                    titleRow.setOrientation(LinearLayout.HORIZONTAL);
                    titleRow.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    trLp.bottomMargin = UiTheme.dp(this, 4);
                    titleRow.setLayoutParams(trLp);

                    TextView tTitle = UiTheme.makeText(this, prefix + hostWithPort, 14, UiTheme.COLOR_TEXT_SECONDARY, true);
                    tTitle.setSingleLine(true);
                    tTitle.setEllipsize(TextUtils.TruncateAt.END);
                    tTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    titleRow.addView(tTitle);

                    if (!protocol.isEmpty()) {
                        int badgeColor = "UDP".equals(protocol)
                                ? Color.parseColor("#673AB7")
                                : ("HTTPS".equals(protocol) ? UiTheme.COLOR_GREEN : Color.parseColor("#757575"));
                        TextView badge = UiTheme.makeText(this, protocol, 10, UiTheme.COLOR_TEXT_PRIMARY, true);
                        badge.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
                        badge.setBackground(UiTheme.roundedRect(badgeColor, UiTheme.dp(this, 4)));
                        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        );
                        bLp.leftMargin = UiTheme.dp(this, 8);
                        badge.setLayoutParams(bLp);
                        titleRow.addView(badge);
                    }

                    LinearLayout tSubRow = new LinearLayout(this);
                    tSubRow.setOrientation(LinearLayout.HORIZONTAL);
                    tSubRow.setGravity(Gravity.CENTER_VERTICAL);
                    tSubRow.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 8));

                    tSubRow.addView(UiTheme.makeText(this, "Status: ", 12, UiTheme.COLOR_TEXT_MUTED, false));
                    String statusLower = tr.status != null ? tr.status.toLowerCase(Locale.US) : "";
                    boolean isWorkingStatus = statusLower.contains("success") || statusLower.contains("working") || statusLower.contains("alive");
                    int statusColor = isWorkingStatus ? UiTheme.COLOR_GREEN : UiTheme.COLOR_TEXT_MUTED;
                    tSubRow.addView(UiTheme.makeText(this, tr.status, 12, statusColor, false));
                    tSubRow.addView(UiTheme.makeText(this, "  |  ", 12, UiTheme.COLOR_TEXT_MUTED, false));

                    ImageView tUp = makeTintedIcon(R.drawable.ic_arrow_up, 11, UiTheme.COLOR_GREEN);
                    ((LinearLayout.LayoutParams) tUp.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
                    tSubRow.addView(tUp);
                    tSubRow.addView(UiTheme.makeText(this, tr.seeds + "  |  ", 12, UiTheme.COLOR_TEXT_MUTED, false));

                    ImageView tDown = makeTintedIcon(R.drawable.ic_arrow_down, 11, UiTheme.COLOR_AMBER);
                    ((LinearLayout.LayoutParams) tDown.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
                    tSubRow.addView(tDown);
                    tSubRow.addView(UiTheme.makeText(this, String.valueOf(tr.peers), 12, UiTheme.COLOR_TEXT_MUTED, false));

                    card.addView(titleRow);
                    card.addView(tSubRow);
                    card.addView(UiTheme.makeDivider(this, 4));
                }
                scrollInner.addView(card);
                break;
            }
            case "Peers": {
                if (info.peers == null || info.peers.isEmpty()) {
                    TextView emptyPeersTv = UiTheme.makeText(this, "No peer information available.", 15, UiTheme.COLOR_TEXT_MUTED, false);
                    emptyPeersTv.setGravity(Gravity.CENTER);
                    scrollInner.addView(emptyPeersTv);
                    break;
                }
                for (PeerItem peer : info.peers) {
                    GeoInfo cachedGeo = GeolocationHelper.getCached(peer.address);
                    if (cachedGeo == null) {
                        bgExecutor.execute(() -> {
                            GeoInfo resolved = GeolocationHelper.lookupIpCountry(peer.address);
                            if (resolved != null && currentScreen == Screen.TASK_DETAIL && "Peers".equals(activeDetailTab)) {
                                uiHandler.post(this::renderTaskDetailTabContent);
                            }
                        });
                    }
                    String flag = (cachedGeo != null && !cachedGeo.flag.isEmpty()) ? cachedGeo.flag + " " : "";
                    card.addView(UiTheme.makeText(this, flag + peer.address, 14, UiTheme.COLOR_TEXT_SECONDARY, true));
                    TextView sub = UiTheme.makeText(
                            this,
                            "Client: " + peer.agent + " | DL: " + FormatUtils.formatBytes(peer.speedDownload)
                                    + "/s | UL: " + FormatUtils.formatBytes(peer.speedUpload) + "/s",
                            12,
                            UiTheme.COLOR_TEXT_MUTED,
                            false
                    );
                    sub.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 8));
                    card.addView(sub);
                    card.addView(UiTheme.makeDivider(this, 4));
                }
                scrollInner.addView(card);
                break;
            }
        }
        scroll.addView(scrollInner);
        detailContentFrame.addView(scroll);
    }

    private void refreshTaskDetailAsync(String taskId, boolean silent) {
        bgExecutor.execute(() -> {
            try {
                List<TaskItem> infos = sessionManager.execute(() ->
                        sessionManager.getDs().getTaskInfo(Collections.singletonList(taskId)));
                TaskItem updatedInfo = !infos.isEmpty() ? infos.get(0) : activeDetailTask;

                List<TaskFileItem> fetchedFiles = new ArrayList<>();
                String fileErr = null;
                try {
                    fetchedFiles = sessionManager.getDs().getTaskFiles(taskId);
                } catch (SynologyError se) {
                    if (se.getCode() == 1913) {
                        fileErr = "Task files cannot be viewed. The task is paused, inactive, or not a BitTorrent task.";
                    } else {
                        fileErr = se.getMessage();
                    }
                } catch (Exception e) {
                    fileErr = e.getMessage();
                }

                final TaskItem finalInfo = updatedInfo;
                final List<TaskFileItem> finalFiles = fetchedFiles;
                final String finalErr = fileErr;
                uiHandler.post(() -> {
                    isDetailLoading = false;
                    activeDetailTask = finalInfo;
                    activeDetailFiles = finalFiles;
                    activeDetailFileError = finalErr;
                    if (currentScreen == Screen.TASK_DETAIL) {
                        renderTaskDetailActionBar();
                        renderTaskDetailTabContent();
                    }
                });
            } catch (Exception ignored) {
                uiHandler.post(() -> {
                    isDetailLoading = false;
                    if (currentScreen == Screen.TASK_DETAIL && "File".equals(activeDetailTab)) {
                        renderTaskDetailTabContent();
                    }
                });
            }
        });
    }

    private void runSingleTaskAction( RunnableWithException action, boolean goBackAfter) {
        bgExecutor.execute(() -> {
            try {
                sessionManager.execute(() -> {
                    action.run();
                    return null;
                });
                uiHandler.post(() -> {
                    if (goBackAfter) {
                        navigateTo(Screen.TASKS);
                    } else if (activeDetailTask != null) {
                        renderTaskDetailActionBar();
                        renderTaskDetailTabContent();
                        refreshTaskDetailAsync(activeDetailTask.id, false);
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private interface RunnableWithException {
        void run() throws Exception;
    }

    // ── 4. Search View ─────────────────────────────────────────────

    private ImageView makeTintedIcon(int resId, int sizeDp, int color) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(resId);
        iv.setColorFilter(color);
        int px = UiTheme.dp(this, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return iv;
    }

    private View buildSearchView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Search input header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = UiTheme.dp(this, 20);
        header.setPadding(padH, UiTheme.dp(this, 12), padH, UiTheme.dp(this, 16));
        header.setBackgroundColor(UiTheme.COLOR_BG_CARD);

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_INPUT, UiTheme.dp(this, 8)));
        searchBar.setPadding(UiTheme.dp(this, 12), 0, UiTheme.dp(this, 12), 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, UiTheme.dp(this, 44), 1f);
        barLp.rightMargin = UiTheme.dp(this, 12);
        searchBar.setLayoutParams(barLp);

        ImageView leftSearchIcon = makeTintedIcon(R.drawable.ic_search, 20, UiTheme.COLOR_TEXT_MUTED);
        ((LinearLayout.LayoutParams) leftSearchIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 8);
        searchBar.addView(leftSearchIcon);

        final EditText searchInput = UiTheme.makeInput(this, "Search for torrents...", InputType.TYPE_CLASS_TEXT);
        searchInput.setBackground(null);
        searchInput.setPadding(0, 0, 0, 0);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (searchController != null) {
            searchInput.setText(searchController.getKeyword());
        }
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addView(searchInput);

        final ImageView clearInputBtn = makeTintedIcon(R.drawable.ic_close, 20, UiTheme.COLOR_TEXT_MUTED);
        ((LinearLayout.LayoutParams) clearInputBtn.getLayoutParams()).leftMargin = UiTheme.dp(this, 8);
        clearInputBtn.setClickable(true);
        clearInputBtn.setFocusable(true);
        android.graphics.drawable.Drawable clearSel = UiTheme.makeSelectableBackground(this);
        if (clearSel != null) clearInputBtn.setBackground(clearSel);
        clearInputBtn.setVisibility(searchInput.getText().length() > 0 ? View.VISIBLE : View.GONE);
        clearInputBtn.setOnClickListener(v -> searchInput.setText(""));
        searchBar.addView(clearInputBtn);

        final TextView searchBtn = UiTheme.makeText(this, "Search", 16, UiTheme.COLOR_SYNOLOGY_BLUE, true);
        searchBtn.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 8));
        searchBtn.setAlpha(searchInput.getText().toString().trim().isEmpty() ? 0.5f : 1.0f);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.length() > 0;
                clearInputBtn.setVisibility(hasText ? View.VISIBLE : View.GONE);
                boolean hasTrimmed = s != null && !s.toString().trim().isEmpty();
                searchBtn.setAlpha(hasTrimmed ? 1.0f : 0.5f);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        Runnable triggerSearch = () -> {
            UiTheme.hideKeyboard(this, searchInput);
            String q = searchInput.getText().toString().trim();
            if (q.isEmpty()) return;
            triggerBtSearch(q);
        };
        searchBtn.setOnClickListener(v -> triggerSearch.run());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch.run();
                return true;
            }
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    triggerSearch.run();
                }
                return true;
            }
            return false;
        });

        header.addView(searchBar);
        header.addView(searchBtn);
        page.addView(header);

        // Save to: destination bar
        LinearLayout destBar = new LinearLayout(this);
        destBar.setOrientation(LinearLayout.HORIZONTAL);
        destBar.setGravity(Gravity.CENTER_VERTICAL);
        destBar.setPadding(padH, 0, padH, UiTheme.dp(this, 12));
        destBar.setBackgroundColor(UiTheme.COLOR_BG_CARD);
        destBar.addView(UiTheme.makeText(this, "Save to:  ", 13, UiTheme.COLOR_TEXT_MUTED, false));

        String dispDest = !searchDestination.isEmpty() ? searchDestination : (!defaultDestination.isEmpty() ? defaultDestination : "Default Share");
        LinearLayout destSelector = new LinearLayout(this);
        destSelector.setOrientation(LinearLayout.HORIZONTAL);
        destSelector.setGravity(Gravity.CENTER_VERTICAL);
        destSelector.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));
        destSelector.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_INPUT, UiTheme.dp(this, 6)));
        destSelector.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        destSelector.setClickable(true);
        destSelector.setFocusable(true);

        ImageView folderIcon = makeTintedIcon(R.drawable.ic_folder, 14, UiTheme.COLOR_SYNOLOGY_BLUE);
        ((LinearLayout.LayoutParams) folderIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 6);
        destSelector.addView(folderIcon);

        final TextView destPathTv = UiTheme.makeText(this, dispDest, 13, UiTheme.COLOR_TEXT_SECONDARY, false);
        destPathTv.setSingleLine(true);
        destPathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        destPathTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        destSelector.addView(destPathTv);

        ImageView editIcon = makeTintedIcon(R.drawable.ic_edit, 12, UiTheme.COLOR_TEXT_MUTED);
        ((LinearLayout.LayoutParams) editIcon.getLayoutParams()).leftMargin = UiTheme.dp(this, 8);
        destSelector.addView(editIcon);

        destSelector.setOnClickListener(v -> {
            UiTheme.hideKeyboard(this, searchInput);
            AppDialogs.showFolderPickerDialog(
                    this,
                    sessionManager,
                    prefsStore,
                    bgExecutor,
                    "Select Task Destination",
                    defaultDestination,
                    selected -> {
                        searchDestination = selected;
                        destPathTv.setText(selected);
                    }
            );
        });
        destBar.addView(destSelector);
        page.addView(destBar);

        View destDivider = new View(this);
        destDivider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTheme.dp(this, 1))));
        destDivider.setBackgroundColor(UiTheme.COLOR_DIVIDER);
        page.addView(destDivider);

        // Collapsible engine status banner
        searchStatusBanner = new LinearLayout(this);
        searchStatusBanner.setOrientation(LinearLayout.VERTICAL);
        searchStatusBanner.setBackgroundColor(UiTheme.COLOR_BG_SEARCH_STATUS);
        page.addView(searchStatusBanner);

        // Sort bar
        searchSortBar = new LinearLayout(this);
        searchSortBar.setOrientation(LinearLayout.HORIZONTAL);
        searchSortBar.setGravity(Gravity.CENTER_VERTICAL);
        searchSortBar.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 10), UiTheme.dp(this, 16), UiTheme.dp(this, 10));
        searchSortBar.setBackgroundColor(Color.parseColor("#1A1A1A"));
        page.addView(searchSortBar);

        // Results scroll
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setOnTouchListener((v, ev) -> {
            UiTheme.hideKeyboard(MainActivity.this, searchInput);
            return false;
        });
        searchResultsContainer = new LinearLayout(this);
        searchResultsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(searchResultsContainer);
        page.addView(scroll);

        renderSearchDynamicSections();
        return page;
    }

    private void triggerBtSearch(String query) {
        if (searchController == null) return;
        uiHandler.removeCallbacks(searchPollRunnable);
        showEngineDetails = true;
        searchStartTimestamp = System.currentTimeMillis();

        bgExecutor.execute(() -> {
            try {
                searchController.startSearch(query);
                uiHandler.post(() -> {
                    renderSearchDynamicSections();
                    uiHandler.post(searchPollRunnable);
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    showEngineDetails = false;
                    renderSearchDynamicSections();
                    Toast.makeText(this, "Search failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private LinearLayout buildSortPill(String label, String field, boolean active, boolean desc) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 4), UiTheme.dp(this, 10), UiTheme.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UiTheme.dp(this, 8);
        pill.setLayoutParams(lp);
        pill.setClickable(true);
        pill.setFocusable(true);
        if (active) {
            pill.setBackground(UiTheme.roundedRectWithBorder(
                    Color.parseColor("#1A00A1E4"),
                    UiTheme.COLOR_SYNOLOGY_BLUE,
                    UiTheme.dp(this, 1),
                    UiTheme.dp(this, 12)
            ));
        } else {
            pill.setBackground(UiTheme.roundedRect(UiTheme.COLOR_BG_INPUT, UiTheme.dp(this, 12)));
        }

        TextView tv = UiTheme.makeText(
                this,
                label,
                12,
                active ? UiTheme.COLOR_SYNOLOGY_BLUE : UiTheme.COLOR_TEXT_MUTED,
                active
        );
        pill.addView(tv);

        if (active) {
            ImageView chev = makeTintedIcon(
                    desc ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_up,
                    14,
                    UiTheme.COLOR_SYNOLOGY_BLUE
            );
            ((LinearLayout.LayoutParams) chev.getLayoutParams()).leftMargin = UiTheme.dp(this, 4);
            pill.addView(chev);
        }

        pill.setOnClickListener(v -> {
            searchController.toggleSort(field);
            renderSearchDynamicSections();
        });
        return pill;
    }

    private void renderSearchDynamicSections() {
        if (searchController == null || searchStatusBanner == null || searchSortBar == null || searchResultsContainer == null) {
            return;
        }

        List<SearchResultItem> results = searchController.getSortedResults();
        List<EngineStatus> engines = searchController.getEngineStatuses();
        boolean searching = searchController.isSearching();
        boolean finished = searchController.isFinished();

        // 1. Engine Status Banner
        searchStatusBanner.removeAllViews();
        if (searching) {
            searchStatusBanner.setVisibility(View.VISIBLE);
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            int padH = UiTheme.dp(this, 16);
            int padV = UiTheme.dp(this, 12);
            topRow.setPadding(padH, padV, padH, padV);
            topRow.setClickable(true);
            topRow.setFocusable(true);
            topRow.setOnClickListener(v -> {
                UiTheme.hideKeyboard(this, null);
                showEngineDetails = !showEngineDetails;
                renderSearchDynamicSections();
            });

            if (!finished) {
                ProgressBar spinner = new ProgressBar(this);
                int spSize = UiTheme.dp(this, 16);
                spinner.setLayoutParams(new LinearLayout.LayoutParams(spSize, spSize));
                spinner.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
                topRow.addView(spinner);
            } else {
                topRow.addView(makeTintedIcon(R.drawable.ic_check_circle, 16, UiTheme.COLOR_GREEN));
            }

            LinearLayout statusCol = new LinearLayout(this);
            statusCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = UiTheme.dp(this, 8);
            statusCol.setLayoutParams(colLp);

            String headline = (finished ? "Search finished" : "Searching...") + " (" + results.size() + " found)";
            statusCol.addView(UiTheme.makeText(this, headline, 13, UiTheme.COLOR_SYNOLOGY_BLUE, true));

            if (!finished) {
                String sub = engines.isEmpty()
                        ? "Initializing search engines..."
                        : searchController.getDoneEngineCount() + "/" + engines.size() + " engines done";
                TextView subTv = UiTheme.makeText(this, sub, 11, UiTheme.COLOR_TEXT_MUTED, false);
                subTv.setPadding(0, UiTheme.dp(this, 2), 0, 0);
                statusCol.addView(subTv);
            }
            topRow.addView(statusCol);

            ImageView chevronIcon = makeTintedIcon(
                    showEngineDetails ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down,
                    20,
                    UiTheme.COLOR_TEXT_MUTED
            );
            ((LinearLayout.LayoutParams) chevronIcon.getLayoutParams()).leftMargin = UiTheme.dp(this, 8);
            topRow.addView(chevronIcon);

            if (!finished) {
                LinearLayout stopBtn = new LinearLayout(this);
                stopBtn.setOrientation(LinearLayout.HORIZONTAL);
                stopBtn.setGravity(Gravity.CENTER_VERTICAL);
                stopBtn.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 4), UiTheme.dp(this, 8), UiTheme.dp(this, 4));
                LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                stopLp.leftMargin = UiTheme.dp(this, 8);
                stopBtn.setLayoutParams(stopLp);
                stopBtn.setBackground(UiTheme.roundedRect(Color.parseColor("#1AFF6B6B"), UiTheme.dp(this, 4)));
                stopBtn.setClickable(true);
                stopBtn.setFocusable(true);

                ImageView stopIc = makeTintedIcon(R.drawable.ic_stop_circle, 18, UiTheme.COLOR_RED);
                stopBtn.addView(stopIc);

                TextView stopTxt = UiTheme.makeText(this, "Stop", 13, UiTheme.COLOR_RED, true);
                stopTxt.setPadding(UiTheme.dp(this, 4), 0, 0, 0);
                stopBtn.addView(stopTxt);

                stopBtn.setOnClickListener(v -> {
                    UiTheme.hideKeyboard(this, null);
                    uiHandler.removeCallbacks(searchPollRunnable);
                    bgExecutor.execute(() -> {
                        searchController.stopSearch();
                        uiHandler.post(this::renderSearchDynamicSections);
                    });
                });
                topRow.addView(stopBtn);
            }
            searchStatusBanner.addView(topRow);

            if (showEngineDetails && !engines.isEmpty()) {
                LinearLayout detailBox = new LinearLayout(this);
                detailBox.setOrientation(LinearLayout.VERTICAL);
                detailBox.setBackgroundColor(Color.parseColor("#33000000"));
                detailBox.setPadding(padH, 0, padH, UiTheme.dp(this, 8));
                for (EngineStatus es : engines) {
                    View rowBorder = new View(this);
                    rowBorder.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTheme.dp(this, 1))));
                    rowBorder.setBackgroundColor(Color.parseColor("#0DFFFFFF"));
                    detailBox.addView(rowBorder);

                    LinearLayout er = new LinearLayout(this);
                    er.setOrientation(LinearLayout.HORIZONTAL);
                    er.setGravity(Gravity.CENTER_VERTICAL);
                    er.setPadding(0, UiTheme.dp(this, 6), 0, UiTheme.dp(this, 6));
                    TextView en = UiTheme.makeText(this, es.moduleTitle, 13, UiTheme.COLOR_TEXT_SECONDARY, false);
                    en.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    TextView ec = UiTheme.makeText(this, es.items + " found", 12, UiTheme.COLOR_TEXT_MUTED, false);
                    ec.setPadding(0, 0, UiTheme.dp(this, 10), 0);

                    boolean done = es.isDone();
                    TextView et = UiTheme.makeText(
                            this,
                            done ? "Finished" : "Searching...",
                            11,
                            done ? UiTheme.COLOR_GREEN : UiTheme.COLOR_YELLOW,
                            true
                    );
                    et.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
                    et.setBackground(UiTheme.roundedRect(
                            Color.parseColor(done ? "#264CAF50" : "#26FFC107"),
                            UiTheme.dp(this, 4)
                    ));
                    er.addView(en);
                    er.addView(ec);
                    er.addView(et);
                    detailBox.addView(er);
                }
                searchStatusBanner.addView(detailBox);
            }
            View bannerBorder = new View(this);
            bannerBorder.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTheme.dp(this, 1))));
            bannerBorder.setBackgroundColor(Color.parseColor("#2C3E50"));
            searchStatusBanner.addView(bannerBorder);
        } else {
            searchStatusBanner.setVisibility(View.GONE);
        }

        // 2. Sort Bar
        searchSortBar.removeAllViews();
        if (!results.isEmpty()) {
            searchSortBar.setVisibility(View.VISIBLE);
            TextView sortLbl = UiTheme.makeText(this, "SORT BY:", 12, UiTheme.COLOR_TEXT_MUTED, true);
            sortLbl.setPadding(0, 0, UiTheme.dp(this, 12), 0);
            searchSortBar.addView(sortLbl);

            boolean seedsActive = "seeds".equalsIgnoreCase(searchController.getSortBy());
            boolean sizeActive = "size".equalsIgnoreCase(searchController.getSortBy());
            boolean desc = searchController.isSortDesc();

            searchSortBar.addView(buildSortPill("Seeds", "seeds", seedsActive, desc));
            searchSortBar.addView(buildSortPill("Size", "size", sizeActive, desc));

            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            searchSortBar.addView(spacer);

            TextView clearBtn = UiTheme.makeText(this, "Clear", 12, UiTheme.COLOR_RED, true);
            clearBtn.setOnClickListener(v -> {
                UiTheme.hideKeyboard(this, null);
                uiHandler.removeCallbacks(searchPollRunnable);
                bgExecutor.execute(() -> {
                    searchController.clearSearch();
                    uiHandler.post(() -> renderScreen(Screen.SEARCH));
                });
            });
            searchSortBar.addView(clearBtn);
        } else {
            searchSortBar.setVisibility(View.GONE);
        }

        // 3. Results List
        searchResultsContainer.removeAllViews();
        if (results.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyBox.setPadding(0, UiTheme.dp(this, 80), 0, 0);

            if (!searching) {
                ImageView emptyIc = makeTintedIcon(R.drawable.ic_search, 64, Color.parseColor("#333333"));
                ((LinearLayout.LayoutParams) emptyIc.getLayoutParams()).bottomMargin = UiTheme.dp(this, 16);
                emptyBox.addView(emptyIc);
                TextView findTv = UiTheme.makeText(this, "Find content to download", 18, Color.parseColor("#444444"), false);
                findTv.setGravity(Gravity.CENTER);
                emptyBox.addView(findTv);
            } else if (!finished) {
                ProgressBar pb = new ProgressBar(this);
                pb.setIndeterminateTintList(ColorStateList.valueOf(UiTheme.COLOR_SYNOLOGY_BLUE));
                LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                pbLp.bottomMargin = UiTheme.dp(this, 16);
                pb.setLayoutParams(pbLp);
                emptyBox.addView(pb);
                TextView waitTv = UiTheme.makeText(this, "Waiting for search engines...", 16, UiTheme.COLOR_TEXT_MUTED, false);
                waitTv.setGravity(Gravity.CENTER);
                emptyBox.addView(waitTv);
            } else {
                TextView noResTv = UiTheme.makeText(this, "No results found.", 18, Color.parseColor("#444444"), false);
                noResTv.setGravity(Gravity.CENTER);
                emptyBox.addView(noResTv);
            }
            searchResultsContainer.addView(emptyBox);
            return;
        }

        for (SearchResultItem item : results) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = UiTheme.dp(this, 16);
            row.setPadding(pad, pad, pad, pad);

            LinearLayout mainCol = new LinearLayout(this);
            mainCol.setOrientation(LinearLayout.VERTICAL);
            mainCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView titleTv = UiTheme.makeText(this, item.title, 15, UiTheme.COLOR_TEXT_SECONDARY, true);
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.bottomMargin = UiTheme.dp(this, 6);
            titleTv.setLayoutParams(titleLp);
            mainCol.addView(titleTv);

            LinearLayout metaRow = new LinearLayout(this);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.bottomMargin = UiTheme.dp(this, 4);
            metaRow.setLayoutParams(metaLp);

            TextView sizeTv = UiTheme.makeText(this, FormatUtils.formatBytes(item.size), 13, UiTheme.COLOR_TEXT_MUTED, false);
            sizeTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            metaRow.addView(sizeTv);

            LinearLayout seedsPeersRow = new LinearLayout(this);
            seedsPeersRow.setOrientation(LinearLayout.HORIZONTAL);
            seedsPeersRow.setGravity(Gravity.CENTER_VERTICAL);

            ImageView upIcon = makeTintedIcon(R.drawable.ic_arrow_up, 12, UiTheme.COLOR_GREEN);
            ((LinearLayout.LayoutParams) upIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
            seedsPeersRow.addView(upIcon);

            TextView seedsTv = UiTheme.makeText(this, String.valueOf(item.seeds), 13, UiTheme.COLOR_TEXT_MUTED, false);
            seedsTv.setPadding(0, 0, UiTheme.dp(this, 10), 0);
            seedsPeersRow.addView(seedsTv);

            ImageView downIcon = makeTintedIcon(R.drawable.ic_arrow_down, 12, UiTheme.COLOR_AMBER);
            ((LinearLayout.LayoutParams) downIcon.getLayoutParams()).rightMargin = UiTheme.dp(this, 3);
            seedsPeersRow.addView(downIcon);

            seedsPeersRow.addView(UiTheme.makeText(this, String.valueOf(item.peers), 13, UiTheme.COLOR_TEXT_MUTED, false));
            metaRow.addView(seedsPeersRow);
            mainCol.addView(metaRow);

            TextView engineTv = UiTheme.makeText(this, item.moduleTitle, 12, Color.parseColor("#555555"), false);
            engineTv.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            mainCol.addView(engineTv);

            ImageView dlBtn = makeTintedIcon(R.drawable.ic_download, 24, UiTheme.COLOR_SYNOLOGY_BLUE);
            int dlPad = UiTheme.dp(this, 8);
            int dlSize = UiTheme.dp(this, 40);
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(dlSize, dlSize);
            dlLp.leftMargin = UiTheme.dp(this, 8);
            dlBtn.setLayoutParams(dlLp);
            dlBtn.setPadding(dlPad, dlPad, dlPad, dlPad);
            dlBtn.setClickable(true);
            dlBtn.setFocusable(true);
            android.graphics.drawable.Drawable dlSel = UiTheme.makeSelectableBackground(this);
            if (dlSel != null) dlBtn.setBackground(dlSel);

            dlBtn.setOnClickListener(v -> addSearchResultTask(item));

            row.addView(mainCol);
            row.addView(dlBtn);
            searchResultsContainer.addView(row);
            searchResultsContainer.addView(UiTheme.makeDivider(this, 0));
        }
    }

    private void addSearchResultTask(SearchResultItem item) {
        UiTheme.hideKeyboard(this, null);
        final String dest = !searchDestination.isEmpty() ? searchDestination : defaultDestination;
        Toast.makeText(this, "Adding task...", Toast.LENGTH_SHORT).show();
        bgExecutor.execute(() -> {
            try {
                CreateTaskResult res = sessionManager.execute(() ->
                        sessionManager.getDs().createTask(item.downloadUri, dest, true, null, null));
                if (!res.listIds.isEmpty()) {
                    String listId = res.listIds.get(0);
                    List<TaskFileItem> files = sessionManager.getDs().getFileList(listId);
                    uiHandler.post(() -> AppDialogs.showFileSelectionDialog(this, files, wanted ->
                            finalizeSelectiveDownload(listId, wanted, dest)));
                } else {
                    SmartDownloadService.startOrKick(this);
                    uiHandler.post(() -> Toast.makeText(this, "Download task added: " + item.title, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "Failed to add task: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ── Modals & Bulk Actions ──────────────────────────────────────

    private void openAddTaskDialog(IncomingPayload initialPayload, boolean isIntentMode) {
        activeAddDialogPayloadHolder = new IncomingPayload[]{initialPayload};
        AppDialogs.showAddTaskDialog(
                this,
                sessionManager,
                prefsStore,
                bgExecutor,
                defaultDestination,
                defaultDestination,
                activeAddDialogPayloadHolder,
                isIntentMode,
                new AppDialogs.AddTaskCallbacks() {
                    @Override
                    public void onPickTorrentFileRequested(Runnable refreshDialogUi) {
                        activeAddDialogRefreshCallback = refreshDialogUi;
                        Intent pickIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        pickIntent.setType("*/*");
                        startActivityForResult(pickIntent, REQ_PICK_TORRENT_FILE);
                    }

                    @Override
                    public void onSubmitUrlTask(String url, String destination, Dialog dialog, Runnable resetButtonState) {
                        if (url.isEmpty()) {
                            resetButtonState.run();
                            showErrorAlert("Error", "Please enter a URL or magnet link");
                            return;
                        }
                        bgExecutor.execute(() -> {
                            try {
                                CreateTaskResult res = sessionManager.execute(() ->
                                        sessionManager.getDs().createTask(url, destination, true, null, null));
                                uiHandler.post(() -> {
                                    dialog.dismiss();
                                    handlePostCreateTaskResult(res, destination, "Task added successfully");
                                });
                            } catch (Exception e) {
                                uiHandler.post(() -> {
                                    resetButtonState.run();
                                    showErrorAlert("Error", "Failed to add task: " + e.getMessage());
                                });
                            }
                        });
                    }

                    @Override
                    public void onSubmitTorrentFile(IncomingPayload filePayload, String destination, Dialog dialog, Runnable resetButtonState) {
                        if (filePayload == null || filePayload.fileBytes == null) {
                            resetButtonState.run();
                            return;
                        }
                        bgExecutor.execute(() -> {
                            try {
                                CreateTaskResult res = sessionManager.execute(() ->
                                        sessionManager.getDs().createTaskFromFile(
                                                filePayload.fileName,
                                                filePayload.fileBytes,
                                                destination,
                                                true
                                        ));
                                uiHandler.post(() -> {
                                    dialog.dismiss();
                                    handlePostCreateTaskResult(res, destination, "Torrent file uploaded successfully");
                                });
                            } catch (Exception e) {
                                uiHandler.post(() -> {
                                    resetButtonState.run();
                                    showErrorAlert("Error", "Failed to upload torrent: " + e.getMessage());
                                });
                            }
                        });
                    }
                }
        );
    }

    private void handlePostCreateTaskResult(CreateTaskResult res, String destination, String successMsg) {
        if (res != null && !res.listIds.isEmpty()) {
            String listId = res.listIds.get(0);
            Dialog progress = AppDialogs.showLoadingDialog(this, "Reading torrent contents...");
            bgExecutor.execute(() -> {
                try {
                    List<TaskFileItem> files = sessionManager.getDs().getFileList(listId);
                    uiHandler.post(() -> {
                        try {
                            if (progress.isShowing()) progress.dismiss();
                        } catch (Exception ignored) {}
                        if (files == null || files.isEmpty()) {
                            finalizeSelectiveDownload(listId, Collections.emptyList(), destination);
                        } else {
                            AppDialogs.showFileSelectionDialog(this, files, wanted ->
                                    finalizeSelectiveDownload(listId, wanted, destination));
                        }
                    });
                } catch (Exception e) {
                    uiHandler.post(() -> {
                        try {
                            if (progress.isShowing()) progress.dismiss();
                        } catch (Exception ignored) {}
                        Toast.makeText(this, "Failed to load file list: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } else {
            SmartDownloadService.startOrKick(this);
            Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
            refreshTasksAsync();
        }
    }

    private void finalizeSelectiveDownload(String listId, List<Integer> wantedIndices, String destination) {
        Dialog progress = AppDialogs.showLoadingDialog(this, "Starting download...");
        bgExecutor.execute(() -> {
            try {
                sessionManager.execute(() ->
                        sessionManager.getDs().createTask("", destination, false, listId, wantedIndices));
                SmartDownloadService.startOrKick(this);
                uiHandler.post(() -> {
                    try {
                        if (progress.isShowing()) progress.dismiss();
                    } catch (Exception ignored) {}
                    Toast.makeText(this, "Download started with selected files", Toast.LENGTH_SHORT).show();
                    refreshTasksAsync();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    try {
                        if (progress.isShowing()) progress.dismiss();
                    } catch (Exception ignored) {}
                    Toast.makeText(this, "Failed to finalize selection: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openSettingsModal() {
        AppDialogs.showSettingsDialog(this, sessionManager, defaultDestination, new AppDialogs.SettingsActionsListener() {
            @Override
            public void onResumeAll() {
                List<String> pausedIds = new ArrayList<>();
                for (TaskItem t : tasks) {
                    if ("paused".equals(t.status) || "error".equals(t.status)) pausedIds.add(t.id);
                }
                if (pausedIds.isEmpty()) {
                    Toast.makeText(MainActivity.this, "No paused tasks to resume", Toast.LENGTH_SHORT).show();
                    return;
                }
                runBulkTaskAction(() -> sessionManager.getDs().resumeTasks(pausedIds), "Tasks resumed");
            }

            @Override
            public void onPauseAll() {
                List<String> activeIds = new ArrayList<>();
                for (TaskItem t : tasks) {
                    if ("downloading".equals(t.status) || "waiting".equals(t.status) || "seeding".equals(t.status)) {
                        activeIds.add(t.id);
                    }
                }
                if (activeIds.isEmpty()) {
                    Toast.makeText(MainActivity.this, "No active tasks to pause", Toast.LENGTH_SHORT).show();
                    return;
                }
                runBulkTaskAction(() -> sessionManager.getDs().pauseTasks(activeIds), "Tasks paused");
            }

            @Override
            public void onClearCompleted() {
                runBulkTaskAction(() -> sessionManager.getDs().clearCompletedTasks(), "Completed tasks cleared");
            }

            @Override
            public void onClearFailed() {
                runBulkTaskAction(() -> sessionManager.getDs().clearErrorTasks(), "Failed tasks cleared");
            }

            @Override
            public void onRemoveAll() {
                AppDialogs.showConfirmDialog(MainActivity.this, "Remove All", "Are you sure you want to remove all tasks?", "Remove", () -> {
                    List<String> allIds = new ArrayList<>();
                    for (TaskItem t : tasks) allIds.add(t.id);
                    if (allIds.isEmpty()) return;
                    runBulkTaskAction(() -> sessionManager.getDs().deleteTasks(allIds, false), "All tasks removed");
                });
            }

            @Override
            public void onChangeDefaultFolder() {
                AppDialogs.showFolderPickerDialog(
                        MainActivity.this,
                        sessionManager,
                        prefsStore,
                        bgExecutor,
                        "Set Default Folder",
                        defaultDestination,
                        selectedPath -> {
                            final String clean = FormatUtils.stripLeadingSlash(selectedPath);
                            runBulkTaskAction(() -> {
                                sessionManager.getDs().setDefaultDestination(clean);
                                defaultDestination = clean;
                            }, "Default download folder changed to: " + selectedPath);
                        }
                );
            }

            @Override
            public void onLogout() {
                AppDialogs.showConfirmDialog(MainActivity.this, "Logout", "Are you sure you want to disconnect?", "Logout", () -> {
                    bgExecutor.execute(() -> {
                        sessionManager.disconnect(false);
                        uiHandler.post(() -> {
                            needsOtp = false;
                            loginDraftUrl = null;
                            loginDraftAccount = null;
                            loginDraftPassword = null;
                            loginDraftOtp = "";
                            navigateTo(Screen.LOGIN);
                        });
                    });
                });
            }
        });
    }

    private void runBulkTaskAction(RunnableWithException action, String toastMessage) {
        bgExecutor.execute(() -> {
            try {
                sessionManager.execute(() -> {
                    action.run();
                    return null;
                });
                uiHandler.post(() -> {
                    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
                    refreshTasksAsync();
                });
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}

