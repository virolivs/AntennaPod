package de.test.antennapod;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.test.runner.AndroidJUnitRunner;

public class VisualMutationTestRunner extends AndroidJUnitRunner {
    private static final String TAG_VISUAL_MUTATION_LAYER = "visual_mutation_layer";
    private static boolean enabled;
    private static VisualMutationLayer.Operator operator;

    @Override
    public void onCreate(Bundle arguments) {
        enabled = isVisualUiTestRun(arguments);
        operator = getOperator(arguments);
        super.onCreate(arguments);
    }

    @Override
    public void onStart() {
        Application application = (Application) getTargetContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                install(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
        super.onStart();
    }

    private static boolean isVisualUiTestRun(Bundle arguments) {
        if (arguments == null) {
            return false;
        }
        String packageName = arguments.getString("package");
        String className = arguments.getString("class");
        return isVisualUiTest(packageName) || isVisualUiTest(className);
    }

    private static boolean isVisualUiTest(String name) {
        return name != null && (name.startsWith("de.test.antennapod.ui")
                || name.startsWith("de.test.antennapod.dialogs"));
    }

    private static void install(Activity activity) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!enabled) {
                return;
            }
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(TAG_VISUAL_MUTATION_LAYER) != null) {
                return;
            }
            VisualMutationLayer layer = new VisualMutationLayer(activity, operator);
            layer.setTag(TAG_VISUAL_MUTATION_LAYER);
            layer.setClickable(false);
            layer.setFocusable(false);
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(layer, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        });
    }

    private static VisualMutationLayer.Operator getOperator(Bundle arguments) {
        if (arguments == null) {
            return VisualMutationLayer.Operator.TEXT_TRUNCATION;
        }
        String operatorName = arguments.getString("visualMutationOperator");
        if (operatorName == null) {
            return VisualMutationLayer.Operator.TEXT_TRUNCATION;
        }
        try {
            return VisualMutationLayer.Operator.valueOf(operatorName);
        } catch (IllegalArgumentException e) {
            return VisualMutationLayer.Operator.TEXT_TRUNCATION;
        }
    }
}
