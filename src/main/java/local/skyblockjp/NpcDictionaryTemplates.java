package local.skyblockjp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.*;

/** Three reviewed dynamic shapes; editorial brackets elsewhere never become wildcards. */
final class NpcDictionaryTemplates {
    static final String PLAYER_CONTEXT = "localPlayerName";
    private static final String INTEGER = "(?:[1-9][0-9]{0,8}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,2})";
    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private record Approval(
            String speaker,
            String source,
            String slot,
            String type,
            String sourceHash,
            String targetHash) {}

    private static final List<Approval> APPROVALS =
            List.of(
                    new Approval(
                            "Carpenter",
                            "Hi, [player]! Welcome to the Furniture Shop.",
                            "[player]",
                            "player_name",
                            "800755a3f59329847825b9bd2099c6ae9e12f0df54573861dbc881f8efed3d04",
                            "7e0704942989c00b9cde4e59e42a0d98e7a60799a302861a82d65814eaec439e"),
                    new Approval(
                            "Andrew",
                            "Did you know that SkyBlock has been running for [number] in-game months already? Time flies.",
                            "[number]",
                            "positive_integer",
                            "f0f9e70cfa3d84029659e8f6d189cc9309d6a180fd679589be0969685e1f6bc8",
                            "1972d191ad66dab96faeb4d3bd4b84818781b94757c98abfdac765e20c1c37c2"),
                    new Approval(
                            "Fetchur",
                            "i need [quantity] pls",
                            "[quantity]",
                            "positive_integer",
                            "62b7d2508ed9148c5da215896ce3aceb1c966ac10df22ffcb5fd86f706b43121",
                            "ee41a38297d76a630cc5e8662d8381048132968369530d321f9fd63b29cba9f9"));

    private record Rule(
            Approval approval, String target, List<String> ids, Pattern pattern, int targetStart) {}

    private final Map<String, List<Rule>> bySpeaker;

    private NpcDictionaryTemplates(Map<String, List<Rule>> rules) {
        bySpeaker = Map.copyOf(rules);
    }

    static NpcDictionaryTemplates create(List<DictionaryMatching.Seed> seeds) {
        var denied = new HashSet<String>();
        for (var seed : seeds)
            if (seed.match() == null) denied.add(key(seed.speaker(), seed.source()));
        var result = new HashMap<String, List<Rule>>();
        for (var approval : APPROVALS) {
            if (denied.contains(key(approval.speaker, approval.source))) continue;
            for (var seed : seeds) {
                if (!seed.channel().equals("npc")
                        || !seed.speaker().equals(approval.speaker)
                        || !seed.source().equals(approval.source)
                        || seed.match() == null) continue;
                var match = seed.match();
                String target = match.text();
                if (match.keepOriginal()
                        || !hash(seed.source()).equals(approval.sourceHash)
                        || !hash(target).equals(approval.targetHash)) continue;
                int from = seed.source().indexOf(approval.slot), to = target.indexOf(approval.slot);
                if (from < 0
                        || to < 0
                        || seed.source().indexOf(approval.slot, from + 1) >= 0
                        || target.indexOf(approval.slot, to + 1) >= 0) continue;
                String shape = seed.source();
                if (shape.endsWith(".") && !shape.endsWith(".."))
                    shape = shape.substring(0, shape.length() - 1);
                String regex =
                        "\\A"
                                + Pattern.quote(shape.substring(0, from))
                                + "(?<value>"
                                + (approval.type.equals("player_name") ? PLAYER.pattern() : INTEGER)
                                + ")"
                                + Pattern.quote(shape.substring(from + approval.slot.length()))
                                + "\\.?\\z";
                result.computeIfAbsent(approval.speaker, ignored -> new ArrayList<>())
                        .add(
                                new Rule(
                                        approval,
                                        target,
                                        match.sourceIds(),
                                        Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                                        to));
            }
        }
        result.replaceAll((k, v) -> List.copyOf(v));
        return new NpcDictionaryTemplates(result);
    }

    static Map<String, Object> context(Map<String, Object> reviewed, Map<String, Object> raw) {
        var result = new TreeMap<>(reviewed);
        Object name = raw == null ? null : raw.get(PLAYER_CONTEXT);
        if (name instanceof String text && PLAYER.matcher(text).matches())
            result.put(PLAYER_CONTEXT, text);
        return Map.copyOf(result);
    }

    BundledDictionary.Lookup inspect(String source, String speaker, Map<String, Object> context) {
        BundledDictionary.Match found = null;
        for (var rule : bySpeaker.getOrDefault(speaker, List.of())) {
            var matcher = rule.pattern.matcher(source);
            if (!matcher.matches()) continue;
            String value = matcher.group("value");
            if (rule.approval.type.equals("player_name")) {
                Object known = context.get(PLAYER_CONTEXT);
                if (!(known instanceof String name)
                        || !PLAYER.matcher(name).matches()
                        || !name.equalsIgnoreCase(value)) continue;
            }
            String target =
                    rule.target.substring(0, rule.targetStart)
                            + value
                            + rule.target.substring(rule.targetStart + rule.approval.slot.length());
            String type = rule.approval.type.equals("player_name") ? "name" : "number";
            var binding =
                    new BundledDictionary.Binding(
                            "npc_" + rule.approval.type,
                            type,
                            matcher.start("value"),
                            matcher.end("value"),
                            rule.targetStart,
                            rule.targetStart + value.length(),
                            value,
                            value);
            var next = new BundledDictionary.Match(target, false, rule.ids, List.of(binding));
            if (found != null
                    && (!found.text().equals(next.text())
                            || !found.bindings().equals(next.bindings()))) return miss("ambiguous");
            if (found == null) found = next;
            else {
                var ids = new LinkedHashSet<>(found.sourceIds());
                ids.addAll(next.sourceIds());
                found =
                        new BundledDictionary.Match(
                                target, false, List.copyOf(ids), found.bindings());
            }
        }
        return found == null
                ? miss("not_found")
                : new BundledDictionary.Lookup(Optional.of(found), "matched", "npc_template");
    }

    private static String key(String speaker, String source) {
        return speaker + '\0' + DictionaryMatching.aliasKey(source);
    }

    private static BundledDictionary.Lookup miss(String reason) {
        return new BundledDictionary.Lookup(Optional.empty(), reason, "npc_template");
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
