package com.example.discordlink;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.security.SecureRandom;

@Mod(DiscordLinkMod.MOD_ID)
public class DiscordLinkMod {
    public static final String MOD_ID = "discordlink";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BOT_BASE_URL = resolveBotBaseUrl();
    private static final String BOT_SHARED_SECRET = resolveBridgeSecret();
    private static volatile String runtimeBridgeSecret;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public DiscordLinkMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(ForgeEvents.class);
        LOGGER.info("DiscordLinkMod loaded.");
        LOGGER.info("Discord bridge target set to {}", BOT_BASE_URL);

        if (BOT_SHARED_SECRET == null) {
            LOGGER.warn("DISCORDLINK_SHARED_SECRET is not configured; bot bridge may reject requests.");
        }
    }

    @EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            LiteralArgumentBuilder<CommandSourceStack> registerTeamCommand = Commands.literal("registerteam")
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .executes(context -> {
                        ServerPlayer player = (ServerPlayer) context.getSource().getEntity();
                        if (player == null) {
                            return 0;
                        }

                        try {
                            String teamReport = CobblemonReflection.buildTeamReport(player);
                            sendToDiscord(player.getGameProfile().getName(), teamReport);
                            context.getSource().sendSuccess(() -> Component.literal("Your Cobblemon team was sent to Discord."), false);
                            return 1;
                        } catch (CobblemonReflection.CobblemonUnavailableException unavailable) {
                            context.getSource().sendFailure(Component.literal(unavailable.getMessage()));
                            return 0;
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to register Cobblemon team to Discord", ex);
                            context.getSource().sendFailure(Component.literal("Failed to read your Cobblemon team. Check server logs."));
                            return 0;
                        }
                    });

            event.getDispatcher().register(registerTeamCommand);

            LiteralArgumentBuilder<CommandSourceStack> discordSecretCommand = Commands.literal("discordsecret")
                    .requires(source -> source.hasPermission(4))
                    .executes(context -> {
                        var server = context.getSource().getServer();
                        String secret = ensureRuntimeBridgeSecret(server);
                        LOGGER.info("[DiscordLink] DISCORDLINK_SHARED_SECRET={}", secret);
                        context.getSource().sendSuccess(
                                () -> Component.literal("DiscordLink shared secret printed to server console."),
                                false
                        );
                        return 1;
                    });

            event.getDispatcher().register(discordSecretCommand);
        }

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            LOGGER.info("DiscordLinkMod server starting; Discord bridge is active.");
            ensureRuntimeBridgeSecret(event.getServer());
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
                    .header("X-DiscordLink-Secret", getCurrentBridgeSecret())
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

    private static String resolveBridgeSecret() {
        String env = System.getenv("DISCORDLINK_SHARED_SECRET");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        String prop = System.getProperty("discordlink.shared.secret");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }

        return null;
    }

    private static String resolveBotBaseUrl() {
        String env = System.getenv("DISCORDLINK_BOT_BASE_URL");
        if (env != null && !env.isBlank()) {
            return trimTrailingSlash(env.trim());
        }

        String prop = System.getProperty("discordlink.bot.base.url");
        if (prop != null && !prop.isBlank()) {
            return trimTrailingSlash(prop.trim());
        }

        return "https://dripmon-discord-production.up.railway.app";
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String getCurrentBridgeSecret() {
        if (runtimeBridgeSecret != null && !runtimeBridgeSecret.isBlank()) {
            return runtimeBridgeSecret;
        }

        if (BOT_SHARED_SECRET != null && !BOT_SHARED_SECRET.isBlank()) {
            return BOT_SHARED_SECRET;
        }

        return "";
    }

    private static String ensureRuntimeBridgeSecret(net.minecraft.server.MinecraftServer server) {
        if (runtimeBridgeSecret != null && !runtimeBridgeSecret.isBlank()) {
            return runtimeBridgeSecret;
        }

        if (BOT_SHARED_SECRET != null && !BOT_SHARED_SECRET.isBlank()) {
            runtimeBridgeSecret = BOT_SHARED_SECRET;
            return runtimeBridgeSecret;
        }

        try {
            Path configDir = server.getServerDirectory().resolve("config");
            Files.createDirectories(configDir);

            Path secretFile = configDir.resolve("discordlink-secret.txt");
            if (Files.exists(secretFile)) {
                String existing = Files.readString(secretFile).trim();
                if (!existing.isBlank()) {
                    runtimeBridgeSecret = existing;
                    return runtimeBridgeSecret;
                }
            }

            String generated = generateSecret();
            Files.writeString(secretFile, generated + System.lineSeparator());
            runtimeBridgeSecret = generated;
            LOGGER.info("Generated DiscordLink shared secret file at {}", secretFile.toAbsolutePath());
            return runtimeBridgeSecret;
        } catch (Exception e) {
            LOGGER.error("Failed to load or generate DiscordLink shared secret", e);
            return "";
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static class CobblemonReflection {
        private static final String COBBLEMON_CLASS = "com.cobblemon.mod.common.Cobblemon";

        static String buildTeamReport(ServerPlayer player) {
            Object partyStore = resolvePartyStore(player);
            if (partyStore == null) {
                throw new CobblemonUnavailableException("Cobblemon is installed but no player party could be found.");
            }

            List<Object> pokemonList = toList(partyStore);
            if (pokemonList.isEmpty()) {
                return "Team registration: no Pokemon found in current party.";
            }

            StringBuilder out = new StringBuilder("Team registration:\n");
            int index = 1;
            for (Object pokemon : pokemonList) {
                out.append(index).append(") ").append(extractPokemonName(pokemon)).append("\n");
                out.append("- Ability: ").append(extractAbilityName(pokemon)).append("\n");
                out.append("- Held Item: ").append(extractHeldItemName(pokemon)).append("\n");
                out.append("- Moves: ").append(String.join(", ", extractMoves(pokemon))).append("\n");
                index++;
            }

            return out.toString().trim();
        }

        private static Object resolvePartyStore(ServerPlayer player) {
            Object cobblemon = getCobblemonSingleton();
            Object storage = invokeNoArgs(cobblemon, "getStorage");
            if (storage == null) {
                throw new CobblemonUnavailableException("Cobblemon storage is not available.");
            }

            Object partyByPlayer = invokeMatching(storage, "getParty", player);
            if (partyByPlayer != null) {
                return unwrap(partyByPlayer);
            }

            UUID uuid = player.getUUID();
            Object registryAccess = player.getServer() != null ? player.getServer().registryAccess() : null;

            Object partyByUuid = registryAccess != null
                    ? invokeMatching(storage, "getParty", uuid, registryAccess)
                    : null;
            if (partyByUuid != null) {
                return unwrap(partyByUuid);
            }

            Object partyByUuidOnly = invokeMatching(storage, "getParty", uuid);
            return unwrap(partyByUuidOnly);
        }

        private static String extractPokemonName(Object pokemon) {
            Object displayName = invokeMatching(pokemon, "getDisplayName");
            if (displayName != null) {
                String asString = invokeString(displayName, "getString");
                if (asString != null && !asString.isBlank()) {
                    return asString;
                }
            }

            Object species = invokeMatching(pokemon, "getSpecies");
            if (species != null) {
                String speciesName = invokeString(species, "getName");
                if (speciesName != null && !speciesName.isBlank()) {
                    return speciesName;
                }
            }

            return "Unknown";
        }

        private static String extractAbilityName(Object pokemon) {
            Object ability = invokeMatching(pokemon, "getAbility");
            if (ability == null) {
                return "Unknown";
            }

            Object template = invokeMatching(ability, "getTemplate");
            if (template != null) {
                String templateName = invokeString(template, "getName");
                if (templateName != null && !templateName.isBlank()) {
                    return templateName;
                }
            }

            String fallback = invokeString(ability, "getName");
            return fallback != null && !fallback.isBlank() ? fallback : "Unknown";
        }

        private static String extractHeldItemName(Object pokemon) {
            Object itemStack = invokeMatching(pokemon, "heldItem");
            if (itemStack == null) {
                itemStack = invokeMatching(pokemon, "getHeldItem");
            }
            if (itemStack == null) {
                return "None";
            }

            Boolean isEmpty = invokeBoolean(itemStack, "isEmpty");
            if (Boolean.TRUE.equals(isEmpty)) {
                return "None";
            }

            Object hoverName = invokeMatching(itemStack, "getHoverName");
            if (hoverName != null) {
                String name = invokeString(hoverName, "getString");
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }

            String asString = itemStack.toString();
            return asString.isBlank() ? "Unknown" : asString;
        }

        private static List<String> extractMoves(Object pokemon) {
            Object moveSet = invokeMatching(pokemon, "getMoveSet");
            if (moveSet == null) {
                return List.of("None");
            }

            List<Object> moves = toList(moveSet);
            if (moves.isEmpty()) {
                return List.of("None");
            }

            List<String> names = new ArrayList<>();
            for (Object move : moves) {
                Object template = invokeMatching(move, "getTemplate");
                String name = template != null ? invokeString(template, "getName") : null;
                if (name == null || name.isBlank()) {
                    name = invokeString(move, "getName");
                }
                if (name == null || name.isBlank()) {
                    name = "Unknown";
                }
                names.add(name);
            }

            return names;
        }

        private static Object getCobblemonSingleton() {
            try {
                Class<?> cobblemonClass = Class.forName(COBBLEMON_CLASS);
                Field instanceField = cobblemonClass.getField("INSTANCE");
                return instanceField.get(null);
            } catch (ClassNotFoundException e) {
                throw new CobblemonUnavailableException("Cobblemon is not installed on this server.");
            } catch (Exception e) {
                throw new CobblemonUnavailableException("Cobblemon was found, but integration failed to initialize.");
            }
        }

        private static List<Object> toList(Object maybeCollection) {
            if (maybeCollection == null) {
                return List.of();
            }

            Object unwrapped = unwrap(maybeCollection);
            if (unwrapped == null) {
                return List.of();
            }

            List<Object> out = new ArrayList<>();
            if (unwrapped instanceof Iterable<?> iterable) {
                for (Object obj : iterable) {
                    if (obj != null) {
                        out.add(obj);
                    }
                }
                return out;
            }

            if (unwrapped.getClass().isArray()) {
                int length = Array.getLength(unwrapped);
                for (int i = 0; i < length; i++) {
                    Object obj = Array.get(unwrapped, i);
                    if (obj != null) {
                        out.add(obj);
                    }
                }
            }

            return out;
        }

        private static Object unwrap(Object value) {
            if (value == null) {
                return null;
            }

            try {
                Method isPresent = value.getClass().getMethod("isPresent");
                if (isPresent.getReturnType() == boolean.class) {
                    boolean present = (boolean) isPresent.invoke(value);
                    if (!present) {
                        return null;
                    }
                    Method get = value.getClass().getMethod("get");
                    return get.invoke(value);
                }
            } catch (Exception ignored) {
                // Not an Optional-like wrapper.
            }

            return value;
        }

        private static Object invokeNoArgs(Object target, String methodName) {
            return invokeMatching(target, methodName);
        }

        private static Object invokeMatching(Object target, String methodName, Object... args) {
            if (target == null) {
                return null;
            }

            Method[] methods = target.getClass().getMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterCount() != args.length) {
                    continue;
                }
                if (!parametersAssignable(method.getParameterTypes(), args)) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (Exception ignored) {
                    // Try next overload.
                }
            }

            return null;
        }

        private static boolean parametersAssignable(Class<?>[] types, Object[] args) {
            for (int i = 0; i < types.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    continue;
                }

                Class<?> wrapped = wrapPrimitive(types[i]);
                if (!wrapped.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            }
            return true;
        }

        private static Class<?> wrapPrimitive(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }

            if (type == boolean.class) return Boolean.class;
            if (type == byte.class) return Byte.class;
            if (type == short.class) return Short.class;
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == float.class) return Float.class;
            if (type == double.class) return Double.class;
            if (type == char.class) return Character.class;
            return type;
        }

        private static String invokeString(Object target, String methodName) {
            Object result = invokeMatching(target, methodName);
            return result instanceof String ? (String) result : null;
        }

        private static Boolean invokeBoolean(Object target, String methodName) {
            Object result = invokeMatching(target, methodName);
            return result instanceof Boolean ? (Boolean) result : null;
        }

        private static class CobblemonUnavailableException extends RuntimeException {
            CobblemonUnavailableException(String message) {
                super(message);
            }
        }
    }
}

