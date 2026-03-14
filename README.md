# Discord Link Mod

NeoForge mod for Minecraft 1.21.1 that forwards Minecraft server activity to a companion Discord bot.

## What This Mod Does

This mod listens for server events and sends them to the Discord relay bot over HTTP.

Current behavior:

- Sends a startup notice when the Minecraft server finishes starting.
- Forwards in-game chat messages to the bot.
- Posts JSON payloads to `http://localhost:3000/mc/chat` by default.

The companion bot project lives in the sibling workspace folder `discordlink-bot`.

## Current Event Flow

The mod currently handles these events:

- `ServerStartedEvent` -> sends `SERVER: @minecraft Server has started.`
- `ServerChatEvent` -> sends `<player name>: <chat message>`

Each event is serialized into JSON like this:

```json
{
	"player": "SERVER",
	"message": "@minecraft Server has started."
}
```

The bot receives that payload and posts it into your configured Discord channel.

## Project Structure

- `src/main/java/com/example/discordlink/DiscordLinkMod.java`: main mod logic and event listeners
- `src/main/templates/META-INF/neoforge.mods.toml`: mod metadata template
- `gradle.properties`: Minecraft, NeoForge, and mod metadata settings

## Requirements

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.219
- The `discordlink-bot` service running and reachable from the Minecraft server

## Setup

1. Start the bot from the `discordlink-bot` project.
2. Configure the bot with a valid Discord bot token and target channel ID.
3. If the bot is not running on the same machine, update `BOT_BASE_URL` in `DiscordLinkMod.java`.
4. Launch the Minecraft server with this mod installed.

## Development Notes

- The bot endpoint is currently hardcoded as `http://localhost:3000` in `DiscordLinkMod.java`.
- HTTP delivery is asynchronous using Java's built-in `HttpClient`.
- JSON escaping is handled manually before the request body is sent.
- The current startup text includes `@minecraft` as plain message text. A real Discord role ping requires bot-side handling with the role mention format.

## Useful Commands

On Windows:

```powershell
.\gradlew.bat runServer
.\gradlew.bat build
```

If dependencies get out of sync:

```powershell
.\gradlew.bat --refresh-dependencies
.\gradlew.bat clean
```

## Planned Features

- Allow Discord members to trigger a Minecraft server restart through bot commands
