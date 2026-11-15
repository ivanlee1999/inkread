package me.ash.reader.infrastructure.html;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the parser against the real minified app without AndroidX/JUnit test-library dependencies.
 * Keep the two fixtures equivalent to the ordinary AndroidJUnit4 ModernReadabilitySmokeTest.
 */
public final class ParserSmokeInstrumentation extends Instrumentation {
    private static final String TEST_CLASS =
            "me.ash.reader.infrastructure.html.ModernReadabilitySmokeTest";
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
    private int testCount = 2;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        if ("true".equals(this.arguments.getString("extractionReports"))) testCount = 3;
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
            if (testCount == 3) {
                runTest(3, "savedExtractionReportsSurviveMinifiedReopenAndExport",
                        this::savedExtractionReportsSurviveMinifiedReopenAndExport);
            }
            synchronized (resultLock) {
                if (!finished) {
                    require(completed == testCount, "Not all smoke tests completed");
                    Bundle results = new Bundle();
                    results.putString(REPORT_KEY_IDENTIFIER, getClass().getName());
                    results.putInt("numtests", testCount);
                    results.putString(REPORT_KEY_STREAMRESULT,
                            "\nTime: " + ((SystemClock.elapsedRealtime() - startedAt) / 1000.0)
                                    + (failures == 0 ? "\n\nOK (" + testCount + " tests)\n"
                                    : "\n\nFAILURES!!!\nTests run: " + testCount
                                            + ", Failures: " + failures + "\n"));
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
        status.putInt("numtests", testCount);
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
                + "<figure><img class=\"lazy\" data-original=\"../image?id=42&amp;format=avif\"><figcaption>延迟图片说明</figcaption></figure>"
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
        require(result.getContent().contains("src=\"https://example.org/image?id=42&amp;format=avif\""),
                "Extensionless lazy image URL was not retained and resolved");
        require(result.getTextContent().contains("延迟图片说明"), "Lazy image caption was lost");
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

    /** Exercises the real app's retained DTOs, Gson storage and coroutine ABI after R8. */
    private void savedExtractionReportsSurviveMinifiedReopenAndExport() {
        File owned = null;
        try {
            File cache = getTargetContext().getCacheDir().getCanonicalFile();
            owned = new File(cache, "extraction-report-smoke-" + UUID.randomUUID());
            require(!owned.exists() && owned.mkdir(), "Could not create isolated smoke directory");
            final File files = owned;
            Context context = new ContextWrapper(getTargetContext()) {
                @Override public File getFilesDir() { return files; }
            };
            TargetReportRuntime runtime = new TargetReportRuntime(getTargetContext().getClassLoader());
            long now = System.currentTimeMillis();
            Object before = runtime.model("ExtractionContentMetrics", 120, 1, 1);
            Object candidate = runtime.model("ExtractionContentMetrics", 10000, 23, 0);
            Object comparison = runtime.model("ExtractionSelectionSnapshot",
                    runtime.value("ExtractionSelectedSource", "FEED"),
                    runtime.value("ExtractionSelectionReason", "FEED_IS_BETTER"),
                    runtime.value("ExtractionQualityVeto", "IMAGE_LOSS"), before, candidate, before);
            Object attempt = runtime.model("ExtractionAttemptSnapshot", now, 15L,
                    runtime.value("ExtractionAttemptOrigin", "FRESH"),
                    runtime.value("ExtractionCacheState", "REFRESH_REQUESTED"), true,
                    runtime.value("ExtractionAttemptOutcome", "FEED_RETAINED"),
                    runtime.value("ExtractionFailureStage", "QUALITY"),
                    runtime.value("ExtractionDiagnosticReason", "QUALITY_REJECTED"),
                    200, 869084L, true, 5L, 10L, before, candidate, null, comparison, false, true);
            Class<?> buildConfig = runtime.type("me.ash.reader.BuildConfig");
            Object snapshot = runtime.model("ExtractionProblemSnapshot", 1, now,
                    buildConfig.getField("VERSION_NAME").get(null),
                    buildConfig.getField("VERSION_CODE").get(null), null,
                    Build.MODEL, Build.VERSION.SDK_INT,
                    "https://example.org/articles/report-smoke?utm_source=smoke#body", false,
                    Collections.singleton(runtime.value("ExtractionPrivacyOmission", "TITLE")),
                    runtime.value("ExtractionSourceMode", "LOCAL"),
                    runtime.value("ExtractionSourceMode", "SERVER"), false, before, before, attempt);
            int account = 987654321;
            String article = "private-report-article-SYNTHETIC_TITLE";
            String notes = "Photo missing on this public article.\nCookie: synthetic-cookie-value\n"
                    + "https://private.example.invalid/rss?token=synthetic-token-value\n"
                    + "<b>Visible note</b>";
            Object store = runtime.store(context);
            Object saved = runtime.suspend(store, "save", account, article, snapshot, notes);
            String id = (String) runtime.get(saved, "getId");
            String safeNotes = (String) runtime.get(saved, "getNotes");
            require(!id.isEmpty(), "Saved marker has no stable identity");
            require(safeNotes.contains("Photo missing"), "Safe user notes were lost");
            require(!safeNotes.contains("synthetic-cookie-value")
                            && !safeNotes.contains("synthetic-token-value")
                            && !safeNotes.contains("private.example.invalid")
                            && !safeNotes.contains("<b>"),
                    "Notes were not sanitized before persistence");

            Object reopenedStore = runtime.store(context);
            Object reopened = runtime.suspend(reopenedStore, "find", account, article);
            require(reopened != null, "Saved marker was not readable after recreating store");
            requireEquals(id, runtime.get(reopened, "getId"), "Marker identity after reopen");
            requireEquals(safeNotes, runtime.get(reopened, "getNotes"), "Notes after reopen");
            requireEquals(1, runtime.get(runtime.get(runtime.get(reopened, "getSnapshot"),
                    "getFeedMetrics"), "getImages"), "Persisted image metrics");

            File record = onlyJsonRecord(owned, owned.getCanonicalPath());
            JSONObject stored = new JSONObject(readBoundedRecord(record));
            requireEquals(1, stored.getInt("storageVersion"), "Stored wrapper schema");
            JSONObject storedSnapshot = stored.getJSONObject("report").getJSONObject("snapshot");
            requireEquals(1, storedSnapshot.getInt("schemaVersion"), "Persisted snapshot schema");
            requireEquals(1, storedSnapshot.getJSONObject("feedMetrics").getInt("images"),
                    "Literal reflected metric field");
            requireEquals("IMAGE_LOSS", storedSnapshot.getJSONObject("lastLocalAttempt")
                    .getJSONObject("readerComparison").getString("detail"),
                    "Literal reflected quality field");

            Class<?> formatterType = runtime.type(
                    "me.ash.reader.infrastructure.report.ExtractionProblemFormatter");
            Object formatter = formatterType.getField("INSTANCE").get(null);
            String exported = (String) formatterType.getMethod("json", reopened.getClass())
                    .invoke(formatter, reopened);
            JSONObject json = new JSONObject(exported);
            requireEquals(1, json.getInt("schema_version"), "Export schema");
            requireEquals("https://example.org/articles/report-smoke",
                    json.getString("public_article_url"), "Sanitized public article URL");
            require(json.getBoolean("url_redacted"), "URL redaction was not recorded");
            requireEquals("local", json.getString("requested_source_mode"), "Requested mode");
            requireEquals("server", json.getString("displayed_source_mode"), "Displayed mode");
            JSONObject exportedAttempt = json.getJSONObject("last_local_attempt");
            requireEquals("quality", exportedAttempt.getString("failure_stage"), "Actual failure stage");
            requireEquals(200, exportedAttempt.getInt("http_status"), "Observed HTTP status");
            JSONObject selection = exportedAttempt.getJSONObject("reader_comparison");
            requireEquals("image_loss", selection.getString("detail"), "Typed quality veto");
            requireEquals(1, selection.getJSONObject("before").getInt("editorial_images"),
                    "Before image count");
            requireEquals(0, selection.getJSONObject("candidate").getInt("editorial_images"),
                    "Candidate image count");
            requireEquals(23, selection.getJSONObject("candidate").getInt("paragraph_blocks"),
                    "Candidate paragraph count");
            rejectPrivateExportKeys(json);
            require(!exported.contains(id) && !exported.contains(article)
                            && !exported.contains("SYNTHETIC_TITLE")
                            && !exported.contains("synthetic-cookie-value")
                            && !exported.contains("synthetic-token-value")
                            && !exported.contains("private.example.invalid")
                            && !json.getString("notes").contains("<b>"),
                    "Local identities or private content escaped export");

            requireEquals(Boolean.TRUE, runtime.suspend(reopenedStore, "remove", account, id),
                    "Explicit marker removal");
            requireEquals(null, runtime.suspend(runtime.store(context), "find", account, article),
                    "Removed marker reappeared after recreating store");
        } catch (Throwable error) {
            if (error instanceof AssertionError) throw (AssertionError) error;
            throw new AssertionError("Minified extraction report persistence/export failed", error);
        } finally {
            if (owned != null && owned.exists()) {
                try {
                    deleteOwnedTree(owned, owned.getCanonicalPath());
                } catch (Exception error) {
                    throw new AssertionError("Could not clean isolated report smoke files", error);
                }
            }
        }
    }

    private static void rejectPrivateExportKeys(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                require(!key.equals("id") && !key.equals("account_id") && !key.equals("article_id")
                                && !key.equals("report_id") && !key.equals("accountId")
                                && !key.equals("articleId") && !key.equals("title")
                                && !key.equals("article_title") && !key.equals("body")
                                && !key.equals("html") && !key.equals("raw_html"),
                        "Private field included in report export: " + key);
                rejectPrivateExportKeys(object.get(key));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) rejectPrivateExportKeys(array.get(i));
        }
    }

    private static File onlyJsonRecord(File directory, String ownedPath) throws Exception {
        requireOwned(directory, ownedPath);
        File[] entries = directory.listFiles();
        require(entries != null && entries.length <= 8, "Unexpected smoke directory contents");
        File found = null;
        for (File entry : entries) {
            requireOwned(entry, ownedPath);
            File candidate = entry.isDirectory() ? onlyJsonRecord(entry, ownedPath)
                    : entry.getName().endsWith(".json") ? entry : null;
            if (candidate != null) {
                require(found == null, "Expected exactly one saved smoke record");
                found = candidate;
            }
        }
        return found;
    }

    private static String readBoundedRecord(File file) throws Exception {
        require(file != null && file.length() > 0 && file.length() <= 32768,
                "Saved smoke record size is invalid");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                require(bytes.size() + count <= 32768, "Saved smoke record exceeded read bound");
                bytes.write(buffer, 0, count);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void requireOwned(File file, String ownedPath) throws Exception {
        String canonical = file.getCanonicalPath();
        require(canonical.equals(file.getAbsolutePath())
                        && (canonical.equals(ownedPath) || canonical.startsWith(ownedPath + File.separator)),
                "Smoke path escaped its unique owned directory");
    }

    private static void deleteOwnedTree(File file, String ownedPath) throws Exception {
        requireOwned(file, ownedPath);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            require(children != null && children.length <= 8, "Unexpected smoke cleanup contents");
            for (File child : children) deleteOwnedTree(child, ownedPath);
        }
        require(file.delete(), "Could not remove owned smoke file");
    }

    /** All Kotlin interfaces and facade calls must come from the target APK's class loader. */
    private static final class TargetReportRuntime {
        private static final String REPORT_PACKAGE = "me.ash.reader.infrastructure.report.";
        private final ClassLoader loader;
        private final Class<?> continuation;
        private final Object emptyContext;
        private final Object suspended;
        private final Method throwOnFailure;
        private final Object dispatcher;

        TargetReportRuntime(ClassLoader loader) throws Exception {
            this.loader = loader;
            continuation = type("kotlin.coroutines.Continuation");
            emptyContext = type("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
            suspended = type("kotlin.coroutines.intrinsics.CoroutineSingletons")
                    .getField("COROUTINE_SUSPENDED").get(null);
            throwOnFailure = type("kotlin.ResultKt").getMethod("throwOnFailure", Object.class);
            // R8 may inline Kotlin's facade getter; the production provider is retained.
            Class<?> dispatcherModule = type("me.ash.reader.infrastructure.di.CoroutineDispatcherModule");
            dispatcher = dispatcherModule.getMethod("provideIODispatcher")
                    .invoke(dispatcherModule.getField("INSTANCE").get(null));
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object value(String name, String constant) throws Exception {
            return type(REPORT_PACKAGE + name).getField(constant).get(null);
        }

        Object model(String name, Object... arguments) throws Exception {
            Constructor<?> selected = null;
            for (Constructor<?> candidate : type(REPORT_PACKAGE + name).getDeclaredConstructors()) {
                if (!candidate.isSynthetic() && candidate.getParameterTypes().length == arguments.length) {
                    require(selected == null, "Ambiguous report model constructor: " + name);
                    selected = candidate;
                }
            }
            require(selected != null, "Missing retained report model constructor: " + name);
            selected.setAccessible(true);
            return selected.newInstance(arguments);
        }

        Object store(Context context) throws Exception {
            Class<?> syncType = type("me.ash.reader.infrastructure.rss.FeedSourceDirectorySync");
            return type(REPORT_PACKAGE + "ExtractionProblemReportStore").getConstructor(
                    Context.class, type("kotlinx.coroutines.CoroutineDispatcher"), syncType)
                    .newInstance(context, dispatcher, syncType.getConstructor().newInstance());
        }

        Object get(Object target, String name) throws Exception {
            require(target != null, "Missing persisted object for " + name);
            return target.getClass().getMethod(name).invoke(target);
        }

        Object suspend(Object target, String name, Object... arguments) throws Exception {
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicReference<Object> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Object completion = Proxy.newProxyInstance(loader, new Class<?>[]{continuation},
                    (proxy, method, values) -> {
                        if ("getContext".equals(method.getName())) return emptyContext;
                        if ("resumeWith".equals(method.getName())) {
                            try {
                                throwOnFailure.invoke(null, values[0]);
                                result.set(values[0]);
                            } catch (InvocationTargetException error) {
                                failure.set(error.getCause());
                            } catch (Throwable error) {
                                failure.set(error);
                            } finally {
                                resumed.countDown();
                            }
                            return null;
                        }
                        if ("toString".equals(method.getName())) return "ReportSmokeContinuation";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == values[0];
                        throw new AssertionError("Unexpected continuation call: " + method.getName());
                    });
            Method selected = null;
            for (Method candidate : target.getClass().getMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getName().equals(name) && parameters.length == arguments.length + 1
                        && parameters[parameters.length - 1] == continuation) {
                    require(selected == null, "Ambiguous suspend store method: " + name);
                    selected = candidate;
                }
            }
            require(selected != null, "Missing suspend store method: " + name);
            Object[] actual = new Object[arguments.length + 1];
            System.arraycopy(arguments, 0, actual, 0, arguments.length);
            actual[arguments.length] = completion;
            Object immediate = selected.invoke(target, actual);
            if (immediate != suspended) return immediate;
            require(resumed.await(30, TimeUnit.SECONDS), "Suspend store operation exceeded 30 seconds");
            if (failure.get() != null) throw new AssertionError("Suspend store operation failed", failure.get());
            return result.get();
        }
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
