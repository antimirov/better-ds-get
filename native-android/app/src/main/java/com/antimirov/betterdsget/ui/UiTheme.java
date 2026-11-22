package com.antimirov.betterdsget.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.antimirov.betterdsget.R;

/**
 * Pure Android SDK styling helpers matching the Better DS Get dark theme (#121212, #1E1E1E, #00A1E4).
 */
public final class UiTheme {
    public static final int COLOR_BG_MAIN = Color.parseColor("#121212");
    public static final int COLOR_BG_CARD = Color.parseColor("#1E1E1E");
    public static final int COLOR_BG_MODAL = Color.parseColor("#2C2C2C");
    public static final int COLOR_BG_INPUT = Color.parseColor("#2A2A2A");
    public static final int COLOR_BG_SEARCH_STATUS = Color.parseColor("#1E2A38");
    public static final int COLOR_SYNOLOGY_BLUE = Color.parseColor("#00A1E4");
    public static final int COLOR_GREEN = Color.parseColor("#4CAF50");
    public static final int COLOR_AMBER = Color.parseColor("#FF9800");
    public static final int COLOR_YELLOW = Color.parseColor("#FFC107");
    public static final int COLOR_RED = Color.parseColor("#FF6B6B");
    public static final int COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF");
    public static final int COLOR_TEXT_SECONDARY = Color.parseColor("#E0E0E0");
    public static final int COLOR_TEXT_MUTED = Color.parseColor("#888888");
    public static final int COLOR_DIVIDER = Color.parseColor("#333333");

    private UiTheme() {}

    public static int dp(Context ctx, float dpVal) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dpVal,
                ctx.getResources().getDisplayMetrics()
        ));
    }

    public static GradientDrawable roundedRect(int fillColor, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable roundedRectWithBorder(int fillColor, int strokeColor, int strokeWidthPx, int radiusPx) {
        GradientDrawable d = roundedRect(fillColor, radiusPx);
        d.setStroke(strokeWidthPx, strokeColor);
        return d;
    }

    public static GradientDrawable topRoundedRect(int fillColor, int topRadiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadii(new float[]{
                topRadiusPx, topRadiusPx,
                topRadiusPx, topRadiusPx,
                0f, 0f, 0f, 0f
        });
        return d;
    }

    public static void styleSwitch(Switch sw) {
        if (sw == null) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumbColors = new int[]{COLOR_SYNOLOGY_BLUE, Color.parseColor("#F4F3F4")};
        int[] trackColors = new int[]{Color.parseColor("#6600A1E4"), Color.parseColor("#555555")};
        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
    }

    public static int statusColor(String status) {
        if (status == null) return COLOR_AMBER;
        switch (status.toLowerCase()) {
            case "finished":
            case "seeding":
                return COLOR_GREEN;
            case "downloading":
                return COLOR_SYNOLOGY_BLUE;
            case "error":
                return COLOR_RED;
            default:
                if (status.toLowerCase().startsWith("error")) return COLOR_RED;
                return COLOR_AMBER;
        }
    }

    public static TextView makeText(Context ctx, String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    public static EditText makeInput(Context ctx, String hint, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#666666"));
        et.setTextColor(COLOR_TEXT_PRIMARY);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setCursorVisible(true);
        et.setHighlightColor(Color.parseColor("#4000A1E4"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            et.setTextCursorDrawable(R.drawable.cursor_cyan);
        } else {
            try {
                java.lang.reflect.Field f = TextView.class.getDeclaredField("mCursorDrawableRes");
                f.setAccessible(true);
                f.set(et, R.drawable.cursor_cyan);
            } catch (Throwable ignored) {}
        }
        int padH = dp(ctx, 12);
        int padV = dp(ctx, 11);
        et.setPadding(padH, padV, padH, padV);
        et.setBackground(roundedRectWithBorder(COLOR_BG_CARD, Color.parseColor("#444444"), dp(ctx, 1), dp(ctx, 8)));
        return et;
    }

    public static void hideKeyboard(Activity activity, View view) {
        if (activity == null || activity.isFinishing()) return;
        View target = view != null ? view : activity.getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            android.os.IBinder token = target != null ? target.getWindowToken() : activity.getWindow().getDecorView().getWindowToken();
            if (token != null) {
                imm.hideSoftInputFromWindow(token, 0);
            }
        }
        if (target != null) {
            target.clearFocus();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsetsController insetsController = activity.getWindow().getInsetsController();
                if (insetsController != null) {
                    insetsController.hide(WindowInsets.Type.ime());
                }
            } catch (Throwable ignored) {}
        }
    }

    public static TextView makeButton(Context ctx, String label, int bgColor, int textColor, View.OnClickListener onClick) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTextColor(textColor);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        int padV = dp(ctx, 13);
        int padH = dp(ctx, 16);
        btn.setPadding(padH, padV, padH, padV);
        btn.setBackground(roundedRect(bgColor, dp(ctx, 8)));
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    public static android.graphics.drawable.Drawable makeSelectableBackground(Context ctx) {
        try {
            TypedValue outValue = new TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            return ctx.getDrawable(outValue.resourceId);
        } catch (Exception e) {
            return null;
        }
    }

    public static View makeDivider(Context ctx, int verticalMarginDp) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(ctx, 1))
        );
        int m = dp(ctx, verticalMarginDp);
        lp.setMargins(0, m, 0, m);
        v.setLayoutParams(lp);
        v.setBackgroundColor(COLOR_DIVIDER);
        return v;
    }

    public static LinearLayout makeInfoRow(Context ctx, String label, String value) {
        return makeInfoRow(ctx, label, value, 3);
    }

    public static LinearLayout makeInfoRow(Context ctx, String label, String value, int maxLines) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));

        TextView lbl = makeText(ctx, label, 13, COLOR_TEXT_MUTED, false);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lbl.setLayoutParams(lp1);

        TextView val = makeText(ctx, value != null ? value : "", 13, COLOR_TEXT_SECONDARY, false);
        val.setGravity(Gravity.END);
        if (maxLines == 1) {
            val.setSingleLine(true);
        } else {
            val.setMaxLines(maxLines);
        }
        val.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        val.setLayoutParams(lp2);

        row.addView(lbl);
        row.addView(val);
        return row;
    }
}

