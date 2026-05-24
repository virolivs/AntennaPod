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
    private static boolean maskFileReset;

    public enum Operator {
        TEXT_TRUNCATION,
        TEXT_OVERLAP,
        COMPONENT_OCCLUSION,
        MISSING_IMAGE,
        INCORRECT_PLACEHOLDER,
        BUTTON_DELETION,
        BUTTON_SWAP,
        LOW_CONTRAST_TEXT,
        PADDING_SHIFT,
        ICON_SWAP
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long startedAt = SystemClock.uptimeMillis();
    private final Operator operator;
    private boolean mutationVisible;
    private int maskId;
    private boolean capturingCleanFrame;

    private static final class Target {
        final RectF bounds;
        final int backgroundColor;
        final int foregroundColor;
        final String text;
        final String kind;

        Target(RectF bounds, int backgroundColor, int foregroundColor, String text, String kind) {
            this.bounds = bounds;
            this.backgroundColor = backgroundColor;
            this.foregroundColor = foregroundColor;
            this.text = text;
            this.kind = kind;
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
        List<Target> editTexts = visibleTargets(EditText.class, "edit_text");
        List<Target> texts = visibleTargets(TextView.class, "text");
        List<Target> images = visibleTargets(ImageView.class, "image");
        List<Target> actions = mergeTargets(buttons, imageButtons);
        int seed = visualSeed(width, height, actions, editTexts, texts, images);
        boolean shouldDraw = shouldDrawMutation(elapsed, seed, actions, editTexts, texts, imageButtons, images);
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
                drawComponentOcclusion(canvas, cleanFrame, width, height, unit, density, actions, texts, images, seed);
                break;
            case MISSING_IMAGE:
                drawMissingImage(canvas, cleanFrame, width, height, unit, density, images, seed);
                break;
            case INCORRECT_PLACEHOLDER:
                drawIncorrectPlaceholder(canvas, cleanFrame, width, height, unit, density, texts, editTexts, seed);
                break;
            case BUTTON_DELETION:
                drawButtonDeletion(canvas, cleanFrame, width, height, unit, density, actions, seed);
                break;
            case BUTTON_SWAP:
                drawButtonSwap(canvas, cleanFrame, width, height, unit, density, actions, seed);
                break;
            case LOW_CONTRAST_TEXT:
                drawLowContrastText(canvas, cleanFrame, width, height, unit, density, editTexts, texts, seed);
                break;
            case PADDING_SHIFT:
                drawPaddingShift(canvas, cleanFrame, width, height, unit, density, actions, texts, seed);
                break;
            case ICON_SWAP:
                drawIconSwap(canvas, cleanFrame, width, height, unit, density, imageButtons, images, seed);
                break;
            default:
                break;
        }
    }

    private boolean shouldDrawMutation(long elapsed, int seed, List<Target> actions, List<Target> editTexts,
                                       List<Target> texts, List<Target> imageButtons, List<Target> images) {
        if (!hasRequiredTarget(actions, editTexts, texts, imageButtons, images)) {
            return false;
        }
        int gate = Math.abs((seed / 17 + operator.ordinal()) % 5);
        if (gate == 0) {
            return false;
        }
        int period = 2100 + operator.ordinal() * 170;
        int active = 1150 + operator.ordinal() % 3 * 220;
        return (elapsed + Math.abs(seed % period)) % period < active;
    }

    private boolean hasRequiredTarget(List<Target> actions, List<Target> editTexts, List<Target> texts,
                                      List<Target> imageButtons, List<Target> images) {
        switch (operator) {
            case BUTTON_SWAP:
                return actions.size() >= 2;
            case BUTTON_DELETION:
            case PADDING_SHIFT:
                return !actions.isEmpty();
            case TEXT_TRUNCATION:
            case LOW_CONTRAST_TEXT:
                return !editTexts.isEmpty() || !texts.isEmpty();
            case TEXT_OVERLAP:
                return !texts.isEmpty();
            case INCORRECT_PLACEHOLDER:
                return !editTexts.isEmpty() || !texts.isEmpty();
            case COMPONENT_OCCLUSION:
                return !actions.isEmpty() || !texts.isEmpty() || !images.isEmpty();
            case MISSING_IMAGE:
                return !images.isEmpty();
            case ICON_SWAP:
                return !imageButtons.isEmpty() || !images.isEmpty();
            default:
                return true;
        }
    }

    private RectF mutationBounds(int width, int height, float unit, List<Target> actions,
                                 List<Target> editTexts, List<Target> texts, List<Target> imageButtons,
                                 List<Target> images, int seed) {
        switch (operator) {
            case TEXT_TRUNCATION:
            case LOW_CONTRAST_TEXT:
                List<Target> textTargets = editTexts.isEmpty() ? texts : editTexts;
                return expand(targetOrFallback(textTargets, targetIndex(textTargets, seed), width, height, unit).bounds,
                        unit / 3f, width, height);
            case TEXT_OVERLAP:
            case INCORRECT_PLACEHOLDER:
                List<Target> placeholderTargets = editTexts.isEmpty() ? texts : editTexts;
                return expand(targetOrFallback(placeholderTargets, targetIndex(placeholderTargets, seed),
                        width, height, unit).bounds, unit / 2f, width, height);
            case COMPONENT_OCCLUSION:
                return componentTargetBounds(width, height, unit, actions, texts, images, seed);
            case MISSING_IMAGE:
                return expand(targetOrFallback(images, targetIndex(images, seed), width, height, unit).bounds,
                        unit / 4f, width, height);
            case BUTTON_DELETION:
                return expand(targetOrFallback(actions, targetIndex(actions, seed), width, height, unit).bounds,
                        unit / 3f, width, height);
            case BUTTON_SWAP:
                int switchIndex = targetIndex(actions, seed);
                return union(targetOrFallback(actions, switchIndex, width, height, unit).bounds,
                        targetOrFallback(actions, switchIndex + 1, width, height, unit).bounds);
            case PADDING_SHIFT:
                return expand(targetOrFallback(actions, targetIndex(actions, seed), width, height, unit).bounds,
                        1.2f * unit, width, height);
            case ICON_SWAP:
                List<Target> iconTargets = imageButtons.isEmpty() ? images : imageButtons;
                return expand(targetOrFallback(iconTargets, targetIndex(iconTargets, seed), width, height, unit).bounds,
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
                                        float density, List<Target> actions, List<Target> texts,
                                        List<Target> images, int seed) {
        Target target = componentTarget(width, height, unit, actions, texts, images, seed);
        RectF bounds = target.bounds;
        RectF occluder = new RectF(bounds.left + bounds.width() * 0.18f, bounds.top + bounds.height() * 0.2f,
                bounds.right - bounds.width() * 0.08f, bounds.bottom - bounds.height() * 0.12f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit));
        canvas.drawRoundRect(occluder, unit / 2f, unit / 2f, paint);
    }

    private void drawMissingImage(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                  List<Target> images, int seed) {
        Target target = targetOrFallback(images, targetIndex(images, seed), width, height, unit);
        RectF bounds = target.bounds;
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRect(expand(bounds, unit / 4f, width, height), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit / 5f);
        paint.setColor(blend(background, Color.rgb(120, 120, 120), 0.45f));
        canvas.drawRect(bounds, paint);
        canvas.drawLine(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
        canvas.drawLine(bounds.right, bounds.top, bounds.left, bounds.bottom, paint);
    }

    private void drawIncorrectPlaceholder(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit,
                                          float density, List<Target> texts, List<Target> editTexts, int seed) {
        List<Target> targets = editTexts.isEmpty() ? texts : editTexts;
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF box = expand(target.bounds, unit / 2f, width, height);
        int background = sampleBackgroundColor(cleanFrame, target.bounds, target.backgroundColor, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRect(box, paint);
        paint.setColor(blend(background, target.foregroundColor, 0.28f));
        canvas.drawRoundRect(new RectF(box.left + unit / 2f, box.centerY() - unit / 4f,
                Math.min(box.right - unit / 2f, box.left + 15f * unit), box.centerY() + unit / 4f),
                unit / 4f, unit / 4f, paint);
    }

    private void drawButtonDeletion(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                    List<Target> actions, int seed) {
        Target target = targetOrFallback(actions, targetIndex(actions, seed), width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, target.bounds, target.backgroundColor, unit));
        canvas.drawRoundRect(expand(target.bounds, unit / 3f, width, height), unit / 2f, unit / 2f, paint);
    }

    private void drawButtonSwap(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                List<Target> actions, int seed) {
        int index = targetIndex(actions, seed);
        Target first = targetOrFallback(actions, index, width, height, unit);
        Target second = targetOrFallback(actions, index + 1, width, height, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, first.bounds, first.backgroundColor, unit));
        canvas.drawRoundRect(first.bounds, unit / 2f, unit / 2f, paint);
        paint.setColor(sampleBackgroundColor(cleanFrame, second.bounds, second.backgroundColor, unit));
        canvas.drawRoundRect(second.bounds, unit / 2f, unit / 2f, paint);
        drawTargetText(canvas, first.bounds, second.text, first.foregroundColor, density, unit);
        drawTargetText(canvas, second.bounds, first.text, second.foregroundColor, density, unit);
    }

    private void drawLowContrastText(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                     List<Target> editTexts, List<Target> texts, int seed) {
        List<Target> targets = editTexts.isEmpty() ? texts : editTexts;
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRect(expand(bounds, unit / 3f, width, height), paint);
        paint.setColor(blend(background, target.foregroundColor, 0.18f));
        paint.setTextSize(textSizeFor(bounds, density));
        canvas.drawText(clippedText(target.text, 24), bounds.left + unit / 4f, textBaseline(bounds), paint);
    }

    private void drawPaddingShift(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                                  List<Target> actions, List<Target> texts, int seed) {
        List<Target> targets = actions.isEmpty() ? texts : actions;
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF box = expand(target.bounds, unit / 2f, width, height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sampleBackgroundColor(cleanFrame, target.bounds, target.backgroundColor, unit));
        canvas.drawRoundRect(box, unit / 2f, unit / 2f, paint);
        RectF shifted = new RectF(Math.min(width - unit, target.bounds.left + unit), target.bounds.top,
                Math.min(width, target.bounds.right + unit), target.bounds.bottom);
        drawTargetText(canvas, shifted, target.text, target.foregroundColor, density, unit);
    }

    private void drawIconSwap(Canvas canvas, Bitmap cleanFrame, int width, int height, float unit, float density,
                              List<Target> imageButtons, List<Target> images, int seed) {
        List<Target> targets = imageButtons.isEmpty() ? images : imageButtons;
        Target target = targetOrFallback(targets, targetIndex(targets, seed), width, height, unit);
        RectF bounds = target.bounds;
        int background = sampleBackgroundColor(cleanFrame, bounds, target.backgroundColor, unit);
        float radius = Math.max(unit, Math.min(bounds.width(), bounds.height()) * 0.34f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRect(expand(bounds, unit / 5f, width, height), paint);
        paint.setColor(blend(background, target.foregroundColor, 0.6f));
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit / 5f);
        paint.setColor(background);
        canvas.drawLine(bounds.centerX() - radius / 2f, bounds.centerY(),
                bounds.centerX() + radius / 2f, bounds.centerY(), paint);
        canvas.drawLine(bounds.centerX(), bounds.centerY() - radius / 2f,
                bounds.centerX(), bounds.centerY() + radius / 2f, paint);
    }

    private RectF componentTargetBounds(int width, int height, float unit, List<Target> actions, List<Target> texts,
                                        List<Target> images, int seed) {
        return componentTarget(width, height, unit, actions, texts, images, seed).bounds;
    }

    private Target componentTarget(int width, int height, float unit, List<Target> actions, List<Target> texts,
                                   List<Target> images, int seed) {
        if (!actions.isEmpty()) {
            Target target = targetOrFallback(actions, targetIndex(actions, seed), width, height, unit);
            return new Target(expand(target.bounds, unit / 2f, width, height), target.backgroundColor,
                    target.foregroundColor, target.text, target.kind);
        }
        if (!texts.isEmpty()) {
            Target target = targetOrFallback(texts, targetIndex(texts, seed), width, height, unit);
            return new Target(expand(target.bounds, unit / 2f, width, height), target.backgroundColor,
                    target.foregroundColor, target.text, target.kind);
        }
        Target target = targetOrFallback(images, targetIndex(images, seed), width, height, unit);
        return new Target(expand(target.bounds, unit / 2f, width, height), target.backgroundColor,
                target.foregroundColor, target.text, target.kind);
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
            if (merged.size() >= 6) {
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
        if (view == null || view == this || targets.size() >= 4 || view.getVisibility() != VISIBLE
                || view.getWidth() == 0 || view.getHeight() == 0) {
            return;
        }
        if (viewClass.isInstance(view) && isEligibleTarget(view, viewClass)) {
            Rect rect = new Rect();
            if (view.getGlobalVisibleRect(rect)) {
                int[] layerLocation = new int[2];
                getLocationOnScreen(layerLocation);
                rect.offset(-layerLocation[0], -layerLocation[1]);
                RectF bounds = new RectF(rect);
                if (!isPlausibleTarget(view, viewClass, bounds)) {
                    return;
                }
                String text = targetText(view);
                targets.add(new Target(bounds, resolveBackgroundColor(view), resolveForegroundColor(view),
                        text, kind));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && targets.size() < 4; i++) {
                collectVisibleTargets(group.getChildAt(i), viewClass, kind, targets);
            }
        }
    }

    private boolean isEligibleTarget(View view, Class<? extends View> viewClass) {
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

    private boolean isPlausibleTarget(View view, Class<? extends View> viewClass, RectF bounds) {
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
            return bounds.width() >= 24f && bounds.height() >= 24f
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
