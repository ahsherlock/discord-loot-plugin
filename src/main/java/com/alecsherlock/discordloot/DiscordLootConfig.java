package com.alecsherlock.discordloot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Configuration for the Discord Loot Notifier.
 */
@ConfigGroup("discordloot")
public interface DiscordLootConfig extends Config
{
    @ConfigItem(
            keyName = "webhookUrl",
            name = "Discord webhook URL",
            description = "The Discord webhook URL that drop notifications are posted to."
    )
    default String webhookUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "rareItemIds",
            name = "Rare item IDs",
            description = "Comma-separated OSRS item IDs that should trigger a notification (e.g. 4151, 11286, 11785). Leave blank to notify on every drop."
    )
    default String rareItemIds()
    {
        return "";
    }

    @ConfigItem(
            keyName = "minGeValue",
            name = "Min GE value",
            description = "Notify when total drop GE value is at least this amount. 0 disables the value check."
    )
    default int minGeValue()
    {
        return 0;
    }

    @ConfigItem(
            keyName = "includeEquipmentList",
            name = "Send worn equipment list",
            description = "Include a JSON list of your currently worn items and their OSRS item IDs in the payload."
    )
    default boolean includeEquipmentList()
    {
        return true;
    }

    @ConfigItem(
            keyName = "includeInventoryList",
            name = "Send inventory list",
            description = "Include a text list of your current inventory items in the Discord payload."
    )
    default boolean includeInventoryList()
    {
        return true;
    }

    @ConfigItem(
            keyName = "includeScreenshot",
            name = "Capture & attach screenshot",
            description = "Capture the client canvas on the next render frame after the drop and upload it as an image attachment."
    )
    default boolean includeScreenshot()
    {
        return true;
    }

    @ConfigItem(
            keyName = "renderEquipmentImage",
            name = "Render equipment image",
            description = "Render a dedicated equipment-screen image from your worn item icons and upload it alongside the screenshot. Reliable and tab-independent (recommended)."
    )
    default boolean renderEquipmentImage()
    {
        return true;
    }

    @ConfigItem(
            keyName = "notifyOnPvp",
            name = "Also notify on PvP kills",
            description = "Also fire on PlayerLootReceived (PvP loot) in addition to NPC loot."
    )
    default boolean notifyOnPvp()
    {
        return false;
    }
}