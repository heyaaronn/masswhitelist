# MassWhitelist

A Purpur/Paper plugin to whitelist a large list of players at once — from an in-game
command or from a config list. Bad usernames are skipped automatically (the rest still
get added) and written to a report file.

Built against the Paper API (1.21.x); runs on Purpur, which is a Paper fork. Requires
**Java 21**, which is what current 1.21.x servers run on.

> Note on the version: there is no Minecraft "2.26.2". This targets the current 1.21.x
> line. If your server is a different 1.21.x build, just change the `paper-api` version
> in `pom.xml` to match — the API used here is stable across all of 1.21.

## Build

You need JDK 21 and Maven.

```
mvn clean package
```

The finished plugin is `target/MassWhitelist.jar`. Drop it in your server's `plugins/`
folder and restart.

## Usage

Permission: `masswhitelist.use` (default: operators). Command alias: `/mwl`.

- `/masswhitelist add <name1> <name2> ...` — whitelist the listed players. Names can be
  separated by spaces or commas, so you can paste a big list in one go.
- `/masswhitelist config` — whitelist every name in the `players:` list in `config.yml`.
- `/masswhitelist reload` — reload `config.yml` after editing it.

Editing the config list (one name per line):

```yaml
players:
  - Notch
  - jeb_
  - AnotherPlayer
```

Then run `/masswhitelist reload` followed by `/masswhitelist config`.

## How bad usernames are handled

When `verify-with-mojang: true` (the default, for normal online-mode servers):

1. Names are first checked for valid format (3–16 chars, letters/digits/underscore).
2. Remaining names are resolved against Mojang in batches of 10.
3. Real accounts are added to the whitelist by UUID; the others are skipped.
4. Every skipped name is listed with a reason in
   `plugins/MassWhitelist/failed-usernames-<timestamp>.txt`, and a summary is shown
   in chat/console.

Reasons you may see: `INVALID_FORMAT`, `NOT_FOUND` (no such account), or `LOOKUP_ERROR`
(couldn't reach Mojang — re-run those later).

### Offline-mode servers

If your server runs with `online-mode=false`, set `verify-with-mojang: false` in the
config. Mojang verification is impossible offline, so every well-formed name is added
(only format-invalid names get reported).

## Notes

- Whitelisting in online mode is enforced by UUID, which is exactly what this plugin
  writes. The player's name may appear blank in `whitelist.json` until they first join —
  that's cosmetic and does not affect whether they can connect.
- The Mojang lookups run off the main thread, so the server is never frozen, even for
  large lists. A short pause between batches keeps you under Mojang's rate limit.
