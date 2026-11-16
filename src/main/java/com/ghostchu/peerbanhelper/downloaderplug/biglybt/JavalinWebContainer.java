package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.SimpleResponse;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.Header;
import io.javalin.http.HttpStatus;
import io.javalin.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

@Slf4j
public class JavalinWebContainer {
    private final JsonMapper gsonMapper = new JsonMapper() {
        @Override
        public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
            return Plugin.GSON.toJson(obj, type);
        }

        @Override
        public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
            return Plugin.GSON.fromJson(json, targetType);
        }
    };
    private Javalin javalin;

    public JavalinWebContainer() {
    }

    public void start(String host, int port, String token, Consumer<Javalin> routes) {
        if (host.isBlank() || token.isBlank()) {
            throw new IllegalArgumentException("Bind address and token must be set");
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.javalin = Javalin.create(c -> {
                    c.http.gzipOnlyCompression();
                    c.http.generateEtags = true;
                    c.showJavalinBanner = false;
                    c.jsonMapper(gsonMapper);
                    c.http.maxRequestSize = 64000000;
                    c.jetty.multipartConfig.maxTotalRequestSize(64, SizeUnit.MB);


                })
                .exception(Exception.class, (e, ctx) -> {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                    ctx.json(new SimpleResponse("500 Internal Server Error"));
                    log.warn("500 Internal Server Error", e);
                })
                .exception(APINotLoggedInException.class, (e, ctx) -> {
                    ctx.status(HttpStatus.FORBIDDEN);
                    ctx.json(new SimpleResponse("403 Forbidden, Need correct access key"));
                })

                .beforeMatched(ctx -> {
                    String supplied = ctx.header("Authorization");
                    if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                        throw new APINotLoggedInException();
                    }
                })
                .after(handler -> handler.header(Header.SERVER, "PeerBanHelper-BiglyBT-Adapter"));
        routes.accept(javalin);
        try {
            javalin.start(host, port);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public Javalin javalin() {
        return javalin;
    }

    public void stop() {
        if (this.javalin != null) {
            this.javalin.stop();
        }
    }
}
