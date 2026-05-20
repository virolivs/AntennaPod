package de.test.antennapod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class VisualMutationLayer extends View {
    private static final String TAG = "VisualMutationLayer";

    public enum Operator {
        IPR,
        ITR,
        MDL,
        ECR,
        ETR,
        APD,
        BWD,
        TWD,
        BWS,
        FON,
        ORL
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long startedAt = SystemClock.uptimeMillis();
    private final Operator operator;
    private boolean mutationLogged;

    public VisualMutationLayer(Context context, Operator operator) {
        super(context);
        this.operator = operator;
        setEnabled(false);
        setWillNotDraw(false);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - startedAt;
        float density = getResources().getDisplayMetrics().density;
        float unit = 8f * density;
        List<RectF> buttons = visibleBounds(Button.class);
        List<RectF> imageButtons = visibleBounds(ImageButton.class);
        List<RectF> editTexts = visibleBounds(EditText.class);
        List<RectF> texts = visibleBounds(TextView.class);
        List<RectF> images = visibleBounds(ImageView.class);
        List<RectF> actions = mergeBounds(buttons, imageButtons);
        int seed = visualSeed(width, height, actions, editTexts, texts, images);
        if (!shouldDrawMutation(elapsed, seed, actions, editTexts, texts, images)) {
            postInvalidateDelayed(120);
            return;
        }
        if (!mutationLogged) {
            mutationLogged = true;
            Log.i(TAG, "visual_mutation_drawn operator=" + operator.name());
        }

        switch (operator) {
            case IPR:
                drawIntentPayloadReplacement(canvas, width, height, unit, density, elapsed, texts, editTexts, seed);
                break;
            case ITR:
                drawIntentTargetReplacement(canvas, width, height, unit, density, elapsed, actions, texts, seed);
                break;
            case MDL:
                drawLifecycleMethodDeletion(canvas, width, height, unit, density, texts, seed);
                break;
            case ECR:
                drawOnClickEventReplacement(canvas, width, height, unit, density, actions, elapsed, seed);
                break;
            case ETR:
                drawOnTouchEventReplacement(canvas, width, height, unit, density, elapsed, texts, seed);
                break;
            case APD:
                drawActivityPermissionDeletion(canvas, width, height, unit, density, actions, texts, seed);
                break;
            case BWD:
                drawButtonWidgetDeletion(canvas, width, height, unit, density, actions, seed);
                break;
            case TWD:
                drawTextWidgetDeletion(canvas, width, height, unit, density, editTexts, texts, seed);
                break;
            case BWS:
                drawButtonWidgetSwitch(canvas, width, height, unit, density, actions, seed);
                break;
            case FON:
                drawFailOnNull(canvas, width, height, unit, density, texts, seed);
                break;
            case ORL:
                drawOrientationLock(canvas, width, height, unit, density);
                break;
            default:
                break;
        }

        postInvalidateDelayed(120);
    }

    private boolean shouldDrawMutation(long elapsed, int seed, List<RectF> actions, List<RectF> editTexts,
                                       List<RectF> texts, List<RectF> images) {
        if (!hasRequiredTarget(actions, editTexts, texts, images)) {
            return false;
        }
        int gate = Math.abs((seed / 17 + operator.ordinal()) % 5);
        if (gate == 0 && operator != Operator.ORL) {
            return false;
        }
        int period = 2100 + operator.ordinal() * 170;
        int active = 1150 + operator.ordinal() % 3 * 220;
        return (elapsed + Math.abs(seed % period)) % period < active;
    }

    private boolean hasRequiredTarget(List<RectF> actions, List<RectF> editTexts, List<RectF> texts,
                                      List<RectF> images) {
        switch (operator) {
            case ITR:
                return !actions.isEmpty();
            case ECR:
            case BWS:
                return actions.size() >= 2;
            case BWD:
                return !actions.isEmpty();
            case TWD:
                return !editTexts.isEmpty() || !texts.isEmpty();
            case ETR:
            case MDL:
            case FON:
                return !texts.isEmpty() || !actions.isEmpty() || !images.isEmpty();
            default:
                return true;
        }
    }

    private int visualSeed(int width, int height, List<RectF> actions, List<RectF> editTexts, List<RectF> texts,
                           List<RectF> images) {
        int seed = operator.ordinal() * 1009 + width * 31 + height;
        seed = addBoundsSeed(seed, actions);
        seed = addBoundsSeed(seed, editTexts);
        seed = addBoundsSeed(seed, texts);
        return addBoundsSeed(seed, images);
    }

    private int addBoundsSeed(int seed, List<RectF> bounds) {
        for (RectF bound : bounds) {
            seed = seed * 31 + Math.round(bound.left + bound.top + bound.right + bound.bottom);
        }
        return seed;
    }

    private void drawIntentPayloadReplacement(Canvas canvas, int width, int height, float unit, float density,
                                              long elapsed, List<RectF> texts, List<RectF> editTexts, int seed) {
        List<RectF> targets = editTexts.isEmpty() ? texts : editTexts;
        RectF target = getBoundsOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF box = expand(target, unit, width, height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surfaceColor());
        canvas.drawRect(box, paint);
        paint.setColor(Color.argb(210, 117, 117, 117));
        if (elapsed % 1200 < 600) {
            paint.setTextSize(18f * density);
            canvas.drawText("0", box.left + unit, Math.min(box.bottom - unit, box.top + 3.4f * unit), paint);
        } else {
            canvas.drawRoundRect(new RectF(box.left + unit, box.centerY() - unit / 4f,
                    Math.min(box.right - unit, box.left + 9f * unit), box.centerY() + unit / 4f),
                    unit / 4f, unit / 4f, paint);
        }
        paint.setColor(Color.argb(210, 176, 0, 32));
        canvas.drawRect(box.left, box.bottom - unit / 3f, box.right, box.bottom, paint);
    }

    private void drawIntentTargetReplacement(Canvas canvas, int width, int height, float unit, float density,
                                             long elapsed, List<RectF> actions, List<RectF> texts, int seed) {
        RectF start = getBoundsOrFallback(texts, targetIndex(texts, seed), width, height, unit);
        RectF end = getBoundsOrFallback(actions, targetIndex(actions, seed + 1), width, height, unit);
        float startX = start.centerX();
        float startY = start.centerY();
        float endX = end.centerX();
        float endY = end.centerY() + (elapsed % 1200 < 600 ? unit : -unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(80, 0, 150, 136));
        canvas.drawCircle(endX, endY, 4.5f * unit + elapsed % 900 / 900f * 2.5f * unit, paint);
        paint.setColor(Color.argb(105, 176, 0, 32));
        canvas.drawRoundRect(expand(end, unit, width, height), unit / 2f, unit / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit / 4f);
        paint.setColor(Color.argb(120, 0, 150, 136));
        canvas.drawLine(startX, startY, endX, endY, paint);
    }

    private void drawLifecycleMethodDeletion(Canvas canvas, int width, int height, float unit, float density,
                                             List<RectF> texts, int seed) {
        RectF target = getBoundsOrFallback(texts, targetIndex(texts, seed), width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(178, 250, 250, 250));
        canvas.drawRoundRect(expand(target, 1.5f * unit, width, height), unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(120, 158, 158, 158));
        float left = Math.max(unit, target.left);
        float lineTop = Math.max(unit, target.top + unit);
        canvas.drawRoundRect(new RectF(left, lineTop, Math.min(width - unit, left + 22f * unit),
                lineTop + unit / 2f), unit / 4f, unit / 4f, paint);
        canvas.drawRoundRect(new RectF(left, lineTop + 1.6f * unit, Math.min(width - unit, left + 14f * unit),
                lineTop + 2.1f * unit), unit / 4f, unit / 4f, paint);
    }

    private void drawOnClickEventReplacement(Canvas canvas, int width, int height, float unit, float density,
                                             List<RectF> actions, long elapsed, int seed) {
        int index = targetIndex(actions, seed);
        RectF first = getBoundsOrFallback(actions, index, width, height, unit);
        RectF second = getBoundsOrFallback(actions, index + 1, width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(68, 0, 150, 136));
        canvas.drawRoundRect(first, unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(115, 0, 150, 136));
        float pulse = elapsed % 900 < 450 ? 0f : unit;
        canvas.drawCircle(second.centerX(), second.centerY(), Math.max(second.width(), second.height()) / 2f + pulse,
                paint);
        paint.setColor(Color.argb(115, 176, 0, 32));
        canvas.drawCircle(first.centerX(), first.centerY(), unit, paint);
    }

    private void drawOnTouchEventReplacement(Canvas canvas, int width, int height, float unit, float density,
                                             long elapsed, List<RectF> texts, int seed) {
        RectF target = getBoundsOrFallback(texts, targetIndex(texts, seed), width, height, unit);
        float cx = target.centerX();
        float cy = target.centerY();
        float radius = 2.4f * unit + elapsed % 1000 / 1000f * 3.2f * unit;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit / 3f);
        paint.setColor(Color.argb(150, 0, 150, 136));
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawCircle(width * 0.28f, height * 0.62f, 2.6f * unit, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(82, 0, 150, 136));
        canvas.drawCircle(width * 0.28f, height * 0.62f, 2.2f * unit, paint);
    }

    private void drawActivityPermissionDeletion(Canvas canvas, int width, int height, float unit, float density,
                                                List<RectF> actions, List<RectF> texts, int seed) {
        List<RectF> targets = actions.isEmpty() ? texts : actions;
        RectF target = getBoundsOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        float left = 2f * unit;
        float top = height - 8f * unit;
        float right = width - 2f * unit;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(232, 50, 50, 50));
        canvas.drawRoundRect(new RectF(left, top, right, top + 6f * unit), unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(240, 255, 255, 255));
        canvas.drawCircle(left + 3f * unit, top + 3f * unit, unit, paint);
        paint.setColor(Color.argb(232, 50, 50, 50));
        canvas.drawRect(left + 2.6f * unit, top + 2.8f * unit, left + 3.4f * unit, top + 4f * unit, paint);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawRoundRect(new RectF(left + 5f * unit, top + 2.1f * unit, right - 4f * unit, top + 2.7f * unit),
                unit / 4f, unit / 4f, paint);
        canvas.drawRoundRect(new RectF(left + 5f * unit, top + 3.5f * unit, right - 12f * unit, top + 4.1f * unit),
                unit / 4f, unit / 4f, paint);
    }

    private void drawButtonWidgetDeletion(Canvas canvas, int width, int height, float unit, float density,
                                          List<RectF> actions, int seed) {
        RectF target = getBoundsOrFallback(actions, targetIndex(actions, seed), width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surfaceColor());
        canvas.drawRoundRect(expand(target, unit / 3f, width, height), unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(35, 0, 0, 0));
        canvas.drawRoundRect(target, unit / 2f, unit / 2f, paint);
    }

    private void drawTextWidgetDeletion(Canvas canvas, int width, int height, float unit, float density,
                                        List<RectF> editTexts, List<RectF> texts, int seed) {
        RectF target = editTexts.isEmpty() ? getBoundsOrFallback(texts, targetIndex(texts, seed), width, height, unit)
                : getBoundsOrFallback(editTexts, targetIndex(editTexts, seed), width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surfaceColor());
        canvas.drawRect(expand(target, unit / 2f, width, height), paint);
        paint.setColor(Color.argb(90, 189, 189, 189));
        canvas.drawRoundRect(new RectF(target.left, target.centerY() - unit / 4f,
                Math.min(target.right, target.left + target.width() * 0.62f), target.centerY() + unit / 4f),
                unit / 4f, unit / 4f, paint);
    }

    private void drawButtonWidgetSwitch(Canvas canvas, int width, int height, float unit, float density,
                                        List<RectF> actions, int seed) {
        int index = targetIndex(actions, seed);
        RectF first = getBoundsOrFallback(actions, index, width, height, unit);
        RectF second = getBoundsOrFallback(actions, index + 1, width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surfaceColor());
        canvas.drawRoundRect(first, unit / 2f, unit / 2f, paint);
        canvas.drawRoundRect(second, unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(95, 0, 150, 136));
        canvas.drawRoundRect(new RectF(second), unit / 2f, unit / 2f, paint);
        paint.setColor(Color.argb(95, 176, 0, 32));
        canvas.drawRoundRect(new RectF(first), unit / 2f, unit / 2f, paint);
    }

    private void drawFailOnNull(Canvas canvas, int width, int height, float unit, float density, List<RectF> texts,
                                int seed) {
        RectF target = getBoundsOrFallback(texts, targetIndex(texts, seed), width, height, unit);
        float left = Math.max(unit, Math.min(target.left, width - 31f * unit));
        float top = Math.max(5f * unit, Math.min(target.bottom + unit, height - 8f * unit));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(220, 255, 255, 255));
        canvas.drawRoundRect(new RectF(left, top, width - 2f * unit, top + 5.5f * unit), unit, unit, paint);
        paint.setColor(Color.argb(210, 176, 0, 32));
        canvas.drawCircle(left + 2.4f * unit, top + 2.7f * unit, unit, paint);
        paint.setColor(Color.argb(95, 117, 117, 117));
        canvas.drawRoundRect(new RectF(left + 4.4f * unit, top + 1.7f * unit, width - 8f * unit, top + 2.3f * unit),
                unit / 4f, unit / 4f, paint);
        canvas.drawRoundRect(new RectF(left + 4.4f * unit, top + 3.2f * unit, width - 15f * unit, top + 3.8f * unit),
                unit / 4f, unit / 4f, paint);
    }

    private void drawOrientationLock(Canvas canvas, int width, int height, float unit, float density) {
        float left = width * 0.08f;
        float top = height * 0.24f;
        float right = width * 0.92f;
        float bottom = height * 0.76f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(190, 0, 0, 0));
        canvas.drawRect(0f, 0f, width, top, paint);
        canvas.drawRect(0f, bottom, width, height, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit / 3f);
        paint.setColor(Color.argb(160, 255, 255, 255));
        canvas.drawRoundRect(new RectF(left, top, right, bottom), unit, unit, paint);
    }

    private RectF getBoundsOrFallback(List<RectF> bounds, int index, int width, int height, float unit) {
        if (!bounds.isEmpty()) {
            return bounds.get(Math.abs(index) % bounds.size());
        }
        float left = index == 0 ? 3f * unit : width - 19f * unit;
        float top = index == 0 ? height * 0.34f : height * 0.55f;
        return new RectF(left, top, left + 16f * unit, top + 5f * unit);
    }

    private int targetIndex(List<RectF> bounds, int seed) {
        if (bounds.isEmpty()) {
            return Math.abs(seed % 2);
        }
        return Math.abs(seed) % bounds.size();
    }

    private RectF expand(RectF rect, float amount, int width, int height) {
        return new RectF(Math.max(0f, rect.left - amount), Math.max(0f, rect.top - amount),
                Math.min(width, rect.right + amount), Math.min(height, rect.bottom + amount));
    }

    private List<RectF> mergeBounds(List<RectF> first, List<RectF> second) {
        List<RectF> merged = new ArrayList<>(first);
        for (RectF bound : second) {
            if (merged.size() >= 6) {
                break;
            }
            merged.add(bound);
        }
        return merged;
    }

    private int surfaceColor() {
        return Color.argb(238, 250, 250, 250);
    }

    private List<RectF> visibleBounds(Class<? extends View> viewClass) {
        List<RectF> bounds = new ArrayList<>();
        collectVisibleBounds(getRootView(), viewClass, bounds);
        return bounds;
    }

    private void collectVisibleBounds(View view, Class<? extends View> viewClass, List<RectF> bounds) {
        if (view == null || view == this || bounds.size() >= 4 || view.getVisibility() != VISIBLE
                || view.getWidth() == 0 || view.getHeight() == 0) {
            return;
        }
        if (viewClass.isInstance(view)) {
            Rect rect = new Rect();
            if (view.getGlobalVisibleRect(rect)) {
                int[] layerLocation = new int[2];
                getLocationOnScreen(layerLocation);
                rect.offset(-layerLocation[0], -layerLocation[1]);
                bounds.add(new RectF(rect));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && bounds.size() < 4; i++) {
                collectVisibleBounds(group.getChildAt(i), viewClass, bounds);
            }
        }
    }
}
