package de.test.antennapod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VisualMutationLayer extends View {
    private static final String TAG = "VisualMutationLayer";
    private static final int MAX_TARGETS = 8;
    private static boolean maskFileReset;

    public enum Operator {
        TEXT_TRUNCATION,
        TEXT_OVERLAP,
        COMPONENT_OCCLUSION,
        COMPONENT_SWAP,
        FONT_SCALE_CHANGE,
        ICON_DELETION,
        COMPONENT_RESIZE,
        LOW_CONTRAST_TEXT,
        PADDING_SHIFT,
        TEXT_COLOR_CHANGE
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long startedAt = SystemClock.uptimeMillis();
    private final Operator operator;
    private boolean mutationVisible;
    private int maskId;
    private boolean capturingCleanFrame;
    private String currentTargetSignature = "";
    private long targetStableSince;

    private static final class Target {
        final RectF bounds;
        final int backgroundColor;
        final int foregroundColor;
        final String text;
        final String kind;
        final float textSize;
        final View view;

        Target(RectF bounds, int backgroundColor, int foregroundColor, String text, String kind) {
            this(bounds, backgroundColor, foregroundColor, text, kind, 0f, null);
        }

        Target(RectF bounds, int backgroundColor, int foregroundColor, String text, String kind, float textSize) {
            this(bounds, backgroundColor, foregroundColor, text, kind, textSize, null);
        }

        Target(RectF bounds, int backgroundColor, int foregroundColor, String text, String kind, float textSize,
               View view) {
            this.bounds = bounds;
            this.backgroundColor = backgroundColor;
            this.foregroundColor = foregroundColor;
            this.text = text;
            this.kind = kind;
            this.textSize = textSize;
            this.view = view;
        }
    }

    private static final class TargetPair {
        final Target first;
        final Target second;

        TargetPair(Target first, Target second) {
            this.first = first;
            this.second = second;
        }
    }

    public VisualMutationLayer(Context context, Operator operator) {
        super(context);
        this.operator = operator;
        resetMaskFile(context);
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
        if (capturingCleanFrame) {
            return;
        }
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - startedAt;
        float density = getResources().getDisplayMetrics().density;
        float unit = 8f * density;
        List<Target> buttons = visibleTargets(Button.class, "button");
        List<Target> imageButtons = visibleTargets(ImageButton.class, "image_button");
        List<Target> imageActions = visibleTargets(ImageView.class, "image_action");
        List<Target> editTexts = visibleTargets(EditText.class, "edit_text");
        List<Target> texts = visibleTargets(TextView.class, "text");
        List<Target> images = visibleTargets(ImageView.class, "image");
        List<Target> actions = mergeTargets(mergeTargets(buttons, imageButtons), imageActions);
        int seed = visualSeed(width, height, actions, editTexts, texts, images);
        long stableForMs = updateTargetStability(width, height, elapsed, actions, editTexts, texts,
                imageButtons, images);
        boolean shouldDraw = shouldDrawMutation(width, height, unit, elapsed, stableForMs, seed, actions,
                editTexts, texts, imageButtons, images);
        if (!shouldDraw) {
            if (mutationVisible) {
                mutationVisible = false;
                Log.i(TAG, "visual_mutation_hidden operator=" + operator.name() + " wall_ms="
                        + System.currentTimeMillis());
            }
            postInvalidateDelayed(120);
            return;
        }

        boolean becameVisible = !mutationVisible;
        if (becameVisible) {
            mutationVisible = true;
        }
        maskId++;
        RectF bounds = mutationBounds(width, height, unit, actions, editTexts, texts, imageButtons, images, seed);
        Bitmap cleanFrame = captureCleanFrame(width, height);
        writeMutationMask(maskId, width, height, unit, density, elapsed, cleanFrame, actions, editTexts, texts,
                imageButtons, images, seed, bounds);
        if (becameVisible) {
            Log.i(TAG, "visual_mutation_visible operator=" + operator.name() + " mask_id=" + maskId
                    + " bounds=" + formatBounds(bounds) + " screen=" + width + "x" + height
                    + " wall_ms=" + System.currentTimeMillis());
        }
        Log.i(TAG, "visual_mutation_frame operator=" + operator.name() + " mask_id=" + maskId
                + " bounds=" + formatBounds(bounds) + " screen=" + width + "x" + height
                + " wall_ms=" + System.currentTimeMillis());

        drawMutation(canvas, width, height, unit, density, elapsed, cleanFrame, actions, editTexts, texts, imageButtons,
                images, seed);
        if (cleanFrame != null) {
            cleanFrame.recycle();
        }
        postInvalidateDelayed(120);
    }

    private void drawMutation(Canvas canvas, int width, int height, float unit, float density, long elapsed,
                              Bitmap cleanFrame, List<Target> actions, List<Target> editTexts, List<Target> texts,
                              List<Target> imageButtons, List<Target> images, int seed) {
        switch (operator) {
            case TEXT_TRUNCATION:
                drawTextTruncation(canvas, cleanFrame, width, height, unit, density, editTexts, texts, seed);
                break;
            case TEXT_OVERLAP:
                drawTextOverlap(canvas, width, height, unit, density, texts, seed);
                break;
            case COMPONENT_OCCLUSION:
                drawComponentOcclusion(canvas, cleanFrame, width, height, unit, density,
                        componentOcclusionTargets(width, height, unit, actions, texts), seed);
                break;
            case COMPONENT_SWAP:
                drawComponentSwap(canvas, cleanFrame, width, height, unit, density, actions, texts,
                        imageButtons, images, seed);
                break;
            case FONT_SCALE_CHANGE:
                drawFontScaleChange(canvas, cleanFrame, width, height, unit, density, editTexts, texts, seed);
                break;
            case ICON_DELETION:
                drawIconDeletion(canvas, cleanFrame, width, height, unit, density, actions, seed);
                break;
            case COMPONENT_RESIZE:
                drawComponentResize(canvas, cleanFrame, width, height, unit, density, actions, imageButtons, images, seed);
                break;
            case LOW_CONTRAST_TEXT:
                drawLowContrastText(canvas, cleanFrame, width, height, unit, density, actions, editTexts, texts, seed);
                break;
            case PADDING_SHIFT:
                drawPaddingShift(canvas, cleanFrame, width, height, unit, density, actions, texts, seed);
                break;
            case TEXT_COLOR_CHANGE:
                drawTextColorChange(canvas, cleanFrame, width, height, unit, density, actions, editTexts, texts, seed);
                break;
            default:
                break;
        }
    }

    private boolean shouldDrawMutation(int width, int height, float unit, long elapsed, long stableForMs, int seed,
                                       List<Target> actions, List<Target> editTexts, List<Target> texts,
                                       List<Target> imageButtons, List<Target> images) {
        if (!hasRequiredTarget(width, height, unit, actions, editTexts, texts, imageButtons, images)) {
            return false;
        }
        if (operator == Operator.COMPONENT_RESIZE && stableForMs < 150L) {
            return false;
        }
        if (operator == Operator.FONT_SCALE_CHANGE && stableForMs < 250L) {
            return false;
        }
        if (operator == Operator.PADDING_SHIFT && stableForMs < 150L) {
            return false;
        }
        if (operator == Operator.COMPONENT_OCCLUSION && stableForMs < 180L) {
            return false;
        }
        if (operator == Operator.COMPONENT_SWAP && stableForMs < 180L) {
            return false;
        }
        int period = 2100 + operator.ordinal() * 170;
        int active = 1150 + operator.ordinal() % 3 * 220;
        return (elapsed + Math.abs(seed % period)) % period < active;
    }

    private boolean hasRequiredTarget(int width, int height, float unit, List<Target> actions, List<Target> editTexts,
                                      List<Target> texts,
                                      List<Target> imageButtons, List<Target> images) {
        switch (operator) {
            case COMPONENT_RESIZE:
                return !componentResizeTargets(width, height, unit, actions, imageButtons, images).isEmpty();
            case ICON_DELETION:
                return !iconDeletionTargets(actions).isEmpty();
            case PADDING_SHIFT:
                return !paddingShiftTargets(width, height, unit, actions, texts, 0).isEmpty();
            case TEXT_TRUNCATION:
                return !editTexts.isEmpty() || !texts.isEmpty();
            case LOW_CONTRAST_TEXT:
                return !editTexts.isEmpty() || !texts.isEmpty() || !actions.isEmpty();
            case TEXT_OVERLAP:
                return !texts.isEmpty();
            case FONT_SCALE_CHANGE:
                return !editTexts.isEmpty() || !texts.isEmpty();
            case COMPONENT_OCCLUSION:
                return !componentOcclusionTargets(width, height, unit, actions, texts).isEmpty();
            case COMPONENT_SWAP:
                return componentSwapPair(width, height, unit, actions, texts, imageButtons, images, 0) != null;
            case TEXT_COLOR_CHANGE:
                return !lowContrastTargets(editTexts, texts, actions).isEmpty();
            default:
                return true;
        }
    }

    private RectF mutationBounds(int width, int height, float unit, List<Target> actions,
                                 List<Target> editTexts, List<Target> texts, List<Target> imageButtons,
                                 List<Target> images, int seed) {
        switch (operator) {
            case TEXT_TRUNCATION:
                List<Target> textTargets = editTexts.isEmpty() ? texts : editTexts;
                return expand(targetOrFallback(textTargets, targetIndex(textTargets, seed), width, height, unit).bounds,
                        unit / 3f, width, height);
            case LOW_CONTRAST_TEXT:
                List<Target> contrastTargets = lowContrastTargets(editTexts, texts, actions);
                return expand(targetOrFallback(contrastTargets, targetIndex(contrastTargets, seed),
                        width, height, unit).bounds, unit / 3f, width, height);
            case TEXT_OVERLAP:
                return expand(targetOrFallback(texts, targetIndex(texts, seed),
                        width, height, unit).bounds, unit / 2f, width, height);
            case FONT_SCALE_CHANGE:
                List<Target> fontTargets = textTargets(editTexts, texts);
                return expand(targetOrFallback(fontTargets, targetIndex(fontTargets, seed),
                        width, height, unit).bounds, unit, width, height);
            case COMPONENT_OCCLUSION:
                return componentOccluderBounds(width, height, unit,
                        componentOcclusionTargets(width, height, unit, actions, texts), seed);
            case COMPONENT_SWAP:
                TargetPair swapPair = componentSwapPair(width, height, unit, actions, texts, imageButtons, images, seed);
                if (swapPair != null) {
                    return expand(union(swapPair.first.bounds, swapPair.second.bounds), unit / 4f, width, height);
                }
                return new RectF(0f, 0f, width, height);
            case ICON_DELETION:
                List<Target> deletionTargets = iconDeletionTargets(actions);
                return targetOrFallback(deletionTargets, targetIndex(deletionTargets, seed), width, height, unit).bounds;
            case COMPONENT_RESIZE:
                List<Target> resizeTargets = componentResizeTargets(width, height, unit, actions, imageButtons, images);
                Target resizeTarget = targetOrFallback(resizeTargets, targetIndex(resizeTargets, seed),
                        width, height, unit);
                return union(resizeTarget.bounds, componentResizeDestination(resizeTarget.bounds, unit, seed, width, height));
            case PADDING_SHIFT:
                List<Target> paddingTargets = paddingShiftTargets(width, height, unit, actions, texts, seed);
                Target paddingTarget = targetOrFallback(paddingTargets, targetIndex(paddingTargets, seed),
                        width, height, unit);
                return union(paddingTarget.bounds, paddingShiftDestination(paddingTarget.bounds, unit, seed, width, height));
            case TEXT_COLOR_CHANGE:
                List<Target> textColorTargets = lowContrastTargets(editTexts, texts, actions);
                return expand(targetOrFallback(textColorTargets, targetIndex(textColorTargets, seed), width, height, unit).bounds,
                        unit / 4f, width, height);
            default:
                return new RectF(0f, 0f, width, height);
        }
    }

    private int visualSeed(int width, int height, List<Target> actions, List<Target> editTexts, List<Target> texts,
                           List<Target> images) {
        int seed = operator.ordinal() * 1009 + width * 31 + height;
        seed = addBoundsSeed(seed, actions);
        seed = addBoundsSeed(seed, editTexts);
        seed = addBoundsSeed(seed, texts);
        return addBoundsSeed(seed, images);
    }

    private int addBoundsSeed(int seed, List<Target> targets) {
        for (Target target : targets) {
            RectF bound = target.bounds;
            seed = seed * 31 + Math.round(bound.left + bound.top + bound.right + bound.bottom);
        }
        return seed;
    }

    private long updateTargetStability(int width, int height, long elapsed, List<Target> actions,
                                       List<Target> editTexts, List<Target> texts,
                                       List<Target> imageButtons, List<Target> images) {
        String signature = targetSignature(width, height, actions, editTexts, texts, imageButtons, images);
        if (!signature.equals(currentTargetSignature)) {
            currentTargetSignature = signature;
            targetStableSince = elapsed;
            return 0L;
        }
        return elapsed - targetStableSince;
    }

    private String targetSignature(int width, int height, List<Target> actions, List<Target> editTexts,
                                   List<Target> texts, List<Target> imageButtons, List<Target> images) {
        StringBuilder signature = new StringBuilder();
        signature.append(width).append('x').append(height);
        appendTargetSignature(signature, "a", actions);
        appendTargetSignature(signature, "e", editTexts);
        appendTargetSignature(signature, "t", texts);
        appendTargetSignature(signature, "ib", imageButtons);
        appendTargetSignature(signature, "i", images);
        return signature.toString();
    }

    private void appendTargetSignature(StringBuilder signature, String prefix, List<Target> targets) {
        signature.append('|').append(prefix).append(':').append(targets.size());
        for (Target target : targets) {
            RectF bounds = target.bounds;
            signature.append('@')
                    .append(Math.round(bounds.left / 8f)).append(',')
                    .append(Math.round(bounds.top / 8f)).append(',')
                    .append(Math.round(bounds.right / 8f)).append(',')
                    .append(Math.round(bounds.bottom / 8f)).append(',')
                    .append(clippedText(target.text, 12).hashCode());
        }
    }

    private void drawTextTruncation(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                    List<Target> editTexts, List<Target> texts, int seed) {
        List<Target> targets = editTexts.isEmpty() ? texts : editTexts;
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        RectF cover = new RectF(bounds.left + bounds.width() * 0.58f, bounds.top - unit / 4f,
                Math.min(width, bounds.right + unit / 3f), bounds.bottom + unit / 4f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit));
        canvas.drawRect(cover, paint);
        paint.setColor(target.foregroundColor);
        paint.setTextSize(textSizeFor(bounds, density));
        canvas.drawText("...", cover.left + unit / 3f, textBaseline(bounds), paint);
    }

    private void drawTextOverlap(Canvas canvas, int width, int height, float unit, float density,
                                 List<Target> texts, int seed) {
        Target target = targetOrFallback(texts, targetIndex(texts, seed), width, height, unit);
        RectF bounds = target.bounds;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(target.foregroundColor);
        paint.setTextSize(textSizeFor(bounds, density));
        canvas.drawText(clippedText(target.text, 18), bounds.left + unit * 0.85f,
                textBaseline(bounds) + unit * 0.55f, paint);
    }

    private void drawComponentOcclusion(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                        float density, List<Target> targets, int seed) {
        if (targets.isEmpty()) {
            return;
        }
        Target target = componentTarget(width, height, unit, targets, seed);
        RectF bounds = target.bounds;
        RectF occluder = componentOccluder(bounds, unit, seed, width, height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit));
        canvas.drawRoundRect(occluder, unit / 2f, unit / 2f, paint);
    }

    private void drawFontScaleChange(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                     float density, List<Target> editTexts, List<Target> texts, int seed) {
        List<Target> targets = textTargets(editTexts, texts);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        Rect source = bitmapRect(cleanFrame, bounds);
        if (cleanFrame == null || cleanFrame.isRecycled() || source.isEmpty()) {
            return;
        }
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        Bitmap textPixels = textPixelBitmap(cleanFrame, source, background, false);
        Bitmap erasePixels = textPixelBitmap(cleanFrame, source, background, true);
        if (textPixels == null || erasePixels == null) {
            return;
        }
        canvas.drawBitmap(erasePixels, source.left, source.top, paint);
        float scale = Math.abs(seed) % 2 == 0 ? 1.22f : 0.82f;
        float destWidth = Math.max(1f, source.width() * scale);
        float destHeight = Math.max(1f, source.height() * scale);
        RectF destination = new RectF(
                source.left,
                source.exactCenterY() - destHeight / 2f,
                Math.min(width, source.left + destWidth),
                Math.min(height, source.exactCenterY() + destHeight / 2f));
        paint.setAlpha(255);
        canvas.drawBitmap(textPixels, null, destination, paint);
        textPixels.recycle();
        erasePixels.recycle();
    }

    private void drawIconDeletion(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                  List<Target> actions, int seed) {
        List<Target> targets = iconDeletionTargets(actions);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        Bitmap hiddenFrame = captureFrameWithHiddenView(target.view, width, height);
        Rect source = bitmapRect(hiddenFrame, target.bounds);
        if (hiddenFrame != null && !hiddenFrame.isRecycled() && !source.isEmpty()) {
            paint.setAlpha(255);
            canvas.drawBitmap(hiddenFrame, source, new RectF(source), paint);
            hiddenFrame.recycle();
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, target.bounds, target.backgroundColor, unit));
        canvas.drawRect(target.bounds, paint);
    }

    private void drawComponentResize(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                     float density, List<Target> actions, List<Target> imageButtons,
                                     List<Target> images, int seed) {
        List<Target> targets = componentResizeTargets(width, height, unit, actions, imageButtons, images);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        Rect source = bitmapRect(cleanFrame, target.bounds);
        if (cleanFrame == null || cleanFrame.isRecycled() || source.isEmpty()) {
            return;
        }
        int background = sampleBackgroundColor(cleanFrame, target.bounds, target.backgroundColor, unit);
        Bitmap contentPixels = visiblePixelBitmap(cleanFrame, source, background, false);
        Bitmap erasePixels = visiblePixelBitmap(cleanFrame, source, background, true);
        if (contentPixels == null || erasePixels == null) {
            return;
        }
        RectF destination = componentResizeDestination(target.bounds, unit, seed, width, height);
        paint.setAlpha(255);
        canvas.drawBitmap(erasePixels, source.left, source.top, paint);
        canvas.drawBitmap(contentPixels, null, destination, paint);
        contentPixels.recycle();
        erasePixels.recycle();
    }

    private void drawLowContrastText(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                     List<Target> actions, List<Target> editTexts, List<Target> texts, int seed) {
        List<Target> targets = lowContrastTargets(editTexts, texts, actions);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        Rect source = bitmapRect(cleanFrame, bounds);
        if (cleanFrame == null || cleanFrame.isRecycled() || source.isEmpty()) {
            return;
        }
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        Bitmap lowContrastText = lowContrastTextBitmap(cleanFrame, source, background);
        if (lowContrastText == null) {
            return;
        }
        canvas.drawBitmap(lowContrastText, source.left, source.top, paint);
        lowContrastText.recycle();
        paint.setAlpha(255);
    }

    private void drawPaddingShift(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                  List<Target> actions, List<Target> texts, int seed) {
        List<Target> targets = paddingShiftTargets(width, height, unit, actions, texts, seed);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        Rect source = bitmapRect(cleanFrame, bounds);
        RectF shifted = paddingShiftDestination(bounds, unit, seed, width, height);
        if (cleanFrame != null && !cleanFrame.isRecycled() && !source.isEmpty()) {
            int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
            Bitmap contentPixels = textPixelBitmap(cleanFrame, source, background, false);
            Bitmap erasePixels = textPixelBitmap(cleanFrame, source, background, true);
            if (contentPixels != null && erasePixels != null) {
                canvas.drawBitmap(erasePixels, source.left, source.top, paint);
                canvas.drawBitmap(contentPixels, null, shifted, paint);
                contentPixels.recycle();
                erasePixels.recycle();
                return;
            }
        }
        paint.setColor(target.foregroundColor);
        paint.setTextSize(targetTextSize(target, target.bounds, density));
        canvas.drawText(clippedText(target.text, 22), shifted.left + unit / 2f, textBaseline(shifted), paint);
    }

    private void drawTextColorChange(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                     float density, List<Target> actions, List<Target> editTexts,
                                     List<Target> texts, int seed) {
        List<Target> targets = lowContrastTargets(editTexts, texts, actions);
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        Rect source = bitmapRect(cleanFrame, bounds);
        if (cleanFrame == null || cleanFrame.isRecycled() || source.isEmpty()) {
            return;
        }
        int replacement = distinctMutationColor(seed, background, target.foregroundColor);
        Bitmap recoloredText = recoloredVisibleBitmap(cleanFrame, source, background, replacement);
        if (recoloredText == null) {
            return;
        }
        paint.setAlpha(255);
        canvas.drawBitmap(recoloredText, source.left, source.top, paint);
        recoloredText.recycle();
    }

    private void drawComponentSwap(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                   float density, List<Target> actions, List<Target> texts,
                                   List<Target> imageButtons, List<Target> images, int seed) {
        TargetPair pair = componentSwapPair(width, height, unit, actions, texts, imageButtons, images, seed);
        if (pair == null || cleanFrame == null || cleanFrame.isRecycled()) {
            return;
        }
        Rect firstSource = bitmapRect(cleanFrame, pair.first.bounds);
        Rect secondSource = bitmapRect(cleanFrame, pair.second.bounds);
        if (firstSource.isEmpty() || secondSource.isEmpty()) {
            return;
        }
        eraseTarget(canvas, cleanFrame, pair.first.bounds, pair.first.backgroundColor, unit);
        eraseTarget(canvas, cleanFrame, pair.second.bounds, pair.second.backgroundColor, unit);
        paint.setAlpha(255);
        canvas.drawBitmap(cleanFrame, firstSource, pair.second.bounds, paint);
        canvas.drawBitmap(cleanFrame, secondSource, pair.first.bounds, paint);
    }

    private RectF componentOccluderBounds(int width, int height, float unit, List<Target> targets, int seed) {
        if (targets.isEmpty()) {
            return new RectF(0f, 0f, 0f, 0f);
        }
        Target target = componentTarget(width, height, unit, targets, seed);
        return componentOccluder(target.bounds, unit, seed, width, height);
    }

    private Target componentTarget(int width, int height, float unit, List<Target> targets, int seed) {
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        return new Target(target.bounds, target.backgroundColor, target.foregroundColor, target.text,
                target.kind, target.textSize, target.view);
    }

    private List<Target> componentOcclusionTargets(int width, int height, float unit, List<Target> actions,
                                                   List<Target> texts) {
        List<Target> targets = new ArrayList<>();
        for (Target target : texts) {
            if (target.text == null || target.text.trim().isEmpty()) {
                continue;
            }
            if (isReasonableOcclusionTarget(width, height, unit, target.bounds)) {
                targets.add(target);
            }
        }
        if (!targets.isEmpty()) {
            return targets;
        }
        for (Target target : actions) {
            if (!"button".equals(target.kind) || target.text == null || target.text.trim().isEmpty()) {
                continue;
            }
            if (isReasonableOcclusionTarget(width, height, unit, target.bounds)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private boolean isReasonableOcclusionTarget(int width, int height, float unit, RectF bounds) {
        if (bounds.width() < unit * 3.5f || bounds.height() < unit * 1.2f) {
            return false;
        }
        if (bounds.width() > width * 0.72f || bounds.height() > unit * 8f) {
            return false;
        }
        if (bounds.top < unit * 6.5f || bounds.bottom > height - unit * 7f) {
            return false;
        }
        return bounds.left >= unit && bounds.right <= width - unit;
    }

    private RectF componentOccluder(RectF bounds, float unit, int seed, int width, int height) {
        float widthRatio = Math.abs(seed) % 2 == 0 ? 0.50f : 0.62f;
        float heightRatio = Math.abs(seed / 3) % 2 == 0 ? 0.48f : 0.60f;
        float occluderW = Math.max(unit * 3f, Math.min(bounds.width() * widthRatio, unit * 8f));
        float occluderH = Math.max(unit * 1.1f, Math.min(bounds.height() * heightRatio, unit * 3f));
        float offsetX = Math.abs(seed) % 2 == 0 ? 0.18f : 0.52f;
        float offsetY = Math.abs(seed / 5) % 2 == 0 ? 0.16f : 0.46f;
        float left = bounds.left + (bounds.width() - occluderW) * offsetX;
        float top = bounds.top + (bounds.height() - occluderH) * offsetY;
        return new RectF(
                Math.max(0f, left),
                Math.max(0f, top),
                Math.min(width, left + occluderW),
                Math.min(height, top + occluderH));
    }

    private List<Target> componentImages(int width, int height, float unit, List<Target> images) {
        List<Target> targets = new ArrayList<>();
        for (Target target : images) {
            RectF bounds = target.bounds;
            float shorterSide = Math.min(bounds.width(), bounds.height());
            float longerSide = Math.max(bounds.width(), bounds.height());
            if (shorterSide < unit * 1.2f || longerSide > unit * 10f) {
                continue;
            }
            if (longerSide > 0f && shorterSide / longerSide < 0.35f) {
                continue;
            }
            if (bounds.bottom > height - unit * 3f || bounds.left < 0f || bounds.right > width) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    private List<Target> contentImages(int width, int height, float unit, List<Target> images) {
        List<Target> targets = new ArrayList<>();
        for (Target target : images) {
            RectF bounds = target.bounds;
            float shorterSide = Math.min(bounds.width(), bounds.height());
            float longerSide = Math.max(bounds.width(), bounds.height());
            if (bounds.width() < 6f * unit || bounds.height() < 6f * unit) {
                continue;
            }
            if (bounds.top < 5f * unit || bounds.bottom > height - 5f * unit) {
                continue;
            }
            if (longerSide > 0f && shorterSide / longerSide < 0.22f) {
                continue;
            }
            if (bounds.left < 0f || bounds.right > width) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    private TargetPair componentSwapPair(int width, int height, float unit, List<Target> actions,
                                         List<Target> texts, List<Target> imageButtons, List<Target> images,
                                         int seed) {
        List<Target> targets = componentSwapTargets(width, height, unit, actions, texts, imageButtons, images);
        if (targets.size() < 2) {
            return null;
        }
        TargetPair sameKindPair = findComponentSwapPair(targets, seed, true, true, unit);
        if (sameKindPair != null) {
            return sameKindPair;
        }
        TargetPair distinctPair = findComponentSwapPair(targets, seed, false, true, unit);
        if (distinctPair != null) {
            return distinctPair;
        }
        sameKindPair = findComponentSwapPair(targets, seed, true, false, unit);
        if (sameKindPair != null) {
            return sameKindPair;
        }
        return findComponentSwapPair(targets, seed, false, false, unit);
    }

    private TargetPair findComponentSwapPair(List<Target> targets, int seed, boolean sameKindOnly,
                                             boolean distinctOnly, float unit) {
        int start = targetIndex(targets, seed);
        for (int offset = 0; offset < targets.size(); offset++) {
            Target first = targets.get((start + offset) % targets.size());
            for (int distance = 1; distance < targets.size(); distance++) {
                Target second = targets.get((start + offset + distance) % targets.size());
                if (isCompatibleSwapPair(first, second, sameKindOnly, distinctOnly, unit)) {
                    return new TargetPair(first, second);
                }
            }
        }
        return null;
    }

    private boolean isCompatibleSwapPair(Target first, Target second, boolean sameKindOnly,
                                         boolean distinctOnly, float unit) {
        if (first == second || first.view == second.view) {
            return false;
        }
        if (sameKindOnly && !first.kind.equals(second.kind)) {
            return false;
        }
        if (distinctOnly && !hasDistinctSwapContent(first, second)) {
            return false;
        }
        if (significantOverlap(first.bounds, second.bounds, unit)) {
            return false;
        }
        float widthRatio = ratio(first.bounds.width(), second.bounds.width());
        float heightRatio = ratio(first.bounds.height(), second.bounds.height());
        if (widthRatio < 0.65f || heightRatio < 0.65f) {
            return false;
        }
        float verticalGap = Math.abs(first.bounds.centerY() - second.bounds.centerY());
        float horizontalGap = Math.abs(first.bounds.centerX() - second.bounds.centerX());
        float maxHeight = Math.max(first.bounds.height(), second.bounds.height());
        float maxWidth = Math.max(first.bounds.width(), second.bounds.width());
        return verticalGap <= Math.max(unit * 3f, maxHeight * 2.5f)
                || horizontalGap <= Math.max(unit * 3f, maxWidth * 2.5f);
    }

    private boolean hasDistinctSwapContent(Target first, Target second) {
        String firstText = first.text == null ? "" : first.text.trim();
        String secondText = second.text == null ? "" : second.text.trim();
        if (!firstText.isEmpty() && !secondText.isEmpty()) {
            return !firstText.equals(secondText);
        }
        return !first.kind.equals(second.kind);
    }

    private float ratio(float first, float second) {
        float larger = Math.max(first, second);
        if (larger <= 0f) {
            return 0f;
        }
        return Math.min(first, second) / larger;
    }

    private List<Target> componentSwapTargets(int width, int height, float unit, List<Target> actions,
                                              List<Target> texts, List<Target> imageButtons, List<Target> images) {
        List<Target> targets = new ArrayList<>();
        for (Target target : texts) {
            addComponentSwapTarget(targets, target, width, height, unit);
        }
        for (Target target : actions) {
            addComponentSwapTarget(targets, target, width, height, unit);
        }
        List<Target> contentImageTargets = contentImages(width, height, unit, images);
        for (Target target : images) {
            if (!isSmallImageComponent(width, height, unit, target.bounds)
                    && !contentImageTargets.contains(target)) {
                continue;
            }
            addComponentSwapTarget(targets, target, width, height, unit);
        }
        return targets;
    }

    private void addComponentSwapTarget(List<Target> targets, Target target, int width, int height, float unit) {
        if (targets.size() >= MAX_TARGETS || hasSameView(targets, target)) {
            return;
        }
        if (!isComponentSwapBounds(width, height, unit, target.bounds)) {
            return;
        }
        targets.add(target);
    }

    private boolean hasSameView(List<Target> targets, Target candidate) {
        for (Target target : targets) {
            if (target.view == candidate.view) {
                return true;
            }
        }
        return false;
    }

    private boolean isComponentSwapBounds(int width, int height, float unit, RectF bounds) {
        if (bounds.width() < unit * 2f || bounds.height() < unit * 1.2f) {
            return false;
        }
        if (bounds.width() > unit * 36f || bounds.height() > unit * 16f) {
            return false;
        }
        return bounds.left >= 0f && bounds.right <= width && bounds.top >= 0f && bounds.bottom <= height;
    }

    private List<Target> textTargets(List<Target> editTexts, List<Target> texts) {
        List<Target> targets = new ArrayList<>(texts);
        for (Target target : editTexts) {
            targets.add(target);
        }
        return targets;
    }

    private List<Target> lowContrastTargets(List<Target> editTexts, List<Target> texts, List<Target> actions) {
        List<Target> targets = textTargets(editTexts, texts);
        for (Target target : actions) {
            if (target.text != null && !target.text.trim().isEmpty()) {
                targets.add(target);
            }
        }
        return targets;
    }

    private List<Target> iconDeletionTargets(List<Target> actions) {
        List<Target> targets = new ArrayList<>();
        for (Target target : actions) {
            if ("image_button".equals(target.kind) || "image_action".equals(target.kind)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private List<Target> componentResizeTargets(int width, int height, float unit, List<Target> actions,
                                                List<Target> imageButtons, List<Target> images) {
        List<Target> targets = new ArrayList<>();
        for (Target target : actions) {
            if (isTopAppBarTarget(target, unit) || !isResizableNonTextComponent(width, height, unit, target.bounds)) {
                continue;
            }
            targets.add(target);
        }
        for (Target target : imageButtons) {
            if (isResizableNonTextComponent(width, height, unit, target.bounds)) {
                targets.add(target);
            }
        }
        for (Target target : images) {
            if (isSmallImageComponent(width, height, unit, target.bounds)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private boolean isResizableNonTextComponent(int width, int height, float unit, RectF bounds) {
        if (bounds.width() < unit * 2f || bounds.height() < unit * 1.4f) {
            return false;
        }
        if (bounds.width() > unit * 24f || bounds.height() > unit * 12f) {
            return false;
        }
        if (bounds.left < 0f || bounds.right > width || bounds.top < unit || bounds.bottom > height - unit * 3f) {
            return false;
        }
        return true;
    }

    private boolean isSmallImageComponent(int width, int height, float unit, RectF bounds) {
        if (!isResizableNonTextComponent(width, height, unit, bounds)) {
            return false;
        }
        float longerSide = Math.max(bounds.width(), bounds.height());
        float shorterSide = Math.min(bounds.width(), bounds.height());
        return longerSide <= unit * 14f && (longerSide == 0f || shorterSide / longerSide >= 0.30f);
    }

    private RectF componentResizeDestination(RectF bounds, float unit, int seed, int width, int height) {
        float scale = Math.abs(seed) % 2 == 0 ? 0.55f : 1.45f;
        float destWidth = Math.max(unit * 1.2f, bounds.width() * scale);
        float destHeight = Math.max(unit * 0.9f, bounds.height() * scale);
        float left = bounds.left;
        float top = bounds.centerY() - destHeight / 2f;
        if (left + destWidth > width) {
            left = Math.max(0f, bounds.right - destWidth);
        }
        if (top < 0f) {
            top = 0f;
        }
        if (top + destHeight > height) {
            top = Math.max(0f, height - destHeight);
        }
        return new RectF(left, top, Math.min(width, left + destWidth), Math.min(height, top + destHeight));
    }

    private List<Target> paddingShiftTargets(int width, int height, float unit, List<Target> actions,
                                             List<Target> texts, int seed) {
        List<Target> textTargets = paddingTextTargets(width, height, unit, texts, seed);
        if (!textTargets.isEmpty()) {
            return textTargets;
        }

        List<Target> actionTargets = new ArrayList<>();
        for (Target target : actions) {
            if (isTopAppBarTarget(target, unit) || target.text == null || target.text.trim().isEmpty()) {
                continue;
            }
            RectF destination = paddingShiftDestination(target.bounds, unit, seed, width, height);
            if (!overlapsText(target.bounds, texts, unit) && !overlapsText(destination, texts, unit)) {
                actionTargets.add(target);
            }
        }
        return actionTargets;
    }

    private List<Target> paddingTextTargets(int width, int height, float unit, List<Target> texts, int seed) {
        List<Target> targets = new ArrayList<>();
        for (Target target : texts) {
            if (isTopAppBarTarget(target, unit)) {
                continue;
            }
            RectF source = paddingShiftSource(target, unit, width, height);
            RectF destination = paddingShiftDestination(source, unit, seed, width, height);
            if (!overlapsOtherText(source, destination, target, texts, unit)) {
                targets.add(target);
            }
        }
        return targets;
    }

    private boolean isTopAppBarTarget(Target target, float unit) {
        return target.bounds.top < 6.5f * unit;
    }

    private boolean overlapsOtherText(RectF source, RectF destination, Target selected, List<Target> texts,
                                      float unit) {
        for (Target text : texts) {
            if (text == selected) {
                continue;
            }
            if (significantOverlap(source, text.bounds, unit)
                    || significantOverlap(destination, text.bounds, unit)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlapsText(RectF rect, List<Target> texts, float unit) {
        for (Target text : texts) {
            if (significantOverlap(rect, text.bounds, unit)) {
                return true;
            }
        }
        return false;
    }

    private boolean significantOverlap(RectF first, RectF second, float unit) {
        float left = Math.max(first.left, second.left);
        float top = Math.max(first.top, second.top);
        float right = Math.min(first.right, second.right);
        float bottom = Math.min(first.bottom, second.bottom);
        if (right <= left || bottom <= top) {
            return false;
        }
        float overlapArea = (right - left) * (bottom - top);
        float textArea = Math.max(1f, second.width() * second.height());
        float minArea = Math.max(4f, unit * unit * 0.08f);
        return overlapArea >= minArea && overlapArea / textArea >= 0.08f;
    }

    private RectF paddingShiftSource(Target target, float unit, int width, int height) {
        return expand(target.bounds, unit / 4f, width, height);
    }

    private RectF paddingShiftDestination(RectF source, float unit, int seed, int width, int height) {
        float shiftX = Math.min(2.4f * unit, Math.max(unit, source.width() * 0.18f));
        if (source.right + shiftX > width) {
            shiftX = -shiftX;
        }
        if (source.left + shiftX < 0f) {
            shiftX = Math.abs(shiftX);
        }
        float shiftY = Math.abs(seed) % 3 == 0 ? unit * 0.45f : 0f;
        if (source.bottom + shiftY > height) {
            shiftY = -shiftY;
        }
        if (source.top + shiftY < 0f) {
            shiftY = 0f;
        }
        return new RectF(source.left + shiftX, source.top + shiftY, source.right + shiftX,
                source.bottom + shiftY);
    }

    private Rect bitmapRect(Bitmap bitmap, RectF bounds) {
        if (bitmap == null || bitmap.isRecycled()) {
            return new Rect();
        }
        return new Rect(
                clamp(Math.round(bounds.left), 0, bitmap.getWidth()),
                clamp(Math.round(bounds.top), 0, bitmap.getHeight()),
                clamp(Math.round(bounds.right), 0, bitmap.getWidth()),
                clamp(Math.round(bounds.bottom), 0, bitmap.getHeight()));
    }

    private void eraseTarget(Canvas canvas, Bitmap cleanFrame, RectF bounds, int fallbackBackground, float unit) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(sampleBackgroundColor(cleanFrame, bounds, fallbackBackground, unit));
        canvas.drawRect(bounds, paint);
    }

    private Bitmap textPixelBitmap(Bitmap cleanFrame, Rect source, int background, boolean erase) {
        return visiblePixelBitmap(cleanFrame, source, background, erase);
    }

    private Bitmap visiblePixelBitmap(Bitmap cleanFrame, Rect source, int background, boolean erase) {
        int width = source.width();
        int height = source.height();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        cleanFrame.getPixels(pixels, 0, width, source.left, source.top, width, height);
        int threshold = 32;
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            if (Color.alpha(color) < 180 || colorDistance(color, background) < threshold) {
                pixels[i] = Color.TRANSPARENT;
                continue;
            }
            if (erase) {
                pixels[i] = background;
            } else {
                pixels[i] = forceOpaque(color);
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void recycleIfNotNull(Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }
    }

    private Bitmap lowContrastTextBitmap(Bitmap cleanFrame, Rect source, int background) {
        int width = source.width();
        int height = source.height();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        cleanFrame.getPixels(pixels, 0, width, source.left, source.top, width, height);
        int threshold = 32;
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            if (Color.alpha(color) < 180 || colorDistance(color, background) < threshold) {
                pixels[i] = Color.TRANSPARENT;
                continue;
            }
            pixels[i] = blend(background, forceOpaque(color), 0.16f);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private Bitmap recoloredVisibleBitmap(Bitmap cleanFrame, Rect source, int background, int replacement) {
        int width = source.width();
        int height = source.height();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        cleanFrame.getPixels(pixels, 0, width, source.left, source.top, width, height);
        int threshold = 32;
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            if (Color.alpha(color) < 180 || colorDistance(color, background) < threshold) {
                pixels[i] = Color.TRANSPARENT;
                continue;
            }
            pixels[i] = Color.argb(Color.alpha(color), Color.red(replacement), Color.green(replacement),
                    Color.blue(replacement));
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private int distinctMutationColor(int seed, int background, int current) {
        int value = seed ^ (background * 31) ^ (current * 17);
        for (int attempt = 0; attempt < 32; attempt++) {
            value = value * 1103515245 + 12345 + attempt * 97;
            float hue = positiveModulo(value, 360);
            float saturation = 0.72f + (positiveModulo(value / 7, 27) / 100f);
            float brightness = 0.55f + (positiveModulo(value / 11, 38) / 100f);
            int candidate = Color.HSVToColor(new float[]{hue, Math.min(0.98f, saturation),
                    Math.min(0.93f, brightness)});
            if (isDistinctMutationColor(candidate, background, current)) {
                return candidate;
            }
        }
        int fallbackHue = positiveModulo(seed + 173, 360);
        int candidate = Color.HSVToColor(new float[]{fallbackHue, 0.92f, 0.82f});
        if (isDistinctMutationColor(candidate, background, current)) {
            return candidate;
        }
        return Color.HSVToColor(new float[]{positiveModulo(fallbackHue + 268, 360), 0.95f, 0.78f});
    }

    private boolean isDistinctMutationColor(int color, int background, int current) {
        return !isNeutralColor(color)
                && colorDistance(color, background) >= 170
                && colorDistance(color, current) >= 170;
    }

    private boolean isNeutralColor(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max < 45 || min > 225 || max - min < 35;
    }

    private int positiveModulo(int value, int modulo) {
        int result = value % modulo;
        return result < 0 ? result + modulo : result;
    }

    private int colorDistance(int first, int second) {
        int red = Color.red(first) - Color.red(second);
        int green = Color.green(first) - Color.green(second);
        int blue = Color.blue(first) - Color.blue(second);
        return Math.abs(red) + Math.abs(green) + Math.abs(blue);
    }

    private Target targetOrFallback(List<Target> targets, int index, int width, int height, float unit) {
        if (!targets.isEmpty()) {
            return targets.get(Math.abs(index) % targets.size());
        }
        float left = index == 0 ? 3f * unit : width - 19f * unit;
        float top = index == 0 ? height * 0.34f : height * 0.55f;
        return new Target(new RectF(left, top, left + 16f * unit, top + 5f * unit),
                surfaceColor(), Color.rgb(80, 80, 80), "", "fallback");
    }

    private int targetIndex(List<Target> targets, int seed) {
        if (targets.isEmpty()) {
            return Math.abs(seed % 2);
        }
        return Math.abs(seed) % targets.size();
    }

    private RectF expand(RectF rect, float amount, int width, int height) {
        return new RectF(Math.max(0f, rect.left - amount), Math.max(0f, rect.top - amount),
                Math.min(width, rect.right + amount), Math.min(height, rect.bottom + amount));
    }

    private RectF union(RectF first, RectF second) {
        return new RectF(Math.min(first.left, second.left), Math.min(first.top, second.top),
                Math.max(first.right, second.right), Math.max(first.bottom, second.bottom));
    }

    private void writeMutationMask(int id, int width, int height, float unit, float density, long elapsed,
                                   Bitmap cleanFrame, List<Target> actions, List<Target> editTexts, List<Target> texts,
                                   List<Target> imageButtons, List<Target> images, int seed, RectF bounds) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(bitmap);
        drawMutation(maskCanvas, width, height, unit, density, elapsed, cleanFrame, actions, editTexts, texts,
                imageButtons, images, seed);
        File directory = getContext().getFilesDir();
        if (directory == null) {
            bitmap.recycle();
            return;
        }
        File file = new File(directory, "visual_mutation_masks.jsonl");
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write("{\"mask_id\":" + id + ",\"mutation_type\":\"" + operator.name() + "\",\"screen\":["
                    + width + "," + height + "],\"bbox\":{\"left\":" + Math.round(bounds.left)
                    + ",\"top\":" + Math.round(bounds.top) + ",\"right\":" + Math.round(bounds.right)
                    + ",\"bottom\":" + Math.round(bounds.bottom)
                    + "},\"mask\":{\"format\":\"rle\",\"size\":[" + width + "," + height
                    + "],\"order\":\"row_major\",\"counts\":[" + maskRle(bitmap) + "]}}\n");
        } catch (IOException e) {
            Log.w(TAG, "visual_mutation_mask_write_failed", e);
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap captureFrameWithHiddenView(View hiddenView, int width, int height) {
        if (hiddenView == null) {
            return null;
        }
        View viewToHide = smallClickableAncestor(hiddenView);
        float previousAlpha = viewToHide.getAlpha();
        viewToHide.setAlpha(0f);
        try {
            return captureCleanFrame(width, height);
        } finally {
            viewToHide.setAlpha(previousAlpha);
        }
    }

    private View smallClickableAncestor(View view) {
        View candidate = view;
        while (candidate != null) {
            if (candidate.isClickable() || candidate.isLongClickable()) {
                Rect rect = new Rect();
                if (candidate.getGlobalVisibleRect(rect)
                        && rect.width() >= 24 && rect.height() >= 24
                        && rect.width() <= 80 && rect.height() <= 80) {
                    return candidate;
                }
            }
            ViewParent parent = candidate.getParent();
            candidate = parent instanceof View ? (View) parent : null;
        }
        return view;
    }

    private Bitmap captureCleanFrame(int width, int height) {
        View root = getRootView();
        if (root == null || root.getWidth() == 0 || root.getHeight() == 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas cleanCanvas = new Canvas(bitmap);
        int[] rootLocation = new int[2];
        int[] layerLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        getLocationOnScreen(layerLocation);
        int save = cleanCanvas.save();
        cleanCanvas.translate(rootLocation[0] - layerLocation[0], rootLocation[1] - layerLocation[1]);
        capturingCleanFrame = true;
        try {
            root.draw(cleanCanvas);
        } finally {
            capturingCleanFrame = false;
            cleanCanvas.restoreToCount(save);
        }
        return bitmap;
    }

    private int sampleBackgroundColor(Bitmap cleanFrame, RectF target, int fallback, float unit) {
        if (cleanFrame == null || cleanFrame.isRecycled()) {
            return fallback;
        }
        int width = cleanFrame.getWidth();
        int height = cleanFrame.getHeight();
        int margin = Math.max(3, Math.round(unit * 0.6f));
        int left = clamp(Math.round(target.left), 0, width);
        int top = clamp(Math.round(target.top), 0, height);
        int right = clamp(Math.round(target.right), 0, width);
        int bottom = clamp(Math.round(target.bottom), 0, height);
        int sampleLeft = clamp(left - margin, 0, width);
        int sampleTop = clamp(top - margin, 0, height);
        int sampleRight = clamp(right + margin, 0, width);
        int sampleBottom = clamp(bottom + margin, 0, height);
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        int step = Math.max(1, margin / 2);

        for (int y = sampleTop; y < sampleBottom; y += step) {
            for (int x = sampleLeft; x < sampleRight; x += step) {
                boolean insideTarget = x >= left && x < right && y >= top && y < bottom;
                if (insideTarget) {
                    continue;
                }
                int color = cleanFrame.getPixel(x, y);
                if (Color.alpha(color) < 200) {
                    continue;
                }
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }

        if (count < 4) {
            return fallback;
        }
        return Color.rgb(Math.round(red / (float) count), Math.round(green / (float) count),
                Math.round(blue / (float) count));
    }

    private static void resetMaskFile(Context context) {
        if (maskFileReset) {
            return;
        }
        maskFileReset = true;
        File file = new File(context.getFilesDir(), "visual_mutation_masks.jsonl");
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "visual_mutation_mask_reset_failed");
        }
    }

    private String maskRle(Bitmap bitmap) {
        StringBuilder counts = new StringBuilder();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width];
        int current = 0;
        int count = 0;
        boolean first = true;
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int value = Color.alpha(pixels[x]) > 0 ? 1 : 0;
                if (value == current) {
                    count++;
                } else {
                    if (!first) {
                        counts.append(',');
                    }
                    counts.append(count);
                    first = false;
                    current = value;
                    count = 1;
                }
            }
        }
        if (!first) {
            counts.append(',');
        }
        counts.append(count);
        return counts.toString();
    }

    private String formatBounds(RectF bounds) {
        return Math.round(bounds.left) + "," + Math.round(bounds.top) + "," + Math.round(bounds.right) + ","
                + Math.round(bounds.bottom);
    }

    private void drawTargetText(Canvas canvas, RectF bounds, String text, int color, float density, float unit) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(textSizeFor(bounds, density));
        canvas.drawText(clippedText(text, 22), bounds.left + unit / 2f, textBaseline(bounds), paint);
    }

    private float textSizeFor(RectF bounds, float density) {
        return Math.max(11f * density, Math.min(18f * density, bounds.height() * 0.48f));
    }

    private float targetTextSize(Target target, RectF bounds, float density) {
        if (target.textSize > 0f) {
            return target.textSize;
        }
        return textSizeFor(bounds, density);
    }

    private float textBaseline(RectF bounds) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
    }

    private String clippedText(String text, int maxChars) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private List<Target> mergeTargets(List<Target> first, List<Target> second) {
        List<Target> merged = new ArrayList<>(first);
        for (Target bound : second) {
            if (merged.size() >= MAX_TARGETS) {
                break;
            }
            merged.add(bound);
        }
        return merged;
    }

    private int surfaceColor() {
        return Color.rgb(250, 250, 250);
    }

    private List<Target> visibleTargets(Class<? extends View> viewClass, String kind) {
        List<Target> targets = new ArrayList<>();
        collectVisibleTargets(getRootView(), viewClass, kind, targets);
        return targets;
    }

    private void collectVisibleTargets(View view, Class<? extends View> viewClass, String kind, List<Target> targets) {
        if (view == null || view == this || targets.size() >= MAX_TARGETS || view.getVisibility() != VISIBLE
                || view.getWidth() == 0 || view.getHeight() == 0) {
            return;
        }
        if (viewClass.isInstance(view) && isEligibleTarget(view, viewClass, kind)) {
            Rect rect = new Rect();
            if (view.getGlobalVisibleRect(rect)) {
                int[] layerLocation = new int[2];
                getLocationOnScreen(layerLocation);
                rect.offset(-layerLocation[0], -layerLocation[1]);
                RectF bounds = new RectF(rect);
                if ("image_action".equals(kind)) {
                    bounds = clickableImageActionBounds(view, bounds);
                }
                if (!isPlausibleTarget(view, viewClass, kind, bounds)) {
                    return;
                }
                String text = targetText(view);
                targets.add(new Target(bounds, resolveBackgroundColor(view), resolveForegroundColor(view),
                        text, kind, targetTextSize(view), view));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && targets.size() < MAX_TARGETS; i++) {
                collectVisibleTargets(group.getChildAt(i), viewClass, kind, targets);
            }
        }
    }

    private boolean isEligibleTarget(View view, Class<? extends View> viewClass, String kind) {
        if (viewClass == TextView.class) {
            return !(view instanceof Button) && !(view instanceof EditText) && !targetText(view).isEmpty();
        }
        if (viewClass == EditText.class) {
            return !targetText(view).isEmpty();
        }
        if (viewClass == ImageView.class) {
            return !(view instanceof ImageButton);
        }
        if (viewClass == Button.class) {
            return !targetText(view).isEmpty();
        }
        return true;
    }

    private boolean isClickableImage(View view) {
        if (view.isClickable() || view.isLongClickable()) {
            return true;
        }
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View parentView = (View) parent;
            if (parentView.isClickable() || parentView.isLongClickable()) {
                return true;
            }
            parent = parentView.getParent();
        }
        return false;
    }

    private RectF clickableImageActionBounds(View view, RectF fallback) {
        View candidate = view;
        while (candidate != null) {
            if (candidate.isClickable() || candidate.isLongClickable()) {
                Rect rect = new Rect();
                if (candidate.getGlobalVisibleRect(rect)) {
                    int[] layerLocation = new int[2];
                    getLocationOnScreen(layerLocation);
                    rect.offset(-layerLocation[0], -layerLocation[1]);
                    RectF bounds = new RectF(rect);
                    if (bounds.width() >= 24f && bounds.height() >= 24f
                            && bounds.width() <= 80f && bounds.height() <= 80f) {
                        return bounds;
                    }
                }
            }
            ViewParent parent = candidate.getParent();
            candidate = parent instanceof View ? (View) parent : null;
        }
        return fallback;
    }

    private RectF viewBounds(View view, int width, int height) {
        Rect rect = new Rect();
        if (view == null || !view.getGlobalVisibleRect(rect)) {
            return new RectF();
        }
        int[] layerLocation = new int[2];
        getLocationOnScreen(layerLocation);
        rect.offset(-layerLocation[0], -layerLocation[1]);
        return new RectF(
                Math.max(0f, rect.left),
                Math.max(0f, rect.top),
                Math.min(width, rect.right),
                Math.min(height, rect.bottom));
    }

    private boolean isPlausibleTarget(View view, Class<? extends View> viewClass, String kind, RectF bounds) {
        if (bounds.width() < 16f || bounds.height() < 12f) {
            return false;
        }
        if (viewClass == TextView.class || viewClass == EditText.class) {
            return bounds.width() >= 40f && bounds.height() >= 14f;
        }
        if (viewClass == Button.class) {
            return bounds.width() >= 48f && bounds.height() >= 24f;
        }
        if (viewClass == ImageButton.class) {
            return bounds.width() >= 24f && bounds.height() >= 24f
                    && ((ImageButton) view).getDrawable() != null;
        }
        if (viewClass == ImageView.class) {
            boolean smallIconImage = bounds.width() >= 16f && bounds.height() >= 16f
                    && bounds.width() <= 80f && bounds.height() <= 80f
                    && ((ImageView) view).getDrawable() != null;
            if ("image_action".equals(kind)) {
                return smallIconImage;
            }
            return !smallIconImage && bounds.width() >= 24f && bounds.height() >= 24f
                    && ((ImageView) view).getDrawable() != null;
        }
        return true;
    }

    private String targetText(View view) {
        if (!(view instanceof TextView)) {
            return "";
        }
        TextView textView = (TextView) view;
        CharSequence text = textView.getText();
        if (text != null && text.toString().trim().length() > 0) {
            return text.toString();
        }
        CharSequence hint = textView.getHint();
        return hint == null ? "" : hint.toString().trim();
    }

    private float targetTextSize(View view) {
        if (view instanceof TextView) {
            return ((TextView) view).getTextSize();
        }
        return 0f;
    }

    private int resolveForegroundColor(View view) {
        if (view instanceof TextView) {
            return forceOpaque(((TextView) view).getCurrentTextColor());
        }
        return Color.rgb(95, 95, 95);
    }

    private int resolveBackgroundColor(View view) {
        View current = view;
        while (current != null) {
            int color = colorFromDrawable(current.getBackground());
            if (color != Color.TRANSPARENT) {
                return color;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return surfaceColor();
    }

    private int colorFromDrawable(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            return blend(surfaceColor(), forceOpaque(((ColorDrawable) drawable).getColor()),
                    Color.alpha(((ColorDrawable) drawable).getColor()) / 255f);
        }
        return Color.TRANSPARENT;
    }

    private int forceOpaque(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int blend(int background, int foreground, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        int red = Math.round(Color.red(background) * (1f - clamped) + Color.red(foreground) * clamped);
        int green = Math.round(Color.green(background) * (1f - clamped) + Color.green(foreground) * clamped);
        int blue = Math.round(Color.blue(background) * (1f - clamped) + Color.blue(foreground) * clamped);
        return Color.rgb(red, green, blue);
    }
}
