package com.example.discordlink;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Mod(DiscordLinkMod.MOD_ID)
public class DiscordLinkMod {
    public static final String MOD_ID = "discordlink";
    private static final Logger LOGGER = LogUtils.getLogger();

    // If your bot is on another machine, change this URL.
    private static final String BOT_BASE_URL = "http://localhost:3000";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public DiscordLinkMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(ForgeEvents.class);
        LOGGER.info("DiscordLinkMod loaded.");
    }

    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            LOGGER.info("DiscordLinkMod server starting; Discord bridge is active.");
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            LOGGER.info("DiscordLinkMod server started; sending startup notice to Discord.");
            sendToDiscord("SERVER", "@minecraft Server has started.");
        }

        @SubscribeEvent(priority = EventPriority.NORMAL)
        public static void onServerChat(ServerChatEvent event) {
            var player = event.getPlayer();
            var message = event.getRawText();

            sendToDiscord(player.getGameProfile().getName(), message);
        }

        private static void sendToDiscord(String player, String message) {
            String json = "{\"player\":\"" + escapeJson(player)
                    + "\",\"message\":\"" + escapeJson(message) + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BOT_BASE_URL + "/mc/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, throwable) -> {
                        if (throwable != null) {
                            LOGGER.warn("Failed to send chat message to Discord bot", throwable);
                        } else if (resp.statusCode() >= 400) {
                            LOGGER.warn("Discord bot returned status {} when sending chat message", resp.statusCode());
                        }
                    });
        }

        private static String escapeJson(String input) {
            return input
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}

