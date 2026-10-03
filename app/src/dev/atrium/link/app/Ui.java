package dev.atrium.link.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.atrium.link.R;

// The few pieces the screen is made of, drawn here rather than taken from a
// widget library: cards, text, pill buttons, a status dot.
final class Ui {
    final Context ctx;
    final int bg, card, fg, dim, accent, onAccent, ok, bad, fill;

    Ui(Context ctx) {
        this.ctx = ctx;
        bg = color(R.color.bg);
        card = color(R.color.card);
        fg = color(R.color.fg);
        dim = color(R.color.dim);
        accent = color(R.color.accent);
        onAccent = color(R.color.on_accent);
        ok = color(R.color.ok);
        bad = color(R.color.bad);
        fill = color(R.color.fill);
    }

    private int color(int id) {
        return ctx.getColor(id);
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(round(card, dp(16)));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold)
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    TextView title(CharSequence s) {
        return text(s, 16, fg, true);
    }

    TextView note(CharSequence s) {
        return text(s, 13, dim, false);
    }

    // A filled pill for the action that moves things on, a plain one for the rest.
    TextView button(CharSequence label, boolean primary, View.OnClickListener click) {
        TextView b = text(label, 14, primary ? onAccent : accent, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setMinWidth(dp(64));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x220a84ff),
                round(primary ? accent : fill, dp(20)), null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        return b;
    }

    View dot(int color) {
        View v = new View(ctx);
        v.setBackground(round(color, dp(5)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
        lp.rightMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    LinearLayout.LayoutParams gapLeft(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(dp);
        return lp;
    }

    LinearLayout.LayoutParams gapTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dp);
        return lp;
    }
}
