Discord Loot Notifier for RuneLite

Capture rare OSRS drops, view your exact loadout, inventory, and drop-moment screenshots, and beam them straight to your clan or personal Discord channel!

DiscordLootPlugin is a custom RuneLite plugin built for OSRS players and clan communities. Whenever a valuable NPC or PvP drop occurs, it filters against your criteria, snaps the action, and formats a clean, information-rich Discord webhook message.

Features

Smart Filtering: Filter notifications by comma-separated rare item IDs and/or a minimum Grand Exchange (GE) value threshold.

Drop-Moment Screenshots: Automatically captures the game client canvas on the exact frame the loot drops.

Worn Equipment Snapshots: Extracts your active gear loadout and renders a clean, dedicated equipment icon panel (fully independent of what tab you currently have open!).

Inventory Capture: Scans your inventory at the moment of the drop and attaches a formatted list of all items and quantities.

PvP & PVM Support: Toggle tracking for standard NPC drops or optional player kills (PlayerLootReceived).

Java 11 Compatible: Fully tuned to compile and run seamlessly within the standard RuneLite build pipeline.

Configuration

Once the plugin is installed and loaded in RuneLite, head over to the configuration panel to customize:

Discord Webhook URL: The destination webhook endpoint for your channel.

Rare Item IDs: Specific OSRS item IDs to watch (leave blank to track all drops meeting GE value requirements).

Min GE Value: Minimum total drop value threshold in gp.

Send Worn Equipment List / Inventory List: Toggle text readouts of your gear and bag contents.

Capture & Attach Screenshot: Toggle canvas snapshot uploads.

Render Equipment Image: Generates a clean visual grid of your worn gear.

Building and Installing Locally

Clone this repository into your local workspace:

git clone https://github.com/your-username/discord-loot-plugin.git
cd discord-loot-plugin

Open the project folder in IntelliJ IDEA as a Gradle project.

Use the Gradle tool window on the right side to run:

./gradlew run

This will compile the plugin and launch a developer-mode RuneLite client with your plugin enabled.

Contributing & License

Feel free to fork, customize, or submit pull requests for your clan's specific needs! Licensed under the MIT License.
