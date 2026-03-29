# Discord Link Mod

NeoForge mod for Minecraft 1.21.1 that bridges Minecraft server events and Cobblemon tournament workflows to a companion Discord bot.

## Version

- Current mod version: `1.1.0`
- Minecraft: `1.21.1`
- NeoForge: `21.1.219`

## Features

- Sends server startup notice to Discord.
- Forwards in-game chat messages to Discord.
- Cobblemon team report command (`/registerteam`).
- Tournament system with persistent registration snapshots.
- Team verification checks against registered tournament teams.
- Shared-secret authentication for mod -> bot bridge requests.

## Discord Bridge

The mod sends HTTP `POST` requests to:

- `POST <BOT_BASE_URL>/mc/chat`

JSON payload format:

```json
{
	"player": "SERVER",
	"message": "@minecraft Server has started."
}
```

Auth header sent on every request:

- `X-DiscordLink-Secret: <secret>`

## Bot URL Resolution

The mod resolves bot base URL in this order:

1. Environment variable: `DISCORDLINK_BOT_BASE_URL`
2. JVM property: `-Ddiscordlink.bot.base.url=...`
3. Built-in fallback: `https://dripmon-discord-production.up.railway.app`

## Shared Secret Resolution

The mod resolves shared secret in this order:

1. Environment variable: `DISCORDLINK_SHARED_SECRET`
2. JVM property: `-Ddiscordlink.shared.secret=...`
3. Runtime-generated secret at `config/discordlink-secret.txt`

Admin command:

- `/discordsecret`
	- Prints active secret to server console.
	- Useful when secret is generated at runtime and needs to be copied to bot env config.

## Tournament Commands

### Player Commands

- `/registerteam`
	- Sends current Cobblemon team details to Discord (preview/report behavior).
- `/registerteam <tournament name>`
	- Saves or replaces your current team snapshot for that tournament (until locked).
- `/check <tournament name> <player name>`
	- Compares target player's current team to their registered team for that tournament.
	- If matched, broadcasts pass message to server chat.
- `/unregisterteam <tournament name>`
	- Removes your registration from an unlocked tournament.

### Admin Commands

- `/registertournament <tournament name>`
	- Creates a new tournament.
- `/locktournament <tournament name>`
	- Locks registrations permanently for that tournament.
- `/listtournaments`
	- Lists tournaments with status and registration count.
- `/tournamentinfo <tournament name>`
	- Shows tournament details and registered players.
- `/exporttournament <tournament name>`
	- Exports registration data to text file under `config/discordlink-exports/`.

## Team Matching Rules

`/check` uses order-insensitive matching:

- Pokemon order does not matter.
- Move order does not matter.

Fields used for match identity:

- Species
- Ability
- Held item
- Nature
- Gender
- Form
- IV spread
- EV spread
- Moveset

Ignored in matching:

- Nickname
- Shiny flag

## Persistence Files

- `config/discordlink-secret.txt`
	- Bridge secret (when auto-generated).
- `config/discordlink-tournaments.json`
	- Tournament definitions, lock state, and registrations.
- `config/discordlink-exports/*.txt`
	- Tournament export reports.

## Requirements

- Java 21
- Minecraft 1.21.1 server with NeoForge 21.1.219
- Cobblemon installed on server (required for team commands)
- Companion bot from sibling project: `discordlink-bot`

## Build and Run

On Windows:

```powershell
.\gradlew.bat runServer
.\gradlew.bat build
```

If dependencies need refresh:

```powershell
.\gradlew.bat --refresh-dependencies
.\gradlew.bat clean
```

## Project Structure

- `src/main/java/com/example/discordlink/DiscordLinkMod.java`: main mod logic, commands, bridge calls, Cobblemon reflection
- `src/main/templates/META-INF/neoforge.mods.toml`: mod metadata template
- `gradle.properties`: Minecraft/NeoForge/mod metadata and version values
