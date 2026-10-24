package me.ash.reader.infrastructure.html;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the parser against the real minified app without AndroidX/JUnit test-library dependencies.
 * Keep the two fixtures equivalent to the ordinary AndroidJUnit4 ModernReadabilitySmokeTest.
 */
public final class ParserSmokeInstrumentation extends Instrumentation {
    private static final String TEST_CLASS =
            "me.ash.reader.infrastructure.html.ModernReadabilitySmokeTest";
    private static final int TEST_COUNT = 2;
    private static final long RUN_TIMEOUT_MILLIS = 120_000L;
    private final CountDownLatch applicationCreated = new CountDownLatch(1);
    private final Object resultLock = new Object();
    private volatile String applicationClass;
    private long startedAt;
    private Bundle arguments;
    private Thread watchdog;
    // All result state is protected by resultLock, including watchdog termination.
    private boolean finished;
    private String activeTest;
    private int activeIndex;
    private int completed;
    private int failures;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        startedAt = SystemClock.elapsedRealtime();
        start();
    }

    @Override
    public void callApplicationOnCreate(Application application) {
        // Never replace or suppress the production Application or any startup failure.
        super.callApplicationOnCreate(application);
        if (getTargetContext().getPackageName().equals(application.getPackageName())) {
            applicationClass = application.getClass().getName();
            applicationCreated.countDown();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        watchdog = new Thread(() -> {
            try {
                Thread.sleep(RUN_TIMEOUT_MILLIS);
                abort(new AssertionError("Parser smoke exceeded its 120 second deadline"));
            } catch (InterruptedException expectedAfterCompletion) {
                Thread.currentThread().interrupt();
            }
        }, "ParserSmokeWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String selectedClass = arguments.getString("class");
            require(selectedClass == null || TEST_CLASS.equals(selectedClass),
                    "Unsupported smoke test filter: " + selectedClass);
            require(!"true".equals(arguments.getString("log")),
                    "Parser smoke requires execution, not test discovery only");
            require(applicationCreated.await(60, TimeUnit.SECONDS),
                    "Target Application.onCreate did not complete");
            requireEquals("me.ash.reader.infrastructure.android.AndroidApp", applicationClass,
                    "The production Application must run");
            // This runs on Instrumentation's worker thread, never the app main thread.
            waitForIdleSync();
            runTest(1, "bundledEngineExtractsChineseShortArticleAndRelativeImage",
                    this::bundledEngineExtractsChineseShortArticleAndRelativeImage);
            runTest(2, "instructionLimitWorksOnDevice", this::instructionLimitWorksOnDevice);
            synchronized (resultLock) {
                if (!finished) {
                    require(completed == TEST_COUNT, "Not all parser tests completed");
                    Bundle results = new Bundle();
                    results.putString(REPORT_KEY_IDENTIFIER, getClass().getName());
                    results.putInt("numtests", TEST_COUNT);
                    results.putString(REPORT_KEY_STREAMRESULT,
                            "\nTime: " + ((SystemClock.elapsedRealtime() - startedAt) / 1000.0)
                                    + (failures == 0 ? "\n\nOK (2 tests)\n"
                                    : "\n\nFAILURES!!!\nTests run: 2, Failures: " + failures + "\n"));
                    finishOnce(Activity.RESULT_OK, results);
                }
            }
        } catch (Throwable error) {
            abort(error);
        }
    }

    private void runTest(int index, String name, Runnable test) {
        synchronized (resultLock) {
            if (finished) return;
            activeIndex = index;
            activeTest = name;
            Bundle status = statusBundle(index, name);
            status.putString(REPORT_KEY_STREAMRESULT, "\n" + TEST_CLASS + "#" + name + ":");
            sendStatus(1, status); // Standard instrumentation test-start protocol.
        }
        Throwable failure = null;
        try {
            test.run();
        } catch (Throwable error) {
            failure = error;
        }
        synchronized (resultLock) {
            if (finished) return;
            Bundle status = statusBundle(index, name);
            if (failure == null) {
                status.putString(REPORT_KEY_STREAMRESULT, ".");
                sendStatus(0, status);
            } else {
                failures++;
                String trace = stackTrace(failure);
                status.putString("stack", trace);
                status.putString(REPORT_KEY_STREAMRESULT, "\n" + trace);
                sendStatus(failure instanceof AssertionError ? -2 : -1, status);
            }
            completed++;
            activeTest = null;
        }
    }

    private Bundle statusBundle(int index, String name) {
        Bundle status = new Bundle();
        status.putString(REPORT_KEY_IDENTIFIER, getClass().getName());
        status.putString("class", TEST_CLASS);
        status.putString("test", name);
        status.putInt("current", index);
        status.putInt("numtests", TEST_COUNT);
        return status;
    }

    private void abort(Throwable error) {
        synchronized (resultLock) {
            if (finished) return;
            String trace = stackTrace(error);
            if (activeTest != null) {
                Bundle status = statusBundle(activeIndex, activeTest);
                status.putString("stack", trace);
                status.putString(REPORT_KEY_STREAMRESULT, "\n" + trace);
                sendStatus(-1, status);
            }
            Bundle results = new Bundle();
            results.putString("shortMsg", error.toString());
            results.putString(REPORT_KEY_STREAMRESULT, "\nParser smoke aborted:\n" + trace);
            finishOnce(Activity.RESULT_CANCELED, results);
        }
    }

    private void finishOnce(int resultCode, Bundle results) {
        finished = true;
        if (watchdog != null && watchdog != Thread.currentThread()) watchdog.interrupt();
        finish(resultCode, results);
    }

    private static ModernReadability.Article extract(
            ModernReadability parser, String html, String url) {
        // Use the app's retained Kotlin default bridge, not a second APK's Kotlin facade/callback.
        // Its mask 4 selects exactly the same default cancellation callback as the JUnit fixture.
        Method bridge = null;
        for (Method candidate : ModernReadability.class.getDeclaredMethods()) {
            if ("extract$default".equals(candidate.getName())
                    && candidate.getParameterTypes().length == 6
                    && Modifier.isStatic(candidate.getModifiers())) {
                require(bridge == null, "Ambiguous parser default bridge");
                bridge = candidate;
            }
        }
        require(bridge != null, "Missing retained parser default bridge");
        try {
            return (ModernReadability.Article) bridge.invoke(null, parser, html, url, null, 4, null);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("Unexpected checked parser failure", cause);
        } catch (IllegalAccessException error) {
            throw new AssertionError("Parser default bridge is inaccessible", error);
        }
    }

    private void bundledEngineExtractsChineseShortArticleAndRelativeImage() {
        String html = "<html lang=\"zh\"><head><title>中文短文章</title>"
                + "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\","
                + "\"@type\":\"NewsArticle\",\"headline\":\"中文短文章\","
                + "\"author\":{\"@type\":\"Person\",\"name\":\"测试作者\"}}</script>"
                + "</head><body><article><h1>中文短文章</h1>"
                + "<p>第一段：这是一篇中文短文章，包含说明和标点符号。图片应该正确保留。</p>"
                + "<figure><img src=\"../images/photo.jpg\"><figcaption>图片说明</figcaption></figure>"
                + "<p>最后一段：短文章不应该因为字数少而丢失，原有段落顺序需要保留。</p>"
                + "<script>throw new Error(\"Publisher script must not execute\");</script>"
                + "</article></body></html>";
        ModernReadability.Article result = extract(
                new ModernReadability(), html, "https://example.org/news/story");
        require(result != null, "Parser returned no article");
        requireEquals("中文短文章", result.getTitle(), "Chinese title");
        requireEquals("测试作者", result.getByline(), "JSON-LD author");
        requireEquals("zh", result.getLang(), "Article language");
        int first = result.getTextContent().indexOf("第一段");
        require(first >= 0, "Missing first Chinese paragraph");
        require(result.getTextContent().indexOf("最后一段") > first, "Paragraph order changed");
        require(!result.getContent().contains("Publisher script"), "Publisher script retained");
        require(result.getContent().contains("src=\"https://example.org/images/photo.jpg\""),
                "Relative image URL was not resolved");
    }

    private void instructionLimitWorksOnDevice() {
        ModernReadability.Limits defaults = new ModernReadability.Limits();
        ModernReadability.Limits limits = new ModernReadability.Limits(
                defaults.getMaxInputBytes(), defaults.getMaxNodes(), defaults.getMaxDepth(),
                defaults.getMaxMillis(), 1L, defaults.getMaxOutputChars());
        StringBuilder html = new StringBuilder("<html><body><article>");
        for (int i = 0; i < 20; i++) html.append("<p>Useful article content.</p>");
        html.append("</article></body></html>");
        try {
            extract(new ModernReadability(limits), html.toString(), "https://example.org");
        } catch (ModernReadability.ExtractionException expected) {
            requireEquals(ModernReadability.Failure.INSTRUCTION_LIMIT, expected.getReason(),
                    "Instruction budget failure reason");
            return;
        }
        throw new AssertionError("Expected bounded instruction failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireEquals(Object expected, Object actual, String message) {
        require(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static String stackTrace(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
