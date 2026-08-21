package com.alecsherlock.discordloot;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DiscordLootPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(DiscordLootPlugin.class);
        RuneLite.main(args);
    }
}