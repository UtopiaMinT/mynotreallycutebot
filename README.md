# mynotreallycutebot
Background process to collect data from Wynncraft.

## Features
[x] Log wars
[x] Log territory movements
[x] Record guild and player war stats
[ ] War tracker on Discord
[ ] Track player play time
[ ] Guild XP movement leaderboard

## Running
1. Create `bot.cfg` with the following content and fill in your database credentials.
```
discord.token=
db.host=localhost
db.user=user
db.pass=pass
db.name=name
```
2. Install Maven and Java 8+
3. Run the following in the project root directory:
```
mvn package
java -jar java -jar mynotreallycutebot-1.0-SNAPSHOT-jar-with-dependencies.jar
```
