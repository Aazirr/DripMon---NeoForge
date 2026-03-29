package com.example.discordlink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Mod(DiscordLinkMod.MOD_ID)
public class DiscordLinkMod {
    public static final String MOD_ID = "discordlink";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BOT_BASE_URL = resolveBotBaseUrl();
    private static final String BOT_SHARED_SECRET = resolveBridgeSecret();
    private static volatile String runtimeBridgeSecret;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TOURNAMENT_DATA_TYPE = new TypeToken<TournamentData>() { }.getType();
    private static final Pattern TOURNAMENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9 _\\-]{1,40}");

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
                    .executes(context -> handleRegisterTeamPreview(context.getSource()))
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleRegisterTeamForTournament(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(registerTeamCommand);

            LiteralArgumentBuilder<CommandSourceStack> registerTournamentCommand = Commands.literal("registertournament")
                    .requires(source -> source.hasPermission(4))
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleRegisterTournament(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(registerTournamentCommand);

            LiteralArgumentBuilder<CommandSourceStack> lockTournamentCommand = Commands.literal("locktournament")
                    .requires(source -> source.hasPermission(4))
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleLockTournament(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(lockTournamentCommand);

            LiteralArgumentBuilder<CommandSourceStack> checkCommand = Commands.literal("check")
                    .requires(source -> source.getEntity() instanceof ServerPlayer || source.hasPermission(4))
                    .then(Commands.argument("tournament", StringArgumentType.string())
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(context -> {
                                        String rawTournament = StringArgumentType.getString(context, "tournament");
                                        String playerName = StringArgumentType.getString(context, "player");
                                        return handleCheckTournamentTeam(context.getSource(), rawTournament, playerName);
                                    })));
            event.getDispatcher().register(checkCommand);

            LiteralArgumentBuilder<CommandSourceStack> unregisterTeamCommand = Commands.literal("unregisterteam")
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleUnregisterTeam(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(unregisterTeamCommand);

            LiteralArgumentBuilder<CommandSourceStack> listTournamentsCommand = Commands.literal("listtournaments")
                    .requires(source -> source.getEntity() instanceof ServerPlayer || source.hasPermission(2))
                    .executes(context -> handleListTournaments(context.getSource()));
            event.getDispatcher().register(listTournamentsCommand);

            LiteralArgumentBuilder<CommandSourceStack> tournamentInfoCommand = Commands.literal("tournamentinfo")
                    .requires(source -> source.getEntity() instanceof ServerPlayer || source.hasPermission(2))
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleTournamentInfo(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(tournamentInfoCommand);

            LiteralArgumentBuilder<CommandSourceStack> exportTournamentCommand = Commands.literal("exporttournament")
                    .requires(source -> source.hasPermission(4))
                    .then(Commands.argument("tournament", StringArgumentType.greedyString())
                            .executes(context -> {
                                String rawName = StringArgumentType.getString(context, "tournament");
                                return handleExportTournament(context.getSource(), rawName);
                            }));
            event.getDispatcher().register(exportTournamentCommand);

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

        private static int handleRegisterTeamPreview(CommandSourceStack source) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal("Only players can use this command."));
                return 0;
            }

            try {
                String teamReport = CobblemonReflection.buildTeamReport(player);
                sendToDiscord(player.getGameProfile().getName(), teamReport);
                source.sendSuccess(() -> Component.literal("Your Cobblemon team was sent to Discord."), false);
                return 1;
            } catch (CobblemonReflection.CobblemonUnavailableException unavailable) {
                source.sendFailure(Component.literal(unavailable.getMessage()));
                return 0;
            } catch (Exception ex) {
                LOGGER.warn("Failed to build Cobblemon team report", ex);
                source.sendFailure(Component.literal("Failed to read your Cobblemon team. Check server logs."));
                return 0;
            }
        }

        private static int handleRegisterTournament(CommandSourceStack source, String rawName) {
            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }

            String normalized = normalizeTournamentName(tournamentInput);
            TournamentData data = loadTournamentData(source.getServer());
            if (data.tournaments.containsKey(normalized)) {
                source.sendFailure(Component.literal("Tournament already exists: " + data.tournaments.get(normalized).displayName));
                return 0;
            }

            TournamentRecord record = new TournamentRecord();
            record.displayName = tournamentInput;
            record.createdAt = Instant.now().toString();
            record.createdBy = source.getTextName();
            record.locked = false;
            data.tournaments.put(normalized, record);
            saveTournamentData(source.getServer(), data);

            source.sendSuccess(() -> Component.literal("Tournament created: " + tournamentInput), true);
            sendToDiscord("SERVER", "Tournament created: " + tournamentInput + " (by " + source.getTextName() + ")");
            return 1;
        }

        private static int handleRegisterTeamForTournament(CommandSourceStack source, String rawName) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal("Only players can use this command."));
                return 0;
            }

            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }
            if (record.locked) {
                source.sendFailure(Component.literal("Tournament is locked. Team registrations can no longer be edited."));
                return 0;
            }

            TeamSnapshot currentTeam;
            try {
                currentTeam = CobblemonReflection.extractTeamSnapshot(player);
            } catch (CobblemonReflection.CobblemonUnavailableException unavailable) {
                source.sendFailure(Component.literal(unavailable.getMessage()));
                return 0;
            } catch (Exception ex) {
                LOGGER.warn("Failed to read Cobblemon team for tournament registration", ex);
                source.sendFailure(Component.literal("Failed to read your Cobblemon team. Check server logs."));
                return 0;
            }

            TournamentRegistration registration = new TournamentRegistration();
            registration.playerUuid = player.getUUID().toString();
            registration.playerNameAtRegistration = player.getGameProfile().getName();
            registration.registeredAt = Instant.now().toString();
            registration.team = currentTeam;

            record.registrations.put(player.getUUID().toString(), registration);
            saveTournamentData(source.getServer(), data);

            source.sendSuccess(
                    () -> Component.literal("Registered your current team for tournament: " + record.displayName + ". Re-running this command replaces your saved team until lock."),
                    false
            );
            sendToDiscord("SERVER", player.getGameProfile().getName() + " registered a team for tournament " + record.displayName + ".");
            return 1;
        }

        private static int handleLockTournament(CommandSourceStack source, String rawName) {
            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }
            if (record.locked) {
                source.sendFailure(Component.literal("Tournament is already locked."));
                return 0;
            }

            record.locked = true;
            saveTournamentData(source.getServer(), data);

            source.sendSuccess(() -> Component.literal("Tournament locked: " + record.displayName), true);
            sendToDiscord("SERVER", "Tournament locked: " + record.displayName + " (teams are now final).");
            return 1;
        }

        private static int handleCheckTournamentTeam(CommandSourceStack source, String rawTournament, String playerName) {
            String tournamentInput = cleanTournamentInput(rawTournament);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }

            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player is not online: " + playerName));
                return 0;
            }

            TournamentRegistration registration = record.registrations.get(target.getUUID().toString());
            if (registration == null) {
                source.sendFailure(Component.literal(target.getGameProfile().getName() + " is not registered for tournament " + record.displayName + "."));
                return 0;
            }

            TeamSnapshot liveTeam;
            try {
                liveTeam = CobblemonReflection.extractTeamSnapshot(target);
            } catch (CobblemonReflection.CobblemonUnavailableException unavailable) {
                source.sendFailure(Component.literal(unavailable.getMessage()));
                return 0;
            } catch (Exception ex) {
                LOGGER.warn("Failed to read Cobblemon team for check command", ex);
                source.sendFailure(Component.literal("Failed to read target team. Check server logs."));
                return 0;
            }

            if (!registration.team.matches(liveTeam)) {
                source.sendFailure(Component.literal(target.getGameProfile().getName() + " failed team check for " + record.displayName + ". Their current team differs from the registered team."));
                return 0;
            }

            String passMessage = target.getGameProfile().getName() + " passed the team check for the " + record.displayName + " tournament.";
            source.getServer().getPlayerList().broadcastSystemMessage(Component.literal(passMessage), false);
            sendToDiscord("SERVER", passMessage);
            return 1;
        }

        private static int handleUnregisterTeam(CommandSourceStack source, String rawName) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal("Only players can use this command."));
                return 0;
            }

            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }
            if (record.locked) {
                source.sendFailure(Component.literal("Tournament is locked. Team registrations can no longer be edited."));
                return 0;
            }

            TournamentRegistration removed = record.registrations.remove(player.getUUID().toString());
            if (removed == null) {
                source.sendFailure(Component.literal("You are not registered for tournament " + record.displayName + "."));
                return 0;
            }

            saveTournamentData(source.getServer(), data);
            source.sendSuccess(() -> Component.literal("Removed your registration for tournament " + record.displayName + "."), false);
            sendToDiscord("SERVER", player.getGameProfile().getName() + " removed their registration for tournament " + record.displayName + ".");
            return 1;
        }

        private static int handleListTournaments(CommandSourceStack source) {
            TournamentData data = loadTournamentData(source.getServer());
            if (data.tournaments.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No tournaments registered yet."), false);
                return 1;
            }

            List<TournamentRecord> records = new ArrayList<>(data.tournaments.values());
            records.sort(Comparator.comparing(record -> record.displayName.toLowerCase(Locale.ROOT)));

            source.sendSuccess(() -> Component.literal("Tournaments:"), false);
            for (TournamentRecord record : records) {
                String status = record.locked ? "LOCKED" : "OPEN";
                int count = record.registrations.size();
                source.sendSuccess(() -> Component.literal("- " + record.displayName + " [" + status + "] registrations=" + count), false);
            }
            return 1;
        }

        private static int handleTournamentInfo(CommandSourceStack source, String rawName) {
            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }

            source.sendSuccess(() -> Component.literal("Tournament: " + record.displayName), false);
            source.sendSuccess(() -> Component.literal("Status: " + (record.locked ? "LOCKED" : "OPEN")), false);
            source.sendSuccess(() -> Component.literal("Created by: " + valueOrUnknown(record.createdBy)), false);
            source.sendSuccess(() -> Component.literal("Created at: " + valueOrUnknown(record.createdAt)), false);
            source.sendSuccess(() -> Component.literal("Registrations: " + record.registrations.size()), false);

            List<TournamentRegistration> regs = new ArrayList<>(record.registrations.values());
            regs.sort(Comparator.comparing(reg -> valueOrUnknown(reg.playerNameAtRegistration).toLowerCase(Locale.ROOT)));
            for (TournamentRegistration reg : regs) {
                String playerName = valueOrUnknown(reg.playerNameAtRegistration);
                String registeredAt = valueOrUnknown(reg.registeredAt);
                source.sendSuccess(() -> Component.literal("- " + playerName + " (registered: " + registeredAt + ")"), false);
            }
            return 1;
        }

        private static int handleExportTournament(CommandSourceStack source, String rawName) {
            String tournamentInput = cleanTournamentInput(rawName);
            String validationError = validateTournamentName(tournamentInput);
            if (validationError != null) {
                source.sendFailure(Component.literal(validationError));
                return 0;
            }
            String normalized = normalizeTournamentName(tournamentInput);

            TournamentData data = loadTournamentData(source.getServer());
            TournamentRecord record = data.tournaments.get(normalized);
            if (record == null) {
                source.sendFailure(Component.literal("Tournament not found: " + tournamentInput));
                return 0;
            }

            try {
                Path configDir = source.getServer().getServerDirectory().resolve("config");
                Path exportDir = configDir.resolve("discordlink-exports");
                Files.createDirectories(exportDir);

                String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
                String safeName = normalized.replace(' ', '_');
                Path output = exportDir.resolve(safeName + "-" + timestamp + ".txt");

                List<String> lines = new ArrayList<>();
                lines.add("Tournament Export");
                lines.add("Name: " + record.displayName);
                lines.add("Status: " + (record.locked ? "LOCKED" : "OPEN"));
                lines.add("Created by: " + valueOrUnknown(record.createdBy));
                lines.add("Created at: " + valueOrUnknown(record.createdAt));
                lines.add("Registrations: " + record.registrations.size());
                lines.add("");

                List<TournamentRegistration> regs = new ArrayList<>(record.registrations.values());
                regs.sort(Comparator.comparing(reg -> valueOrUnknown(reg.playerNameAtRegistration).toLowerCase(Locale.ROOT)));
                for (TournamentRegistration reg : regs) {
                    lines.add("Player: " + valueOrUnknown(reg.playerNameAtRegistration));
                    lines.add("UUID: " + valueOrUnknown(reg.playerUuid));
                    lines.add("Registered at: " + valueOrUnknown(reg.registeredAt));
                    lines.add("Team:");
                    lines.addAll(formatTeamForExport(reg.team));
                    lines.add("");
                }

                Files.write(output, lines);
                TournamentExportPayload payload = buildTournamentExportPayload(record);
                String botPage = sendTournamentExportToBot(payload);

                source.sendSuccess(() -> Component.literal("Tournament exported to " + output.toAbsolutePath()), false);
                if (botPage != null && !botPage.isBlank()) {
                    source.sendSuccess(() -> Component.literal("Tournament web page: " + botPage), false);
                } else {
                    source.sendSuccess(() -> Component.literal("Tournament exported locally, but bot page URL was not returned."), false);
                }
                return 1;
            } catch (Exception ex) {
                LOGGER.warn("Failed to export tournament", ex);
                source.sendFailure(Component.literal("Failed to export tournament. Check server logs."));
                return 0;
            }
        }

        private static List<String> formatTeamForExport(TeamSnapshot team) {
            List<String> lines = new ArrayList<>();
            if (team == null || team.pokemon.isEmpty()) {
                lines.add("  - No Pokemon");
                return lines;
            }

            int index = 1;
            for (PokemonSnapshot pokemon : team.pokemon) {
                lines.add("  " + index + ") " + valueOrUnknown(pokemon.displayName));
                lines.add("     Species: " + valueOrUnknown(pokemon.species));
                lines.add("     Ability: " + valueOrUnknown(pokemon.ability));
                lines.add("     Held Item: " + valueOrUnknown(pokemon.heldItem));
                lines.add("     Nature: " + valueOrUnknown(pokemon.nature));
                lines.add("     Gender: " + valueOrUnknown(pokemon.gender));
                lines.add("     Form: " + valueOrUnknown(pokemon.form));
                lines.add("     IVs: " + valueOrUnknown(pokemon.ivSpread));
                lines.add("     EVs: " + valueOrUnknown(pokemon.evSpread));
                lines.add("     Moves: " + String.join(", ", pokemon.moves));
                index++;
            }
            return lines;
        }

        private static TournamentExportPayload buildTournamentExportPayload(TournamentRecord record) {
            TournamentExportPayload payload = new TournamentExportPayload();
            payload.tournamentName = record.displayName;
            payload.status = record.locked ? "LOCKED" : "OPEN";
            payload.createdBy = valueOrUnknown(record.createdBy);
            payload.createdAt = valueOrUnknown(record.createdAt);
            payload.exportedAt = Instant.now().toString();

            List<TournamentRegistration> regs = new ArrayList<>(record.registrations.values());
            regs.sort(Comparator.comparing(reg -> valueOrUnknown(reg.playerNameAtRegistration).toLowerCase(Locale.ROOT)));
            for (TournamentRegistration reg : regs) {
                ExportPlayer player = new ExportPlayer();
                player.playerUuid = valueOrUnknown(reg.playerUuid);
                player.playerName = valueOrUnknown(reg.playerNameAtRegistration);
                player.registeredAt = valueOrUnknown(reg.registeredAt);

                TeamSnapshot team = reg.team != null ? reg.team : new TeamSnapshot();
                for (PokemonSnapshot pokemon : team.pokemon) {
                    ExportPokemon out = new ExportPokemon();
                    out.displayName = valueOrUnknown(pokemon.displayName);
                    out.species = valueOrUnknown(pokemon.species);
                    out.ability = valueOrUnknown(pokemon.ability);
                    out.heldItem = valueOrUnknown(pokemon.heldItem);
                    out.nature = valueOrUnknown(pokemon.nature);
                    out.gender = valueOrUnknown(pokemon.gender);
                    out.form = valueOrUnknown(pokemon.form);
                    out.ivSpread = valueOrUnknown(pokemon.ivSpread);
                    out.evSpread = valueOrUnknown(pokemon.evSpread);
                    out.moves = pokemon.moves != null ? new ArrayList<>(pokemon.moves) : List.of();
                    player.team.add(out);
                }

                payload.players.add(player);
            }

            return payload;
        }

        private static String sendTournamentExportToBot(TournamentExportPayload payload) {
            try {
                String body = GSON.toJson(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BOT_BASE_URL + "/mc/tournament-export"))
                        .header("Content-Type", "application/json")
                        .header("X-DiscordLink-Secret", getCurrentBridgeSecret())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    LOGGER.warn("Tournament export endpoint returned status {}: {}", response.statusCode(), response.body());
                    return null;
                }

                Map<?, ?> parsed = GSON.fromJson(response.body(), Map.class);
                Object publicUrl = parsed != null ? parsed.get("publicUrl") : null;
                if (publicUrl instanceof String s && !s.isBlank()) {
                    return BOT_BASE_URL + s;
                }
                return null;
            } catch (Exception ex) {
                LOGGER.warn("Failed to push tournament export to bot", ex);
                return null;
            }
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

    private static String validateTournamentName(String name) {
        if (name == null || name.isBlank()) {
            return "Tournament name cannot be empty.";
        }
        if (!TOURNAMENT_NAME_PATTERN.matcher(name).matches()) {
            return "Tournament name must be 1-40 chars and only use letters, numbers, spaces, '-' or '_'.";
        }
        return null;
    }

    private static String cleanTournamentInput(String raw) {
        if (raw == null) {
            return "";
        }

        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static String normalizeTournamentName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String valueOrUnknown(String input) {
        if (input == null || input.isBlank()) {
            return "Unknown";
        }
        return input;
    }

    private static synchronized TournamentData loadTournamentData(net.minecraft.server.MinecraftServer server) {
        Path path = getTournamentDataPath(server);

        try {
            if (!Files.exists(path)) {
                return new TournamentData();
            }

            String json = Files.readString(path);
            TournamentData parsed = GSON.fromJson(json, TOURNAMENT_DATA_TYPE);
            if (parsed == null) {
                return new TournamentData();
            }
            if (parsed.tournaments == null) {
                parsed.tournaments = new HashMap<>();
            }
            for (TournamentRecord record : parsed.tournaments.values()) {
                if (record.registrations == null) {
                    record.registrations = new HashMap<>();
                }
            }
            return parsed;
        } catch (Exception e) {
            LOGGER.error("Failed to read tournament data. Using empty state.", e);
            return new TournamentData();
        }
    }

    private static synchronized void saveTournamentData(net.minecraft.server.MinecraftServer server, TournamentData data) {
        Path path = getTournamentDataPath(server);

        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.writeString(temp, GSON.toJson(data));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("Failed to save tournament data", e);
        }
    }

    private static Path getTournamentDataPath(net.minecraft.server.MinecraftServer server) {
        return server.getServerDirectory().resolve("config").resolve("discordlink-tournaments.json");
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

    private static class TournamentData {
        Map<String, TournamentRecord> tournaments = new HashMap<>();
    }

    private static class TournamentRecord {
        String displayName;
        String createdAt;
        String createdBy;
        boolean locked;
        Map<String, TournamentRegistration> registrations = new HashMap<>();
    }

    private static class TournamentRegistration {
        String playerUuid;
        String playerNameAtRegistration;
        String registeredAt;
        TeamSnapshot team;
    }

    private static class TournamentExportPayload {
        String tournamentName;
        String status;
        String createdBy;
        String createdAt;
        String exportedAt;
        List<ExportPlayer> players = new ArrayList<>();
    }

    private static class ExportPlayer {
        String playerUuid;
        String playerName;
        String registeredAt;
        List<ExportPokemon> team = new ArrayList<>();
    }

    private static class ExportPokemon {
        String displayName;
        String species;
        String ability;
        String heldItem;
        String nature;
        String gender;
        String form;
        String ivSpread;
        String evSpread;
        List<String> moves = new ArrayList<>();
    }

    private static class TeamSnapshot {
        List<PokemonSnapshot> pokemon = new ArrayList<>();

        boolean matches(TeamSnapshot other) {
            if (other == null) {
                return false;
            }

            List<String> left = canonicalPokemonKeys(this.pokemon);
            List<String> right = canonicalPokemonKeys(other.pokemon);
            return left.equals(right);
        }

        private static List<String> canonicalPokemonKeys(List<PokemonSnapshot> list) {
            List<String> keys = new ArrayList<>();
            if (list == null) {
                return keys;
            }

            for (PokemonSnapshot pokemon : list) {
                if (pokemon != null) {
                    keys.add(pokemon.canonicalKey());
                }
            }
            keys.sort(String::compareTo);
            return keys;
        }
    }

    private static class PokemonSnapshot {
        String displayName;
        String species;
        String ability;
        String heldItem;
        List<String> moves = new ArrayList<>();
        String nature;
        String gender;
        String form;
        String ivSpread;
        String evSpread;
        boolean shiny;

        String canonicalKey() {
            List<String> normalizedMoves = new ArrayList<>();
            for (String move : moves) {
                normalizedMoves.add(normalizeValue(move));
            }
            normalizedMoves.sort(String::compareTo);

            return String.join("|",
                    "species=" + normalizeValue(species),
                    "ability=" + normalizeValue(ability),
                    "item=" + normalizeValue(heldItem),
                    "nature=" + normalizeValue(nature),
                    "gender=" + normalizeValue(gender),
                    "form=" + normalizeValue(form),
                    "ivs=" + normalizeValue(ivSpread),
                    "evs=" + normalizeValue(evSpread),
                    "moves=" + String.join(",", normalizedMoves)
            );
        }

        private static String normalizeValue(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    private static class CobblemonReflection {
        private static final String COBBLEMON_CLASS = "com.cobblemon.mod.common.Cobblemon";

        static String buildTeamReport(ServerPlayer player) {
            TeamSnapshot team = extractTeamSnapshot(player);
            if (team.pokemon.isEmpty()) {
                return "Team registration: no Pokemon found in current party.";
            }

            StringBuilder out = new StringBuilder("Team registration:\n");
            int index = 1;
            for (PokemonSnapshot pokemon : team.pokemon) {
                out.append(index).append(") ").append(valueOrUnknown(pokemon.displayName)).append("\n");
                out.append("- Ability: ").append(valueOrUnknown(pokemon.ability)).append("\n");
                out.append("- Held Item: ").append(valueOrUnknown(pokemon.heldItem)).append("\n");
                out.append("- Moves: ").append(String.join(", ", pokemon.moves)).append("\n");
                index++;
            }

            return out.toString().trim();
        }

        static TeamSnapshot extractTeamSnapshot(ServerPlayer player) {
            Object partyStore = resolvePartyStore(player);
            if (partyStore == null) {
                throw new CobblemonUnavailableException("Cobblemon is installed but no player party could be found.");
            }

            List<Object> pokemonList = toList(partyStore);
            TeamSnapshot snapshot = new TeamSnapshot();
            for (Object pokemon : pokemonList) {
                snapshot.pokemon.add(extractPokemonSnapshot(pokemon));
            }
            return snapshot;
        }

        private static PokemonSnapshot extractPokemonSnapshot(Object pokemon) {
            PokemonSnapshot snapshot = new PokemonSnapshot();
            snapshot.displayName = extractPokemonName(pokemon);
            snapshot.species = extractSpeciesName(pokemon);
            snapshot.ability = extractAbilityName(pokemon);
            snapshot.heldItem = extractHeldItemName(pokemon);
            snapshot.moves = extractMoves(pokemon);
            snapshot.nature = extractNatureName(pokemon);
            snapshot.gender = extractGenderName(pokemon);
            snapshot.form = extractFormName(pokemon);
            snapshot.ivSpread = extractIvSpread(pokemon);
            snapshot.evSpread = extractEvSpread(pokemon);
            snapshot.shiny = extractShiny(pokemon);
            return snapshot;
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

            return extractSpeciesName(pokemon);
        }

        private static String extractSpeciesName(Object pokemon) {
            Object species = invokeMatching(pokemon, "getSpecies");
            if (species != null) {
                String speciesName = invokeString(species, "getName");
                if (speciesName != null && !speciesName.isBlank()) {
                    return speciesName;
                }

                Object translated = invokeMatching(species, "translatedName");
                String translatedString = translated != null ? invokeString(translated, "getString") : null;
                if (translatedString != null && !translatedString.isBlank()) {
                    return translatedString;
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

        private static String extractNatureName(Object pokemon) {
            Object nature = invokeMatching(pokemon, "getNature");
            if (nature == null) {
                return "Unknown";
            }

            String name = invokeString(nature, "getName");
            if (name != null && !name.isBlank()) {
                return name;
            }

            return sanitizeToString(nature);
        }

        private static String extractGenderName(Object pokemon) {
            Object gender = invokeMatching(pokemon, "getGender");
            if (gender == null) {
                return "Unknown";
            }

            String name = invokeString(gender, "name");
            if (name != null && !name.isBlank()) {
                return name;
            }

            return sanitizeToString(gender);
        }

        private static String extractFormName(Object pokemon) {
            Object form = invokeMatching(pokemon, "getForm");
            if (form != null) {
                String name = invokeString(form, "getName");
                if (name != null && !name.isBlank()) {
                    return name;
                }
                return sanitizeToString(form);
            }

            Object formName = invokeMatching(pokemon, "getFormName");
            if (formName instanceof String s && !s.isBlank()) {
                return s;
            }

            Object aspects = invokeMatching(pokemon, "getAspects");
            List<Object> aspectList = toList(aspects);
            if (!aspectList.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Object aspect : aspectList) {
                    String name = sanitizeToString(aspect);
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
                if (!names.isEmpty()) {
                    names.sort(String::compareTo);
                    return String.join(",", names);
                }
            }

            return "Default";
        }

        private static String extractIvSpread(Object pokemon) {
            Object ivs = invokeMatching(pokemon, "getIvs");
            if (ivs == null) {
                ivs = invokeMatching(pokemon, "getIVs");
            }
            return extractStatSpread(ivs);
        }

        private static String extractEvSpread(Object pokemon) {
            Object evs = invokeMatching(pokemon, "getEvs");
            if (evs == null) {
                evs = invokeMatching(pokemon, "getEVs");
            }
            return extractStatSpread(evs);
        }

        private static boolean extractShiny(Object pokemon) {
            Object shiny = invokeMatching(pokemon, "getShiny");
            if (shiny == null) {
                shiny = invokeMatching(pokemon, "isShiny");
            }
            return shiny instanceof Boolean b && b;
        }

        private static String extractStatSpread(Object spread) {
            if (spread == null) {
                return "unknown";
            }

            Map<String, String> values = new HashMap<>();
            tryAddStat(values, spread, "hp", "getHp", "getHP", "hp");
            tryAddStat(values, spread, "atk", "getAttack", "getAtk", "attack", "atk");
            tryAddStat(values, spread, "def", "getDefense", "getDef", "defense", "def");
            tryAddStat(values, spread, "spa", "getSpecialAttack", "getSpAttack", "getSpAtk", "specialAttack", "spAttack");
            tryAddStat(values, spread, "spd", "getSpecialDefense", "getSpDefense", "getSpDef", "specialDefense", "spDefense");
            tryAddStat(values, spread, "spe", "getSpeed", "getSpe", "speed", "spe");

            tryAddStatFromField(values, spread, "hp", "hp");
            tryAddStatFromField(values, spread, "atk", "attack", "atk");
            tryAddStatFromField(values, spread, "def", "defense", "def");
            tryAddStatFromField(values, spread, "spa", "specialAttack", "spAttack", "spa");
            tryAddStatFromField(values, spread, "spd", "specialDefense", "spDefense", "spd");
            tryAddStatFromField(values, spread, "spe", "speed", "spe");

            if (values.size() == 6) {
                return "hp=" + values.get("hp")
                        + ";atk=" + values.get("atk")
                        + ";def=" + values.get("def")
                        + ";spa=" + values.get("spa")
                        + ";spd=" + values.get("spd")
                        + ";spe=" + values.get("spe");
            }

            if (spread instanceof Map<?, ?> map) {
                List<String> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = normalizeStatKey(entry.getKey());
                    String value = normalizeStatValue(entry.getValue());
                    entries.add(key + "=" + value);
                }
                entries.sort(String::compareTo);
                if (!entries.isEmpty()) {
                    return String.join(";", entries);
                }
            }

            String fallback = sanitizeToString(spread);
            return fallback.isBlank() ? "unknown" : fallback;
        }

        private static void tryAddStat(Map<String, String> out, Object spread, String key, String... methodNames) {
            for (String methodName : methodNames) {
                Object value = invokeMatching(spread, methodName);
                if (value != null) {
                    out.put(key, normalizeStatValue(value));
                    return;
                }
            }
        }

        private static void tryAddStatFromField(Map<String, String> out, Object spread, String key, String... fieldNames) {
            if (out.containsKey(key) || spread == null) {
                return;
            }

            Class<?> type = spread.getClass();
            while (type != null) {
                Field[] fields = type.getDeclaredFields();
                for (Field field : fields) {
                    String fieldName = field.getName();
                    for (String candidate : fieldNames) {
                        if (!fieldName.equalsIgnoreCase(candidate)) {
                            continue;
                        }
                        try {
                            field.setAccessible(true);
                            Object value = field.get(spread);
                            if (value != null) {
                                out.put(key, normalizeStatValue(value));
                                return;
                            }
                        } catch (Exception ignored) {
                            // Try next field.
                        }
                    }
                }
                type = type.getSuperclass();
            }
        }

        private static String normalizeStatValue(Object value) {
            if (value == null) {
                return "0";
            }
            if (value instanceof Number number) {
                return String.valueOf(number.intValue());
            }
            return sanitizeToString(value);
        }

        private static String normalizeStatKey(Object key) {
            String normalized = sanitizeToString(key).toLowerCase(Locale.ROOT);
            if (normalized.contains("special") && normalized.contains("attack")) return "spa";
            if (normalized.contains("special") && normalized.contains("def")) return "spd";
            if (normalized.startsWith("hp")) return "hp";
            if (normalized.startsWith("atk") || normalized.contains("attack")) return "atk";
            if (normalized.startsWith("def") || normalized.contains("defense")) return "def";
            if (normalized.startsWith("spe") || normalized.contains("speed")) return "spe";
            return normalized;
        }

        private static String sanitizeToString(Object value) {
            if (value == null) {
                return "";
            }
            return value.toString().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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
