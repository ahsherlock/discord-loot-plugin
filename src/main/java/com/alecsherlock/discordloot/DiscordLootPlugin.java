package com.alecsherlock.discordloot;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.DrawManager;

/**
 * Discord Loot Notifier.
 *
 * <p>Listens for NPC (and optionally PvP) loot drops, filters them against a configurable
 * "rare item" list / minimum GE value, captures the client canvas on the next rendered
 * frame, extracts the player's worn equipment with OSRS item IDs, and posts a structured
 * payload plus the screenshot to a Discord webhook.</p>
 *
 * <p>This plugin only READS game state and renders/captures the client canvas. It performs
 * no input automation. Where RuneLite does not expose a stable way to force the equipment
 * tab open, the plugin captures the current canvas AND can render a dedicated equipment
 * snapshot image from the worn item icons. See README.md for scope and limitations.</p>
 */
@Slf4j
@net.runelite.client.plugins.PluginDescriptor(
        name = "Discord Loot Notifier",
        description = "Captures a screenshot and worn equipment on rare drops, then posts to Discord.",
        tags = {"discord", "loot", "screenshot", "equipment", "webhook"}
)
public class DiscordLootPlugin extends net.runelite.client.plugins.Plugin
{
    private static final String CANVAS_ATTACHMENT = "drop-moment.png";
    private static final String EQUIPMENT_ATTACHMENT = "equipment-screen.png";
    private static final int CELL = 36;
    private static final int PADDING = 6;
    private static final int COLS = 4;

    @Inject
    private Client client;

    @Inject
    private DrawManager drawManager;

    @Inject
    private net.runelite.client.game.ItemManager itemManager;

    @Inject
    private DiscordLootConfig config;

    // All network/encoding work happens off the client thread.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void shutDown()
    {
        executor.shutdown();
    }

    @com.google.inject.Provides
    DiscordLootConfig provideConfig(net.runelite.client.config.ConfigManager configManager)
    {
        return configManager.getConfig(DiscordLootConfig.class);
    }

    /* ------------------------------------------------------------------ */
    /*  Loot events                                                       */
    /* ------------------------------------------------------------------ */

    @net.runelite.client.eventbus.Subscribe
    public void onNpcLootReceived(net.runelite.client.events.NpcLootReceived event)
    {
        final NPC npc = event.getNpc();
        final String sourceName = npc != null ? npc.getName() : "Unknown NPC";
        handleLoot(sourceName, event.getItems());
    }

    @net.runelite.client.eventbus.Subscribe
    public void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
    {
        if (!config.notifyOnPvp())
        {
            return;
        }
        final Player player = event.getPlayer();
        final String sourceName = player != null ? player.getName() : "Unknown Player";
        handleLoot(sourceName, event.getItems());
    }

    private void handleLoot(String sourceName, Collection<ItemStack> items)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }

        // Loot event handlers run on the client thread, so reading ItemContainer here is safe.
        final Set<Integer> rareFilter = parseItemIds(config.rareItemIds());
        final List<DropItem> drops = new ArrayList<>();
        int totalGe = 0;
        for (ItemStack stack : items)
        {
            final int id = stack.getId();
            final int qty = stack.getQuantity();
            final int canonicalId = itemManager.canonicalize(id);
            final ItemComposition comp = itemManager.getItemComposition(id);
            final String name = comp != null ? comp.getName() : "Unknown item";
            final int ge = itemManager.getItemPrice(id) * qty;
            totalGe += ge;
            drops.add(new DropItem(id, canonicalId, name, qty, ge));
        }

        final boolean matchesIdFilter = rareFilter.isEmpty()
                || drops.stream().anyMatch(d -> rareFilter.contains(d.itemId) || rareFilter.contains(d.canonicalItemId));
        final boolean matchesValueFilter = config.minGeValue() <= 0 || totalGe >= config.minGeValue();
        if (!matchesIdFilter || !matchesValueFilter)
        {
            return;
        }

        // Snapshot config values on the client thread so background work never reads config off-thread.
        final WebhookJob job = new WebhookJob();
        job.webhookUrl = config.webhookUrl();
        job.includeEquipmentList = config.includeEquipmentList();
        job.includeInventoryList = config.includeInventoryList();
        job.includeScreenshot = config.includeScreenshot();
        job.renderEquipmentImage = config.renderEquipmentImage();

        // Grab the local player's username safely on the client thread
        Player localPlayer = client.getLocalPlayer();
        job.playerName = (localPlayer != null && localPlayer.getName() != null) ? localPlayer.getName() : "Unknown";

        final EquipmentSnapshot equipment = readEquipment();
        final InventorySnapshot inventory = readInventory();

        job.payload = buildPayload(sourceName, drops, totalGe, equipment, inventory, job);
        job.equipment = equipment;
        job.inventory = inventory;

        // Pre-load item icons on the client thread. getImage() may return a blank image if
        // called off the game thread, so we fetch the BufferedImages here and reuse them later.
        if (job.renderEquipmentImage)
        {
            job.icons = new java.util.HashMap<>();
            for (EquippedItem e : equipment.items)
            {
                if (!job.icons.containsKey(e.canonicalItemId))
                {
                    job.icons.put(e.canonicalItemId, itemIcon(e.canonicalItemId, e.quantity));
                }
            }
        }

        if (job.includeScreenshot)
        {
            // Capture the next rendered frame (the loot moment) and send once it's ready.
            requestScreenshot(job);
        }
        else
        {
            executor.submit(() -> sendToDiscord(job, null));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Screenshot capture                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Captures the next rendered client frame using
     * {@link DrawManager#requestNextFrameListener}, the supported path RuneLite's own
     * Screenshot plugin uses. Raw Canvas.paint() can return a stale/blank image,
     * especially with the GPU plugin enabled.
     */
    private void requestScreenshot(WebhookJob job)
    {
        drawManager.requestNextFrameListener((Image image) ->
        {
            final BufferedImage copy = copyImage(image);
            executor.submit(() -> sendToDiscord(job, copy));
        });
    }

    private static BufferedImage copyImage(Image src)
    {
        if (src == null)
        {
            return null;
        }
        final int width = src.getWidth(null);
        final int height = src.getHeight(null);
        if (width <= 0 || height <= 0)
        {
            return null;
        }
        final BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try
        {
            g.drawImage(src, 0, 0, null);
        }
        finally
        {
            g.dispose();
        }
        return out;
    }

    /**
     * Renders a dedicated "equipment screen" image from the worn item icons. This is the
     * reliable, Hub-safe way to show the player's loadout at the moment of the drop,
     * independent of which tab is currently open.
     */
    private BufferedImage renderEquipmentImage(EquipmentSnapshot equipment, java.util.Map<Integer, BufferedImage> icons)
    {
        if (equipment == null || equipment.items.isEmpty())
        {
            return null;
        }
        final int rows = (int) Math.ceil(equipment.items.size() / (double) COLS);
        final int w = COLS * CELL + (COLS + 1) * PADDING;
        final int h = rows * CELL + (rows + 1) * PADDING + 18;
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        try
        {
            g.setColor(new Color(30, 30, 35));
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.drawString("Worn equipment", PADDING, 13);

            int i = 0;
            for (EquippedItem e : equipment.items)
            {
                final int col = i % COLS;
                final int row = i / COLS;
                final int x = PADDING + col * (CELL + PADDING);
                final int y = 18 + PADDING + row * (CELL + PADDING);
                g.setColor(new Color(55, 55, 62));
                g.fillRect(x, y, CELL, CELL);
                final BufferedImage icon = icons != null ? icons.get(e.canonicalItemId) : null;
                if (icon != null)
                {
                    g.drawImage(icon, x + (CELL - icon.getWidth()) / 2, y + (CELL - icon.getHeight()) / 2, null);
                }
                g.setColor(Color.LIGHT_GRAY);
                g.setFont(new Font("SansSerif", Font.PLAIN, 9));
                g.drawString(e.slot.substring(0, Math.min(e.slot.length(), 4)), x + 1, y + CELL - 2);
                i++;
            }
        }
        finally
        {
            g.dispose();
        }
        return out;
    }

    private BufferedImage itemIcon(int itemId, int qty)
    {
        try
        {
            // AsyncBufferedImage extends BufferedImage; fetched on the client thread it is
            // already populated for cached items.
            return itemManager.getImage(itemId, qty, false);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Inventory / Equipment extraction                                   */
    /* ------------------------------------------------------------------ */

    private EquipmentSnapshot readEquipment()
    {
        final List<EquippedItem> worn = new ArrayList<>();
        final ItemContainer equipmentContainer = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipmentContainer == null)
        {
            return new EquipmentSnapshot(worn);
        }
        final Item[] items = equipmentContainer.getItems();
        if (items == null)
        {
            return new EquipmentSnapshot(worn);
        }
        for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
        {
            final int idx = slot.getSlotIdx();
            if (idx >= items.length)
            {
                continue;
            }
            final Item item = items[idx];
            if (item == null || item.getId() < 1)
            {
                continue;
            }
            final int rawId = item.getId();
            final int canonicalId = itemManager.canonicalize(rawId);
            final ItemComposition comp = itemManager.getItemComposition(rawId);
            final String name = comp != null ? comp.getName() : "Unknown";
            final int qty = item.getQuantity();
            worn.add(new EquippedItem(slot.name(), rawId, canonicalId, name, qty));
        }
        return new EquipmentSnapshot(worn);
    }

    private InventorySnapshot readInventory()
    {
        final List<InventoryItem> inv = new ArrayList<>();
        final ItemContainer inventoryContainer = client.getItemContainer(InventoryID.INVENTORY);
        if (inventoryContainer == null)
        {
            return new InventorySnapshot(inv);
        }
        final Item[] items = inventoryContainer.getItems();
        if (items == null)
        {
            return new InventorySnapshot(inv);
        }
        for (int i = 0; i < items.length; i++)
        {
            final Item item = items[i];
            if (item == null || item.getId() < 0)
            {
                continue;
            }
            final int rawId = item.getId();
            final int canonicalId = itemManager.canonicalize(rawId);
            final ItemComposition comp = itemManager.getItemComposition(rawId);
            final String name = comp != null ? comp.getName() : "Unknown";
            final int qty = item.getQuantity();
            inv.add(new InventoryItem(i, rawId, canonicalId, name, qty));
        }
        return new InventorySnapshot(inv);
    }

    /* ------------------------------------------------------------------ */
    /*  Payload + Discord                                                  */
    /* ------------------------------------------------------------------ */

    private String buildPayload(String sourceName, List<DropItem> drops, int totalGe, EquipmentSnapshot equipment, InventorySnapshot inventory, WebhookJob job)
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":\"**Rare drop** from ").append(escape(sourceName)).append("\",");
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"Loot Notification\",");
        sb.append("\"color\":16753920,");

        final StringBuilder desc = new StringBuilder();
        desc.append("**Player:** ").append(escape(job.playerName)).append("\\n");
        desc.append("**Source:** ").append(escape(sourceName)).append("\\n");
        desc.append("**Total GE value:** ").append(totalGe).append(" gp\\n\\n");
        desc.append("**Drops:**\\n");
        for (DropItem d : drops)
        {
            desc.append("- ").append(escape(d.name)).append(" x").append(d.quantity)
                    .append(" (id ").append(d.itemId).append(", ").append(d.geValue).append(" gp)\\n");
        }
        sb.append("\"description\":\"").append(desc).append("\",");

        final List<String> attachments = new ArrayList<>();
        if (job.includeScreenshot)
        {
            attachments.add(CANVAS_ATTACHMENT);
        }
        if (job.renderEquipmentImage)
        {
            attachments.add(EQUIPMENT_ATTACHMENT);
        }

        final StringBuilder fields = new StringBuilder();
        if (job.includeEquipmentList && equipment != null && !equipment.items.isEmpty())
        {
            fields.append("{\"name\":\"Worn equipment\",\"value\":\"");
            for (int i = 0; i < equipment.items.size(); i++)
            {
                if (i > 0)
                {
                    fields.append("\\n");
                }
                final EquippedItem e = equipment.items.get(i);
                fields.append(escape(e.slot)).append(": ").append(escape(e.name))
                        .append(" x").append(e.quantity).append(" (raw ").append(e.rawItemId)
                        .append(" / canonical ").append(e.canonicalItemId).append(")");
            }
            fields.append("\"}");
        }

        if (job.includeInventoryList && inventory != null && !inventory.items.isEmpty())
        {
            if (fields.length() > 0)
            {
                fields.append(","); // Separate the fields in the JSON array
            }

            fields.append("{\"name\":\"Inventory\",\"value\":\"");
            StringBuilder invStr = new StringBuilder();

            for (int i = 0; i < inventory.items.size(); i++)
            {
                if (i > 0)
                {
                    invStr.append("\\n");
                }
                final InventoryItem invItem = inventory.items.get(i);
                invStr.append("Slot ").append(invItem.slotIdx + 1).append(": ").append(escape(invItem.name))
                        .append(" x").append(invItem.quantity);
            }

            // Discord has a strict 1024 character limit for field values.
            // Truncate it if it exceeds this to avoid HTTP 400 Bad Request errors.
            String safeInvStr = invStr.toString();
            if (safeInvStr.length() > 1020)
            {
                safeInvStr = safeInvStr.substring(0, 1020) + "...";
            }
            fields.append(safeInvStr).append("\"}");
        }

        sb.append("\"fields\":[").append(fields).append("],");

        // Only reference an attachment image when we will actually attach one.
        // Prefer the rendered equipment screen as the primary embed image; fall back to
        // the canvas screenshot of the drop moment.
        if (!attachments.isEmpty())
        {
            final String primary = job.renderEquipmentImage ? EQUIPMENT_ATTACHMENT : attachments.get(0);
            sb.append("\"image\":{\"url\":\"attachment://").append(primary).append("\"},");
        }
        sb.append("\"footer\":{\"text\":\"RuneLite Discord Loot Notifier\"}");
        sb.append("}]}");
        return sb.toString();
    }

    private void sendToDiscord(WebhookJob job, BufferedImage canvasImage)
    {
        if (job.webhookUrl == null || job.webhookUrl.isBlank())
        {
            log.warn("Discord webhook URL is not set; skipping upload.");
            return;
        }

        final byte[] canvasPng = (job.includeScreenshot && canvasImage != null) ? toPng(canvasImage) : null;
        final byte[] equipmentPng = job.renderEquipmentImage ? toPng(renderEquipmentImage(job.equipment, job.icons)) : null;
        final String boundary = "----RLBoundary" + Long.toHexString(System.nanoTime());

        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) new URL(job.webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);

            try (OutputStream out = conn.getOutputStream())
            {
                writeField(out, boundary, "payload_json", "application/json", job.payload.getBytes(StandardCharsets.UTF_8));

                int fileIndex = 0;
                if (canvasPng != null && canvasPng.length > 0)
                {
                    writeFile(out, boundary, "files[" + fileIndex + "]", CANVAS_ATTACHMENT, "image/png", canvasPng);
                    fileIndex++;
                }
                if (equipmentPng != null && equipmentPng.length > 0)
                {
                    writeFile(out, boundary, "files[" + fileIndex + "]", EQUIPMENT_ATTACHMENT, "image/png", equipmentPng);
                }
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            final int code = conn.getResponseCode();
            if (code == 429)
            {
                final String retryAfter = conn.getHeaderField("Retry-After");
                log.warn("Discord rate-limited (429). Retry-After: {}", retryAfter);
            }
            else if (code < 200 || code >= 300)
            {
                try (InputStream err = conn.getErrorStream())
                {
                    log.warn("Discord webhook returned {}: {}", code, err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        catch (IOException e)
        {
            log.error("Failed to post to Discord webhook", e);
        }
        finally
        {
            if (conn != null)
            {
                conn.disconnect();
            }
        }
    }

    private static byte[] toPng(BufferedImage image)
    {
        if (image == null)
        {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            javax.imageio.ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
        catch (IOException e)
        {
            return new byte[0];
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String contentType, byte[] value) throws IOException
    {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(OutputStream out, String boundary, String fieldName, String filename, String contentType, byte[] value) throws IOException
    {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static Set<Integer> parseItemIds(String csv)
    {
        if (csv == null || csv.isBlank())
        {
            return new HashSet<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .flatMap(s ->
                {
                    try
                    {
                        return java.util.stream.Stream.of(Integer.parseInt(s));
                    }
                    catch (NumberFormatException e)
                    {
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toCollection(HashSet::new));
    }

    /* ------------------------------------------------------------------ */
    /*  DTOs                                                              */
    /* ------------------------------------------------------------------ */

    private static final class DropItem
    {
        final int itemId;
        final int canonicalItemId;
        final String name;
        final int quantity;
        final int geValue;

        DropItem(int itemId, int canonicalItemId, String name, int quantity, int geValue)
        {
            this.itemId = itemId;
            this.canonicalItemId = canonicalItemId;
            this.name = name;
            this.quantity = quantity;
            this.geValue = geValue;
        }
    }

    private static final class EquippedItem
    {
        final String slot;
        final int rawItemId;
        final int canonicalItemId;
        final String name;
        final int quantity;

        EquippedItem(String slot, int rawItemId, int canonicalItemId, String name, int quantity)
        {
            this.slot = slot;
            this.rawItemId = rawItemId;
            this.canonicalItemId = canonicalItemId;
            this.name = name;
            this.quantity = quantity;
        }
    }

    private static final class EquipmentSnapshot
    {
        final List<EquippedItem> items;

        EquipmentSnapshot(List<EquippedItem> items)
        {
            this.items = items;
        }
    }

    private static final class InventoryItem
    {
        final int slotIdx;
        final int rawItemId;
        final int canonicalItemId;
        final String name;
        final int quantity;

        InventoryItem(int slotIdx, int rawItemId, int canonicalItemId, String name, int quantity)
        {
            this.slotIdx = slotIdx;
            this.rawItemId = rawItemId;
            this.canonicalItemId = canonicalItemId;
            this.name = name;
            this.quantity = quantity;
        }
    }

    private static final class InventorySnapshot
    {
        final List<InventoryItem> items;

        InventorySnapshot(List<InventoryItem> items)
        {
            this.items = items;
        }
    }

    /** Snapshot of config values captured on the client thread and handed to background work. */
    private static final class WebhookJob
    {
        String webhookUrl;
        String payload;
        String playerName;
        EquipmentSnapshot equipment;
        InventorySnapshot inventory;
        java.util.Map<Integer, BufferedImage> icons;
        boolean includeEquipmentList;
        boolean includeInventoryList;
        boolean includeScreenshot;
        boolean renderEquipmentImage;
    }
}