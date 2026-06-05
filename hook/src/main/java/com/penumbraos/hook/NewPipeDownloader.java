package com.penumbraos.hook;

import com.penumbraos.shaded.okhttp3.Call;
import com.penumbraos.shaded.okhttp3.Callback;
import com.penumbraos.shaded.okhttp3.MediaType;
import com.penumbraos.shaded.okhttp3.OkHttpClient;
import com.penumbraos.shaded.okhttp3.RequestBody;

import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PipePipe's Downloader, OkHttp-based (PipePipe's executeAsync returns CancellableCall(okhttp3.Call),
 * so HttpURLConnection can't satisfy it). Written in Java on purpose: okhttp 4.x is a Kotlin lib and
 * Shadow does NOT rewrite Kotlin @Metadata, so compiling Kotlin against the SHADED okhttp breaks
 * extension/property resolution — Java resolves the real JVM methods regardless of metadata.
 * okhttp here is the relocated com.penumbraos.shaded.okhttp3 (shaded so full protobuf-java/okhttp
 * can't collide with ironman's protobuf-javalite/okhttp -> see [[humane-pipepipe-extractor]]).
 */
public final class NewPipeDownloader extends Downloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private com.penumbraos.shaded.okhttp3.Request build(final Request request) {
        final com.penumbraos.shaded.okhttp3.Request.Builder builder =
                new com.penumbraos.shaded.okhttp3.Request.Builder().url(request.url());

        for (final Map.Entry<String, List<String>> e : request.headers().entrySet()) {
            builder.removeHeader(e.getKey());
            for (final String v : e.getValue()) builder.addHeader(e.getKey(), v);
        }
        if (request.headers().get("User-Agent") == null) {
            builder.header("User-Agent", USER_AGENT);
        }

        final byte[] data = request.dataToSend();
        final String method = request.httpMethod();
        RequestBody body = null;
        if (data != null) {
            body = RequestBody.create((MediaType) null, data);
        } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            body = RequestBody.create((MediaType) null, new byte[0]);
        }
        builder.method(method, body);
        return builder.build();
    }

    private Response toResponse(final com.penumbraos.shaded.okhttp3.Response r) throws IOException {
        final byte[] bytes = r.body() != null ? r.body().bytes() : new byte[0];
        final Map<String, List<String>> headers = new HashMap<>();
        for (final String name : r.headers().names()) {
            headers.put(name, new ArrayList<>(r.headers().values(name)));
        }
        return new Response(r.code(), r.message(), headers,
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8), bytes,
                r.request().url().toString());
    }

    @Override
    public Response execute(final Request request) throws IOException {
        try (com.penumbraos.shaded.okhttp3.Response r = client.newCall(build(request)).execute()) {
            return toResponse(r);
        }
    }

    @Override
    public CancellableCall executeAsync(final Request request, final AsyncCallback callback) {
        final Call call = client.newCall(build(request));
        final CancellableCall cancellable = new CancellableCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(final Call c, final IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(final Call c, final com.penumbraos.shaded.okhttp3.Response response) {
                try (com.penumbraos.shaded.okhttp3.Response r = response) {
                    callback.onSuccess(toResponse(r));
                } catch (final Exception ex) {
                    callback.onError(ex);
                } finally {
                    cancellable.setFinished();
                }
            }
        });
        return cancellable;
    }
}
