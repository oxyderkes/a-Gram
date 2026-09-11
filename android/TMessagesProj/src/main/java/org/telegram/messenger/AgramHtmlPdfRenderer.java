/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renders Agram's local messages.html export to an A4 PDF without using the network. */
public final class AgramHtmlPdfRenderer {

    private static final String TAG = "AgramHtmlPdf";
    private static final String BRIDGE_NAME = "AgramPdfReady";
    private static final byte[] EMPTY_RESPONSE = new byte[0];

    // PdfDocument dimensions are PDF points (1/72 inch). These are A4 rounded to whole points.
    private static final int PAGE_WIDTH_POINTS = 595;
    private static final int PAGE_HEIGHT_POINTS = 842;
    private static final int HORIZONTAL_MARGIN_POINTS = 28; // approximately 10 mm
    private static final int VERTICAL_MARGIN_POINTS = 34; // approximately 12 mm
    private static final int CONTENT_WIDTH_POINTS =
            PAGE_WIDTH_POINTS - HORIZONTAL_MARGIN_POINTS * 2;
    private static final int CONTENT_HEIGHT_POINTS =
            PAGE_HEIGHT_POINTS - VERTICAL_MARGIN_POINTS * 2;
    private static final float CSS_PIXELS_PER_INCH = 96.0f;
    private static final float PDF_POINTS_PER_INCH = 72.0f;

    private static final long PAGE_LOAD_TIMEOUT_MS = 30_000L;
    private static final long RESOURCE_WAIT_TIMEOUT_MS = 15_000L;
    private static final long MIN_RENDER_TIMEOUT_MS = 2 * 60_000L;
    private static final long MAX_RENDER_TIMEOUT_MS = 30 * 60_000L;
    private static final long RENDER_TIMEOUT_PER_PAGE_MS = 5_000L;
    private static final long PAGE_IMAGE_WAIT_TIMEOUT_MS = 3_500L;
    private static final long PDF_WRITE_TIMEOUT_MS = 10 * 60_000L;
    // PdfDocument retains finished pages until writeTo(); require date-splitting beyond this bound.
    private static final int MAX_PDF_PAGES = 150;

    private static final int STATE_QUEUED = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_WAITING_FOR_RESOURCES = 2;
    private static final int STATE_RENDERING = 3;
    private static final int STATE_WRITING = 4;
    private static final int STATE_FINISHED = 5;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();
    private static Job activeJob;

    private AgramHtmlPdfRenderer() {
    }

    public interface Callback {
        void onSuccess();

        void onError(String message);
    }

    public static final class RenderTask {
        private final Job job;

        private RenderTask(Job job) {
            this.job = job;
        }

        public void cancel() {
            job.requestCancel();
        }
    }

    /**
     * Queues a PDF render. Callbacks are delivered once on the main thread. A cancelled task does
     * not receive a callback.
     */
    public static RenderTask render(File htmlFile, File outputFile, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        Job job = new Job(htmlFile, outputFile, callback);
        MAIN_HANDLER.post(() -> enqueue(job));
        return new RenderTask(job);
    }

    private static void enqueue(Job job) {
        if (job.finished) {
            return;
        }
        if (job.cancelRequested.get()) {
            job.cancelOnMain();
            return;
        }
        JOBS.addLast(job);
        startNext();
    }

    private static void startNext() {
        if (activeJob != null) {
            return;
        }
        while (!JOBS.isEmpty()) {
            Job job = JOBS.removeFirst();
            if (job.finished) {
                continue;
            }
            activeJob = job;
            job.start();
            return;
        }
    }

    private static final class Job {
        private final File requestedHtmlFile;
        private final File requestedOutputFile;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean abortWrite = new AtomicBoolean();
        private final Object outputStreamLock = new Object();

        private Callback callback;
        private File htmlFile;
        private File outputFile;
        private File temporaryOutputFile;
        private File allowedRoot;
        private boolean outputMayBeDeleted;
        private boolean finished;
        private int state = STATE_QUEUED;
        private String deferredWriteError;

        private RenderWebView webView;
        private ReadyBridge readyBridge;
        private PdfDocument pdfDocument;
        private Runnable timeoutRunnable;
        private Runnable pageImageTimeoutRunnable;

        private int viewportWidthPx;
        private int viewportHeightPx;
        private long documentHeightPx;
        private int pageCount;
        private int nextPageIndex;
        private long pendingPageTopPx;
        private int pageResourceToken;
        private boolean waitingForPageResources;

        private volatile FileOutputStream activeOutputStream;
        private volatile Thread writerThread;

        private Job(File htmlFile, File outputFile, Callback callback) {
            requestedHtmlFile = htmlFile;
            requestedOutputFile = outputFile;
            this.callback = callback;
        }

        private void requestCancel() {
            if (cancelRequested.compareAndSet(false, true)) {
                MAIN_HANDLER.post(this::cancelOnMain);
            }
        }

        private void cancelOnMain() {
            if (finished) {
                return;
            }
            if (state == STATE_WRITING && writerThread != null) {
                callback = null;
                clearTimeout();
                abortBackgroundWrite();
                return;
            }
            if (state == STATE_QUEUED) {
                JOBS.remove(this);
            }
            finish(false, null, true);
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void start() {
            if (cancelRequested.get()) {
                cancelOnMain();
                return;
            }
            try {
                if (requestedHtmlFile == null || !requestedHtmlFile.isFile()
                        || !requestedHtmlFile.canRead()) {
                    fail("Не найден HTML-файл экспорта");
                    return;
                }
                htmlFile = requestedHtmlFile.getCanonicalFile();
                if (!"messages.html".equalsIgnoreCase(htmlFile.getName())) {
                    fail("Поддерживается только локальный файл messages.html");
                    return;
                }
                if (requestedOutputFile == null) {
                    fail("Не указан файл PDF");
                    return;
                }
                outputFile = requestedOutputFile.getCanonicalFile();
                if (htmlFile.equals(outputFile)) {
                    fail("HTML и PDF должны быть разными файлами");
                    return;
                }
                outputMayBeDeleted = true;
                allowedRoot = htmlFile.getParentFile().getCanonicalFile();
                File outputParent = outputFile.getParentFile();
                if (outputParent == null
                        || (!outputParent.isDirectory() && !outputParent.mkdirs())
                        || !outputParent.isDirectory()) {
                    fail("Не удалось подготовить папку для PDF");
                    return;
                }
                if (outputFile.exists() && !outputFile.delete()) {
                    fail("Не удалось перезаписать файл PDF");
                    return;
                }
                temporaryOutputFile = new File(outputParent,
                        outputFile.getName() + ".part").getCanonicalFile();
                if (temporaryOutputFile.equals(htmlFile)
                        || (temporaryOutputFile.exists() && !temporaryOutputFile.delete())) {
                    fail("Не удалось подготовить временный файл PDF");
                    return;
                }

                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    fail("Приложение ещё не готово к созданию PDF");
                    return;
                }
                Context applicationContext = context.getApplicationContext();
                if (applicationContext != null) {
                    context = applicationContext;
                }
                webView = new RenderWebView(context);
                webView.setNetworkAvailable(false);
                webView.setBackgroundColor(Color.WHITE);
                webView.setHorizontalScrollBarEnabled(false);
                webView.setVerticalScrollBarEnabled(false);
                webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                // The layer is only one A4 content viewport high; no full-document bitmap exists.
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                settings.setAllowContentAccess(false);
                settings.setAllowFileAccess(true);
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
                settings.setBlockNetworkLoads(true);
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
                settings.setDomStorageEnabled(false);
                settings.setDatabaseEnabled(false);
                settings.setGeolocationEnabled(false);
                settings.setLoadsImagesAutomatically(true);
                settings.setMediaPlaybackRequiresUserGesture(true);
                settings.setOffscreenPreRaster(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(false);
                settings.setTextZoom(100);

                layoutViewport(context);
                readyBridge = new ReadyBridge(this);
                webView.addJavascriptInterface(readyBridge, BRIDGE_NAME);
                webView.setWebViewClient(createWebViewClient());
                state = STATE_LOADING;
                scheduleTimeout(PAGE_LOAD_TIMEOUT_MS,
                        () -> fail("Истекло время загрузки HTML для PDF"));
                webView.loadUrl(Uri.fromFile(htmlFile).toString());
            } catch (Throwable e) {
                Log.e(TAG, "Unable to start PDF rendering", e);
                fail("Не удалось запустить создание PDF");
            }
        }

        private void layoutViewport(Context context) {
            float density = Math.max(1.0f,
                    context.getResources().getDisplayMetrics().density);
            float contentWidthCssPx = CONTENT_WIDTH_POINTS
                    * CSS_PIXELS_PER_INCH / PDF_POINTS_PER_INCH;
            viewportWidthPx = Math.max(1, Math.round(contentWidthCssPx * density));
            viewportHeightPx = Math.max(1, Math.round(viewportWidthPx
                    * (CONTENT_HEIGHT_POINTS / (float) CONTENT_WIDTH_POINTS)));
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    viewportWidthPx, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    viewportHeightPx, View.MeasureSpec.EXACTLY);
            webView.measure(widthSpec, heightSpec);
            webView.layout(0, 0, viewportWidthPx, viewportHeightPx);
        }

        private WebViewClient createWebViewClient() {
            return new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    boolean allowed = isAllowedLocalUrl(request.getUrl());
                    if (!allowed && request.isForMainFrame()) {
                        MAIN_HANDLER.post(() -> fail("HTML попытался открыть внешний адрес"));
                    }
                    return !allowed;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                  WebResourceRequest request) {
                    if (isAllowedLocalUrl(request.getUrl())) {
                        return null;
                    }
                    return blockedResponse();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (state == STATE_LOADING && isExpectedDocumentUrl(url)) {
                        waitForResources();
                    }
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request.isForMainFrame()) {
                        CharSequence detail = error == null ? null : error.getDescription();
                        MAIN_HANDLER.post(() -> fail(withDetail(
                                "Не удалось загрузить HTML для PDF", detail)));
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    fail("Системный компонент WebView завершил работу");
                    return true;
                }
            };
        }

        private boolean isExpectedDocumentUrl(String url) {
            try {
                Uri uri = url == null ? null : Uri.parse(url);
                return uri != null && htmlFile != null
                        && "file".equalsIgnoreCase(uri.getScheme())
                        && htmlFile.equals(fileFromUri(uri));
            } catch (Throwable ignore) {
                return false;
            }
        }

        private boolean isAllowedLocalUrl(Uri uri) {
            if (uri == null) {
                return false;
            }
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if ("data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme)
                    || "about".equalsIgnoreCase(scheme)) {
                return true;
            }
            if (!"file".equalsIgnoreCase(scheme) || allowedRoot == null) {
                return false;
            }
            try {
                File file = fileFromUri(uri);
                String rootPath = allowedRoot.getPath();
                String filePath = file.getPath();
                return filePath.equals(rootPath)
                        || filePath.startsWith(rootPath + File.separator);
            } catch (Throwable ignore) {
                return false;
            }
        }

        private File fileFromUri(Uri uri) throws IOException {
            String path = uri.getPath();
            if (path == null) {
                throw new IOException("File URL has no path");
            }
            return new File(path).getCanonicalFile();
        }

        private WebResourceResponse blockedResponse() {
            return new WebResourceResponse("text/plain", "UTF-8",
                    new ByteArrayInputStream(EMPTY_RESPONSE));
        }

        @SuppressLint("SetJavaScriptEnabled")
        private void waitForResources() {
            if (finished || state != STATE_LOADING || webView == null) {
                return;
            }
            clearTimeout();
            state = STATE_WAITING_FOR_RESOURCES;
            scheduleTimeout(RESOURCE_WAIT_TIMEOUT_MS, () -> preparePdf(null));
            try {
                webView.getSettings().setJavaScriptEnabled(true);
                webView.evaluateJavascript(resourceWaitScript(), null);
            } catch (Throwable e) {
                Log.e(TAG, "Unable to wait for HTML resources", e);
                // The native timeout is an intentional fallback to rendering the loaded document.
            }
        }

        private String resourceWaitScript() {
            return "(function(){"
                    + "var sent=false;"
                    + "function metrics(){var d=document.documentElement,b=document.body;"
                    + "var h=Math.max(d?d.scrollHeight:0,d?d.offsetHeight:0,d?d.clientHeight:0,"
                    + "b?b.scrollHeight:0,b?b.offsetHeight:0,b?b.clientHeight:0);"
                    + "var w=Math.max(1,d?d.clientWidth:0,window.innerWidth||0);"
                    + "return String(Math.ceil(h))+'|'+String(w);}"
                    + "function done(){if(sent)return;sent=true;"
                    + "requestAnimationFrame(function(){requestAnimationFrame(function(){"
                    + "try{window." + BRIDGE_NAME + ".ready(metrics());}catch(e){}"
                    + "});});}"
                    + "try{var style=document.getElementById('agram_pdf_style');"
                    + "if(!style){style=document.createElement('style');style.id='agram_pdf_style';"
                    + "style.textContent='html,body{background:#fff!important;}"
                    + "*,*:before,*:after{box-sizing:border-box;}"
                    + ".page_header{position:static!important;border-bottom:1px solid #ddd!important;}"
                    + ".page_header .content,.page_body,.export_notice{width:100%!important;}"
                    + ".page_body{padding-bottom:0!important;}"
                    + ".media img.photo{width:min(100%,520px)!important;height:auto!important;"
                    + "aspect-ratio:4/3!important;max-height:390px!important;object-fit:contain!important;object-position:left top!important;}"
                    + ".media img.sticker{width:180px!important;height:180px!important;"
                    + "max-width:180px!important;max-height:180px!important;object-fit:contain!important;}"
                    + ".media video,.media audio{display:none!important;}"
                    + ".video_pdf_note,.audio_pdf_note{display:block!important;}"
                    + ".text,.file_name,.sender{overflow-wrap:anywhere!important;word-break:break-word!important;}';"
                    + "(document.head||document.documentElement).appendChild(style);}"
                    + "var fonts=(document.fonts&&document.fonts.ready)"
                    + "?Promise.resolve(document.fonts.ready).catch(function(){}):Promise.resolve();"
                    + "Promise.race([fonts,new Promise(function(resolve){"
                    + "setTimeout(resolve,12000);})]).then(done,done);"
                    + "}catch(e){done();}"
                    + "})();";
        }

        private void onResourcesReady(String metrics) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onResourcesReady(metrics));
                return;
            }
            if (!finished && state == STATE_WAITING_FOR_RESOURCES) {
                preparePdf(metrics);
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private void preparePdf(String metrics) {
            if (finished || state != STATE_WAITING_FOR_RESOURCES || webView == null) {
                return;
            }
            if (cancelRequested.get()) {
                cancelOnMain();
                return;
            }
            clearTimeout();
            try {
                int widthSpec = View.MeasureSpec.makeMeasureSpec(
                        viewportWidthPx, View.MeasureSpec.EXACTLY);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(
                        viewportHeightPx, View.MeasureSpec.EXACTLY);
                webView.measure(widthSpec, heightSpec);
                webView.layout(0, 0, viewportWidthPx, viewportHeightPx);

                documentHeightPx = measureDocumentHeight(metrics);
                if (documentHeightPx <= 0) {
                    fail("Не удалось измерить высоту HTML");
                    return;
                }
                if (documentHeightPx > Integer.MAX_VALUE) {
                    fail("HTML слишком большой для системного WebView");
                    return;
                }
                long pages = (documentHeightPx + viewportHeightPx - 1L) / viewportHeightPx;
                if (pages <= 0 || pages > MAX_PDF_PAGES) {
                    fail("PDF превышает " + MAX_PDF_PAGES
                            + " страниц. Выберите меньший диапазон или формат HTML ZIP");
                    return;
                }
                pageCount = (int) pages;
                nextPageIndex = 0;
                pdfDocument = new PdfDocument();
                state = STATE_RENDERING;
                scheduleTimeout(renderTimeoutForPageCount(pageCount), () ->
                        fail("Истекло время отрисовки PDF"));
                MAIN_HANDLER.post(this::renderNextPage);
            } catch (Throwable e) {
                Log.e(TAG, "Unable to prepare PDF", e);
                fail("Не удалось подготовить PDF");
            }
        }

        private long measureDocumentHeight(String metrics) {
            long height = Math.max(viewportHeightPx, webView.getContentScrollRange());
            height = Math.max(height,
                    (long) Math.ceil(webView.getContentHeight() * webView.getScale()));
            if (metrics == null) {
                return height;
            }
            try {
                String[] values = metrics.split("\\|", -1);
                if (values.length == 2) {
                    double cssHeight = Double.parseDouble(values[0]);
                    double cssWidth = Double.parseDouble(values[1]);
                    if (cssHeight > 0.0 && cssWidth > 0.0
                            && Double.isFinite(cssHeight) && Double.isFinite(cssWidth)) {
                        double pxPerCssPixel = viewportWidthPx / cssWidth;
                        double measuredHeight = Math.ceil(cssHeight * pxPerCssPixel);
                        if (measuredHeight > Integer.MAX_VALUE) {
                            return (long) measuredHeight;
                        }
                        height = Math.max(height, (long) measuredHeight);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Unable to parse HTML dimensions", e);
            }
            return height;
        }

        private long renderTimeoutForPageCount(int pages) {
            long extra = Math.min(MAX_RENDER_TIMEOUT_MS - MIN_RENDER_TIMEOUT_MS,
                    pages * RENDER_TIMEOUT_PER_PAGE_MS);
            return MIN_RENDER_TIMEOUT_MS + extra;
        }

        private void renderNextPage() {
            if (finished || state != STATE_RENDERING) {
                return;
            }
            if (cancelRequested.get()) {
                cancelOnMain();
                return;
            }
            if (nextPageIndex >= pageCount) {
                writePdf();
                return;
            }
            pendingPageTopPx = (long) nextPageIndex * viewportHeightPx;
            long maximumScroll = Math.max(0L, documentHeightPx - viewportHeightPx);
            int requestedScroll = (int) Math.min(pendingPageTopPx, maximumScroll);
            webView.scrollTo(0, requestedScroll);
            webView.computeScroll();
            webView.invalidate();
            int expectedPageIndex = nextPageIndex;
            int expectedToken = ++pageResourceToken;
            waitingForPageResources = true;
            schedulePageImageTimeout(expectedPageIndex, expectedToken);
            try {
                webView.evaluateJavascript(
                        pageImageWaitScript(expectedPageIndex, expectedToken), null);
            } catch (Throwable e) {
                Log.e(TAG, "Unable to wait for page images", e);
            }
        }

        private String pageImageWaitScript(int pageIndex, int token) {
            return "(function(){var page=" + pageIndex + ",token=" + token + ",sent=false;"
                    + "function done(){if(sent)return;sent=true;"
                    + "requestAnimationFrame(function(){requestAnimationFrame(function(){"
                    + "try{window." + BRIDGE_NAME + ".pageReady(page,token);}catch(e){}"
                    + "});});}"
                    + "try{var vw=window.innerWidth||document.documentElement.clientWidth;"
                    + "var vh=window.innerHeight||document.documentElement.clientHeight;"
                    + "var imgs=Array.prototype.slice.call(document.images||[]).filter(function(img){"
                    + "var r=img.getBoundingClientRect();return r.bottom>=-1&&r.top<=vh+1"
                    + "&&r.right>=-1&&r.left<=vw+1;});"
                    + "var waits=imgs.map(function(img){img.loading='eager';img.removeAttribute('loading');"
                    + "var src=img.getAttribute('src');if(src&&!img.complete){img.src=src;}"
                    + "return new Promise(function(resolve){function decoded(){"
                    + "if(img.decode){img.decode().then(resolve,resolve);}else{resolve();}}"
                    + "if(img.complete){decoded();}else{"
                    + "img.addEventListener('load',decoded,{once:true});"
                    + "img.addEventListener('error',resolve,{once:true});}});});"
                    + "Promise.race([Promise.all(waits),new Promise(function(resolve){"
                    + "setTimeout(resolve,2500);})]).then(done,done);"
                    + "}catch(e){done();}})();";
        }

        private void schedulePageImageTimeout(int pageIndex, int token) {
            clearPageImageTimeout();
            pageImageTimeoutRunnable = () -> {
                pageImageTimeoutRunnable = null;
                onPageResourcesReady(pageIndex, token);
            };
            MAIN_HANDLER.postDelayed(pageImageTimeoutRunnable, PAGE_IMAGE_WAIT_TIMEOUT_MS);
        }

        private void clearPageImageTimeout() {
            if (pageImageTimeoutRunnable != null) {
                MAIN_HANDLER.removeCallbacks(pageImageTimeoutRunnable);
                pageImageTimeoutRunnable = null;
            }
        }

        private void onPageResourcesReady(int pageIndex, int token) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onPageResourcesReady(pageIndex, token));
                return;
            }
            if (finished || state != STATE_RENDERING || !waitingForPageResources
                    || nextPageIndex != pageIndex || pageResourceToken != token) {
                return;
            }
            waitingForPageResources = false;
            clearPageImageTimeout();
            awaitVisualState(pageIndex);
        }

        private void awaitVisualState(int expectedPageIndex) {
            try {
                webView.postVisualStateCallback(expectedPageIndex + 1L,
                        new WebView.VisualStateCallback() {
                            @Override
                            public void onComplete(long requestId) {
                                MAIN_HANDLER.post(() -> drawPageIfCurrent(expectedPageIndex));
                            }
                        });
            } catch (Throwable e) {
                Log.e(TAG, "Unable to await WebView visual state", e);
            }
            // An unattached WebView may not report visual-state completion on every provider.
            MAIN_HANDLER.postDelayed(() -> drawPageIfCurrent(expectedPageIndex), 75L);
        }

        private void drawPageIfCurrent(int expectedPageIndex) {
            if (!finished && state == STATE_RENDERING
                    && nextPageIndex == expectedPageIndex) {
                drawCurrentPage();
            }
        }

        private void drawCurrentPage() {
            if (finished || state != STATE_RENDERING) {
                return;
            }
            if (cancelRequested.get()) {
                cancelOnMain();
                return;
            }
            PdfDocument.Page page = null;
            try {
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, nextPageIndex + 1).create();
                page = pdfDocument.startPage(pageInfo);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                int saveCount = canvas.save();
                canvas.clipRect(HORIZONTAL_MARGIN_POINTS, VERTICAL_MARGIN_POINTS,
                        PAGE_WIDTH_POINTS - HORIZONTAL_MARGIN_POINTS,
                        PAGE_HEIGHT_POINTS - VERTICAL_MARGIN_POINTS);
                canvas.translate(HORIZONTAL_MARGIN_POINTS, VERTICAL_MARGIN_POINTS);
                float scale = CONTENT_WIDTH_POINTS / (float) viewportWidthPx;
                canvas.scale(scale, scale);
                long skippedTop = Math.max(0L, pendingPageTopPx - webView.getScrollY());
                if (skippedTop != 0L) {
                    canvas.translate(0.0f, -skippedTop);
                }
                webView.draw(canvas);
                canvas.restoreToCount(saveCount);
                pdfDocument.finishPage(page);
                page = null;
                nextPageIndex++;
                MAIN_HANDLER.post(this::renderNextPage);
            } catch (Throwable e) {
                if (page != null && pdfDocument != null) {
                    try {
                        pdfDocument.finishPage(page);
                    } catch (Throwable ignored) {
                        // The document is closed by finish().
                    }
                }
                Log.e(TAG, "Unable to draw PDF page", e);
                fail("Не удалось отрисовать страницу PDF");
            }
        }

        private void writePdf() {
            if (finished || state != STATE_RENDERING || pdfDocument == null) {
                return;
            }
            clearTimeout();
            state = STATE_WRITING;
            destroyWebView();
            final PdfDocument documentToWrite = pdfDocument;
            pdfDocument = null;
            scheduleTimeout(PDF_WRITE_TIMEOUT_MS, () -> {
                abortBackgroundWrite();
                fail("Истекло время записи PDF");
            });

            Thread thread = new Thread(() -> writePdfInBackground(documentToWrite),
                    "AgramPdfWriter");
            writerThread = thread;
            try {
                thread.start();
            } catch (Throwable e) {
                writerThread = null;
                try {
                    documentToWrite.close();
                } catch (Throwable ignored) {
                    // Nothing else can use this document.
                }
                Log.e(TAG, "Unable to start PDF writer", e);
                fail("Не удалось запустить запись PDF");
            }
        }

        private void writePdfInBackground(PdfDocument documentToWrite) {
            FileOutputStream stream = null;
            boolean success = false;
            String error = "Не удалось записать PDF";
            try {
                if (cancelRequested.get() || abortWrite.get()) {
                    throw new IOException("PDF write cancelled");
                }
                stream = new FileOutputStream(temporaryOutputFile, false);
                synchronized (outputStreamLock) {
                    activeOutputStream = stream;
                }
                if (cancelRequested.get() || abortWrite.get()) {
                    throw new IOException("PDF write cancelled");
                }
                documentToWrite.writeTo(stream);
                stream.flush();
                stream.close();
                synchronized (outputStreamLock) {
                    if (activeOutputStream == stream) {
                        activeOutputStream = null;
                    }
                }
                if (cancelRequested.get() || abortWrite.get()) {
                    throw new IOException("PDF write cancelled");
                }
                if ((outputFile.exists() && !outputFile.delete())
                        || !temporaryOutputFile.renameTo(outputFile)) {
                    throw new IOException("Unable to publish completed PDF");
                }
                success = !cancelRequested.get() && !abortWrite.get()
                        && outputFile.isFile() && outputFile.length() > 0;
                if (!success && !cancelRequested.get() && !abortWrite.get()) {
                    error = "Системный компонент не записал PDF";
                }
            } catch (Throwable e) {
                if (!cancelRequested.get() && !abortWrite.get()) {
                    Log.e(TAG, "Unable to write PDF", e);
                }
            } finally {
                synchronized (outputStreamLock) {
                    if (activeOutputStream == stream) {
                        activeOutputStream = null;
                    }
                }
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Unable to close PDF output", e);
                    }
                }
                try {
                    documentToWrite.close();
                } catch (Throwable e) {
                    Log.e(TAG, "Unable to close PDF document", e);
                }
                writerThread = null;
            }

            boolean completed = success && !cancelRequested.get() && !abortWrite.get();
            if (!completed) {
                deleteOutputSafely();
            }
            String completedError = error;
            MAIN_HANDLER.post(() -> pdfWriteFinished(completed, completedError));
        }

        private void pdfWriteFinished(boolean success, String error) {
            if (finished) {
                if (!success) {
                    deleteOutputSafely();
                }
                return;
            }
            if (state != STATE_WRITING) {
                return;
            }
            clearTimeout();
            if (cancelRequested.get()) {
                cancelOnMain();
            } else if (deferredWriteError != null) {
                String message = deferredWriteError;
                deferredWriteError = null;
                finish(false, message, false);
            } else if (success) {
                finish(true, null, false);
            } else {
                fail(error);
            }
        }

        private void abortBackgroundWrite() {
            abortWrite.set(true);
            FileOutputStream stream;
            synchronized (outputStreamLock) {
                stream = activeOutputStream;
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Unable to abort PDF output", e);
                }
            }
            Thread thread = writerThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }

        private void fail(String message) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> fail(message));
                return;
            }
            if (state == STATE_WRITING && writerThread != null) {
                deferredWriteError = message;
                clearTimeout();
                abortBackgroundWrite();
                return;
            }
            if (cancelRequested.get()) {
                cancelOnMain();
            } else {
                finish(false, message, false);
            }
        }

        private void scheduleTimeout(long delayMs, Runnable action) {
            clearTimeout();
            timeoutRunnable = () -> {
                timeoutRunnable = null;
                if (!finished) {
                    action.run();
                }
            };
            MAIN_HANDLER.postDelayed(timeoutRunnable, delayMs);
        }

        private void clearTimeout() {
            if (timeoutRunnable != null) {
                MAIN_HANDLER.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
        }

        private void finish(boolean success, String error, boolean cancelled) {
            if (finished) {
                return;
            }
            boolean wasWriting = state == STATE_WRITING;
            finished = true;
            state = STATE_FINISHED;
            clearTimeout();
            clearPageImageTimeout();
            waitingForPageResources = false;
            if (wasWriting && !success) {
                abortBackgroundWrite();
            }
            closePdfDocument();
            destroyWebView();

            if (!success) {
                deleteOutputSafely();
            }

            Callback resultCallback = callback;
            callback = null;
            JOBS.remove(this);
            if (activeJob == this) {
                activeJob = null;
                MAIN_HANDLER.post(AgramHtmlPdfRenderer::startNext);
            }

            if (!cancelled && resultCallback != null) {
                try {
                    if (success) {
                        resultCallback.onSuccess();
                    } else {
                        resultCallback.onError(error == null || error.trim().isEmpty()
                                ? "Не удалось создать PDF" : error);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "PDF callback failed", e);
                }
            }
        }

        private void closePdfDocument() {
            if (pdfDocument != null) {
                try {
                    pdfDocument.close();
                } catch (Throwable e) {
                    Log.e(TAG, "Unable to close PDF document", e);
                }
                pdfDocument = null;
            }
        }

        private void destroyWebView() {
            if (webView != null) {
                try {
                    webView.stopLoading();
                    webView.removeJavascriptInterface(BRIDGE_NAME);
                    webView.destroy();
                } catch (Throwable e) {
                    Log.e(TAG, "Unable to destroy PDF WebView", e);
                }
                webView = null;
            }
            readyBridge = null;
        }

        private void deleteOutputSafely() {
            File file = outputFile;
            if (!outputMayBeDeleted) {
                try {
                    if (requestedOutputFile == null) {
                        return;
                    }
                    file = requestedOutputFile.getCanonicalFile();
                    if (requestedHtmlFile != null
                            && file.equals(requestedHtmlFile.getCanonicalFile())) {
                        return;
                    }
                } catch (Throwable ignore) {
                    return;
                }
            }
            if (file != null && file.exists() && !file.delete()) {
                Log.e(TAG, "Unable to delete incomplete PDF");
            }
            File temporary = temporaryOutputFile;
            if (temporary != null && !temporary.equals(htmlFile)
                    && temporary.exists() && !temporary.delete()) {
                Log.e(TAG, "Unable to delete temporary PDF");
            }
        }

        private String withDetail(String prefix, CharSequence detail) {
            if (detail == null) {
                return prefix;
            }
            String value = detail.toString().trim();
            if (value.isEmpty()) {
                return prefix;
            }
            if (value.length() > 160) {
                value = value.substring(0, 160);
            }
            return prefix + ": " + value;
        }
    }

    private static final class RenderWebView extends WebView {
        private RenderWebView(Context context) {
            super(context);
        }

        private int getContentScrollRange() {
            return computeVerticalScrollRange();
        }
    }

    private static final class ReadyBridge {
        private final WeakReference<Job> jobReference;

        private ReadyBridge(Job job) {
            jobReference = new WeakReference<>(job);
        }

        @JavascriptInterface
        public void ready(String metrics) {
            Job job = jobReference.get();
            if (job != null) {
                MAIN_HANDLER.post(() -> job.onResourcesReady(metrics));
            }
        }

        @JavascriptInterface
        public void pageReady(int pageIndex, int token) {
            Job job = jobReference.get();
            if (job != null) {
                MAIN_HANDLER.post(() -> job.onPageResourcesReady(pageIndex, token));
            }
        }
    }
}
