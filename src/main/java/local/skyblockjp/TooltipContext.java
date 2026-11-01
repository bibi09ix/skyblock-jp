package local.skyblockjp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Selects one dictionary scope. Menu names never turn actual equipment into UI text. */
public final class TooltipContext {
    public enum Kind { ITEM, UI, UNKNOWN }
    public enum Location { PLAYER, CONTAINER, UNKNOWN }
    public record Evidence(String screenClass, String containerTitle, Location location, boolean slotMatches,
                           String skyblockItemId, String minecraftItemId, int slotIndex, int containerSize) {}
    public record Decision(Kind kind, String reason, Map<String, Object> evidence) {
        public String channel() { return kind == Kind.UI ? "ui" : "item"; }
    }
    private static final Pattern RARITY = Pattern.compile(
        "^(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|SUPREME)(?: DUNGEON)?(?: [A-Z][A-Z -]*)?$"
    );
    private static final Pattern MINION_MENU = Pattern.compile("[A-Z][A-Za-z'’ -]{0,70} Minion [IVXLCDM]+");
    private static final Pattern LOCKED_SLOT_INSTRUCTION = Pattern.compile("[A-Z][A-Za-z ]{1,40} unlocked at (?:tier|level) [IVXLCDM]+");
    private static final Pattern KNOWN_MENU = Pattern.compile(
        "(?i)^(?:skyblock menu|your (?:skyblock )?profile|profile management|profiles|co-op|pets|pet (?:care|sitter|rules|score)|"
        + "your skills|skills|(?:.* )?collections?|recipe book|(?:.* )?recipes?|trades|quest log|quests|calendar(?: and events)?|events|"
        + "(?:your )?stats(?: breakdown)?|stats (?:and|&) equipment|(?:combat|gathering|wisdom|miscellaneous|rift) stats|confirm(?:ation)?(?: .*)?|"
        + "storage|ender chest(?: \\d+)?|backpacks?(?: \\d+)?|loadouts?|wardrobe|armor sets|outfits|equipment(?:.*)?|(?:accessory|potion|fishing|sack of sacks|rift|tuning) bag|quiver|sacks|sack of sacks|"
        + "(?:.* )?sack|personal (?:compactor|deletor)(?: \\d+)?|(?:.* )?auction(?: house|s| browser)?|bazaar(?:.*)?|bank(?:.*)?|"
        + "(?:.* )?shop|(?:.* )?merchant|(?:.* )?slayer(?:.*)?|heart of the mountain|(?:.* )?hotm(?:.*)?|forge(?:.*)?|the forge|"
        + "(?:.* )?commission(?:s)?|mining(?:.*)?|gemstone(?:.*)?|(?:.* )?reforg(?:e|ing)(?:.*)?|enchant(?:ment|ing)?(?:.*)?|"
        + "the hex|hex(?:.*)?|(?:.* )?experiment(?:ation)?(?:.*)?|anvil|crafting table|salvage(?:.*)?|essence(?:.*)?|"
        + "(?:.* )?dungeon(?:s| hub| classes| rewards| party| finder)?|catacombs(?:.*)?|(?:.* )?class(?:es)?|"
        + "garden(?:.*)?|desk|composter(?:.*)?|greenhouse(?:.*)?|(?:.* )?crop(?:s| milestones| upgrades)?|visitor(?:s| offers)?|"
        + "(?:.* )?farming(?:.*)?|hunting(?:.*)?|hunt(?:.*)?|(?:.* )?hunt(?:ing)? toolkit|farming toolkit|"
        + "skyblock level(?:s|ing)?|leveling(?:.*)?|bestiary(?:.*)?|(?:.* )?museum(?:.*)?|(?:.* )?power(?:s| stones| tuning)?|"
        + "(?:.* )?tuning(?:.*)?|abiphone(?:.*)?|contacts|(?:.* )?rift(?:.*)?|(?:.* )?chocolate(?:.*)?|"
        + "(?:.* )?mayor(?:.*)?|election(?:.*)?|community shop|bits shop|(?:.* )?bingo(?:.*)?|(?:.* )?season(?:al)?(?:.*)?|"
        + "(?:.* )?event(?:.*)?|(?:.* )?reward(?:s)?|fast travel|warp(?:.*)?|island(?:.*)?|(?:.* )?settings|"
        + "(?:.* )?upgrade(?:s)?|(?:.* )?milestone(?:s)?|(?:.* )?achievement(?:s)?|(?:.* )?trophy fish(?:.*)?|"
        + "(?:.* )?fishing(?:.*)?|(?:.* )?level(?:s)?|(?:.* )?tiers?|(?:.* )?perks?|(?:.* )?essence shop)$"
    );

    private TooltipContext() { }

    /** Caller must already have established the configured Hypixel/SkyBlock connection. */
    public static Kind classify(String containerTitle, boolean hoveredPlayerInventory,
            String skyblockItemId, List<String> tooltipLines) {
        // The legacy API has no unknown-slot state: false explicitly means a matching container slot.
        return analyze(new Evidence("legacy", containerTitle, hoveredPlayerInventory ? Location.PLAYER : Location.CONTAINER,
            true, skyblockItemId, "", -1, 0), tooltipLines).kind();
    }

    public static boolean trustedMenu(String title) {
        if (title == null) return false;
        String clean = cleanTitle(title);
        return !clean.isEmpty() && clean.length() <= 120 && (KNOWN_MENU.matcher(clean).matches() || statBreakdownMenu(clean));
    }
    private static String cleanTitle(String title) { return LoreParser.clean(title).replaceFirst("^\\(\\d+/\\d+\\)\\s*", "").strip(); }
    private static boolean statBreakdownMenu(String title) {
        return cleanTitle(title).matches("^(?:[^A-Za-z0-9\\s]+\\s*)?[A-Z][A-Za-z'’ -]{0,65}\\s*[➜→›»]\\s*(?:Flat|Additive|Multiplicative|Base|Total) Bonuses$");
    }

    /** Evidence is retained even when the conservative dictionary fallback is item. */
    public static Decision analyze(Evidence raw, List<String> tooltipLines) {
        Evidence e = raw == null ? new Evidence("", "", Location.UNKNOWN, false, "", "", -1, 0) : raw;
        var proof = new LinkedHashMap<String, Object>();
        proof.put("screenClass", text(e.screenClass())); proof.put("containerTitle", text(e.containerTitle()));
        proof.put("location", e.location() == null ? Location.UNKNOWN.name() : e.location().name());
        proof.put("slotMatches", e.slotMatches()); proof.put("slotIndex", e.slotIndex()); proof.put("containerSize", e.containerSize());
        proof.put("skyblockItemId", text(e.skyblockItemId())); proof.put("minecraftItemId", text(e.minecraftItemId()));
        List<String> lines = tooltipLines == null ? List.of() : tooltipLines.stream().map(LoreParser::clean).toList();
        String itemProof = itemProof(lines);
        boolean trusted = trustedMenu(e.containerTitle());
        boolean statBreakdown = statBreakdownMenu(e.containerTitle());
        boolean control = lines.stream().anyMatch(TooltipContext::controlEvidence);
        boolean carrier = !lines.isEmpty() && controlCarrier(lines.getFirst(), e.minecraftItemId());
        boolean lockedInstruction = lockedSlotInstruction(e, lines);
        int stats = statRows(lines);
        proof.put("trustedMenu", trusted); proof.put("statBreakdownMenu", statBreakdown); proof.put("controlEvidence", control); proof.put("controlCarrierEvidence", carrier); proof.put("numericStatRows", stats);
        proof.put("tooltipTitle", lines.isEmpty() ? "" : lines.getFirst());
        proof.put("menuInstructionEvidence", lockedInstruction);
        Kind kind; String reason;
        if (lines.isEmpty()) { kind = Kind.UNKNOWN; reason = "EMPTY_TOOLTIP"; }
        else if (e.location() == Location.PLAYER) { kind = Kind.ITEM; reason = "PLAYER_INVENTORY"; }
        else if (!text(e.skyblockItemId()).isBlank()) { kind = Kind.ITEM; reason = "SKYBLOCK_ITEM_ID"; }
        else if (!itemProof.isEmpty()) { kind = Kind.ITEM; reason = itemProof; }
        else if (lockedInstruction) { kind = Kind.UI; reason = "MINION_LOCKED_SLOT_INSTRUCTION"; }
        else if (e.location() == Location.CONTAINER && e.slotMatches() && trusted) {
            kind = Kind.UI; reason = statBreakdown ? "CONTAINER_STAT_BREAKDOWN" : "TRUSTED_MENU_CONTROL";
        } else if (e.location() == Location.CONTAINER && e.slotMatches() && control) {
            kind = Kind.UI; reason = "CONTAINER_CONTROL_PROMPT";
        } else if (e.location() == Location.CONTAINER && e.slotMatches() && carrier) {
            kind = Kind.UI; reason = "CONTAINER_CONTROL_CARRIER";
        } else if (stats >= 2) { kind = Kind.ITEM; reason = "STAT_ROWS_FALLBACK"; }
        else { kind = Kind.UNKNOWN; reason = "INSUFFICIENT_CONTEXT"; }
        proof.put("tooltipKind", kind.name());
        return new Decision(kind, reason, Map.copyOf(proof));
    }

    private static String text(String value) { return value == null ? "" : value; }
    /** A lone instruction is not an item name merely because it is the first row.
     * Real item identity and player-inventory evidence still take precedence.
     */
    private static boolean lockedSlotInstruction(Evidence e, List<String> lines) {
        return e.location() == Location.CONTAINER && e.slotMatches() && text(e.skyblockItemId()).isBlank()
            && text(e.minecraftItemId()).matches("minecraft:[a-z_]+_stained_glass_pane")
            && MINION_MENU.matcher(cleanTitle(e.containerTitle())).matches()
            && lines.size() == 1 && LOCKED_SLOT_INSTRUCTION.matcher(lines.getFirst()).matches();
    }
    private static boolean controlEvidence(String line) {
        return line.matches("(?i)^(?:(?:Shift |Ctrl\\+)?(?:Left|Right)[ -]click to (?:view|open|select|go|switch|claim|trade|buy|sell|hire|remove|convert|summon|toggle)|Click\\b.*\\b(?:to|for)\\b|Also accessible via|Selected (?:pet|class|slot):).*" );
    }
    private static boolean controlCarrier(String label, String itemId) {
        String carrier = text(itemId).toLowerCase(java.util.Locale.ROOT).replaceFirst("^minecraft:", "");
        if (label.equalsIgnoreCase("Close")) return carrier.equals("barrier");
        if (label.matches("(?i)^(?:Go Back|Back|Next Page|Previous Page|Return)$"))
            return carrier.matches("(?:spectral_|tipped_)?arrow");
        if (label.matches("(?i)^(?:Confirm|Cancel)$"))
            return carrier.matches("(?:green|lime|red)_(?:terracotta|stained_glass_pane|dye)") || carrier.equals("barrier");
        return false;
    }
    private static String itemProof(List<String> lines) {
        if (lines.isEmpty()) return "";
        if (lines.getFirst().matches(".*\\[Lvl \\d+\\].*")) return "PET_LEVEL_NAME";
        for (String line : lines) {
            if (line.matches("(?i)^skyblock:[A-Z0-9_]+$")) return "SKYBLOCK_ITEM_DEBUG_ID";
            if (RARITY.matcher(line).matches()) return "RARITY_LINE";
            if (line.matches("(?i)^(?:Combat|Mining|Farming|Fishing|Foraging|Alchemy|Enchanting|All Skills) Pet$")) return "PET_CATEGORY";
        }
        return "";
    }
    private static int statRows(List<String> lines) {
        int count = 0;
        for (String line : lines) if (LoreParser.numericStat(line)) count++;
        return count;
    }

}
