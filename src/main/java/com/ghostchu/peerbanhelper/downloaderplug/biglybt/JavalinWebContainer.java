package com.ghostchu.peerbanhelper.downloaderplug.biglybt;

import com.ghostchu.peerbanhelper.downloaderplug.biglybt.network.SimpleResponse;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.Header;
import io.javalin.http.HttpStatus;
import io.javalin.json.JsonMapper;
import io.javalin.plugin.bundled.CorsPluginConfig;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

@Slf4j
public class JavalinWebContainer {
    private final JsonMapper gsonMapper = new JsonMapper() {
        @Override
        public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
            return Plugin.GSON.toJson(obj,type);
        }

        @Override
        public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
            return  Plugin.GSON.fromJson(json, targetType);
        }
    };
    private Javalin javalin;

    public JavalinWebContainer() {
    }

    public void start(String host, int port, String token) {
        this.javalin = Javalin.create(c -> {
                    c.http.gzipOnlyCompression();
                    c.http.generateEtags = true;
                    c.showJavalinBanner = false;
                    c.jsonMapper(gsonMapper);
                    c.jetty.multipartConfig.maxTotalRequestSize(64, SizeUnit.MB);
                    c.bundledPlugins.enableCors(cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
                })
                .exception(Exception.class, (e, ctx) -> {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                    ctx.json(new SimpleResponse("500 Internal Server Error: " + e.getMessage()));
                    log.warn("500 Internal Server Error", e);
                })
                .exception(APINotLoggedInException.class, (e, ctx) -> {
                    ctx.status(HttpStatus.FORBIDDEN);
                    ctx.json(new SimpleResponse("403 Forbidden, Need correct access key"));
                })

                .beforeMatched(ctx -> {
                    String authenticated = ctx.sessionAttribute("authenticated");
                    if (authenticated != null && authenticated.equals(token)) {
                        return;
                    }
                    String authToken = ctx.header("Authorization");
                    if (authToken != null) {
                        if (authToken.startsWith("Bearer ")) {
                            String tk = authToken.substring(7);
                            if (tk.equals(token)) {
                                return;
                            }
                        }
                    }
                    throw new APINotLoggedInException();
                })
                .after(handler->{
                    handler.header(Header.SERVER, "PeerBanHelper-BiglyBT-Adapter/");
                })
                .start(host, port);
    }

    public Javalin javalin() {
        return javalin;
    }

    public void stop(){
        if(this.javalin != null){
            this.javalin.stop();
        }
    }
}
