package tv.clean.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** One translucent "glass" highlight that springs from target to target. */
public class HighlightView extends View {
    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float curR, fromR, toR;
    private boolean shown;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        fill.setColor(0x2EFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        stroke.setColor(0x99FFFFFF);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(6f * d);
        halo.setColor(0x14FFFFFF);
        anim.setDuration(320);
        anim.setInterpolator(new OvershootInterpolator(1.1f));
        anim.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            cur.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            curR = lerp(fromR, toR, t);
            invalidate();
        });
    }

    public void moveTo(RectF target, float radius, boolean animate) {
        anim.cancel();
        to.set(target);
        toR = radius;
        if (!animate || !shown) {
            cur.set(target);
            curR = radius;
            shown = true;
            invalidate();
            return;
        }
        from.set(cur);
        fromR = curR;
        anim.start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!shown) return;
        c.drawRoundRect(cur, curR, curR, halo);
        c.drawRoundRect(cur, curR, curR, fill);
        c.drawRoundRect(cur, curR, curR, stroke);
    }
}
