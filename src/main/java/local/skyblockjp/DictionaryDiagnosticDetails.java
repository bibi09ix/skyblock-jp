package local.skyblockjp;

import java.util.*;

/** Bounded, explicit diagnostic DTO. Unknown values never enter shared records. */
final class DictionaryDiagnosticDetails {
    private static final Set<String> TEXT = Set.of("validationStage", "alignmentId", "spanId", "targetSha256", "targetCandidate",
        "marker", "sourceLiteral", "styleLiteral", "validationStep", "targetLiteral", "anchorLiteral", "anchorStage",
        "offsetUnit", "originalStyledFragment", "alignmentMode", "renderPolicy", "cause", "reason", "variable");
    private static final Set<String> NUMBERS = Set.of("expectedCount", "candidateCount", "sourceStart", "sourceEnd", "targetStart", "targetEnd",
        "sourceOffset", "targetOffset", "styleId", "sourceCount", "targetCount", "protectedCount", "ordinaryCount", "markerCount",
        "sourceUnitCount", "targetUnitCount");
    private static final Set<String> FLAGS = Set.of("markersValid", "quantitiesPreserved");
    private static final Set<String> TEXT_LISTS=Set.of("sourceEffectLevels","targetEffectLevels","sourceRoles","targetRoles",
        "sourceCommands","targetCommands","sourceRates","targetRates","unknownTargetWords","excessTargetWords");
    private static final Set<String> COUNTS=Set.of("sourceMembers","targetMembers");
    private static final Set<String> STYLE_FLAGS = Set.of("bold", "italic", "underlined", "strikethrough", "obfuscated");
    private DictionaryDiagnosticDetails() {}

    static Map<String,Object> copy(Map<String,Object> input) {
        var out = new LinkedHashMap<String,Object>();
        for (var key : TEXT) if (input.get(key) instanceof String s) out.put(key, text(s));
        for (var key : NUMBERS) { var n = integer(input.get(key)); if (n != null) out.put(key,n); }
        for (var key : FLAGS) if (input.get(key) instanceof Boolean b) out.put(key,b);
        for(var key:TEXT_LISTS)if(input.get(key) instanceof List<?> list) {
            if(list.size()<=32&&list.stream().allMatch(String.class::isInstance))out.put(key,list.stream().map(v->text((String)v)).toList());
        }
        for(var key:COUNTS)if(input.get(key) instanceof Map<?,?> counts&&counts.size()<=32) {
            var kept=new TreeMap<String,Integer>();
            counts.forEach((name,count)->{var n=integer(count);if(name instanceof String s&&n!=null&&n>=0)kept.put(text(s),n);});
            if(kept.size()==counts.size())out.put(key,Map.copyOf(kept));
        }
        if (input.get("candidateOffsets") instanceof List<?> values) {
            var ranges = new ArrayList<Object>();
            for (var value : values.stream().limit(16).toList()) {
                if (value instanceof List<?> pair && pair.size()==2) {
                    Integer a=integer(pair.get(0)), b=integer(pair.get(1));
                    if(a!=null&&b!=null&&a>=0&&b>=a) ranges.add(List.of(a,b));
                } else { var n=integer(value); if(n!=null&&n>=0) ranges.add(n); }
            }
            out.put("candidateOffsets",List.copyOf(ranges));
        }
        if(input.get("style") instanceof Map<?,?> style) {
            var visual=new LinkedHashMap<String,Object>();
            for(var key:STYLE_FLAGS)if(style.get(key) instanceof Boolean b)visual.put(key,b);
            if(style.get("colorRGB") instanceof String c&&c.matches("#[0-9a-fA-F]{6}"))visual.put("colorRGB",c);
            var color=integer(style.get("color"));if(color!=null&&color>=0&&color<=0xffffff)visual.put("color",color);
            out.put("style",Map.copyOf(visual));
        }
        return Collections.unmodifiableMap(out);
    }
    private static Integer integer(Object value) {
        if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())return null;
        return n.intValue();
    }
    private static String text(String s) {
        int end=Math.min(s.length(),8192);
        if(end<s.length()&&end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;
        return s.substring(0,end);
    }
}
