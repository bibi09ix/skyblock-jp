package local.skyblockjp;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit human-reviewed decisions; never a heuristic tie-breaker for conflicts. */
final class ReviewedDictionary {
    static final String RESOURCE = "/assets/skyblock_jp/dictionary/candidate-reviewed.json";
    private static final Set<String> CHANNELS = Set.of("item", "ui", "npc", "common", "system");
    private static final Set<String> CONTEXT = Set.of("containerTitle", "location", "slotMatches", "slotIndex", "containerSize",
        "skyblockItemId", "minecraftItemId", "screenClass", "previousDialogue", "previousSpeaker");
    record Stats(boolean loaded, int rules, int rejectedRules, String error) {}
    private record Rule(String id, String target, boolean keep, List<String> ids, Map<String,Object> requires) {}
    private final Map<String,List<Rule>> rules;
    private final Stats stats;
    private ReviewedDictionary(Map<String,List<Rule>> rules, Stats stats) { this.rules=Map.copyOf(rules); this.stats=stats; }
    static ReviewedDictionary empty(String reason) { return new ReviewedDictionary(Map.of(), new Stats(false,0,0,reason)); }
    Stats stats() { return stats; }
    static String key(String channel,String speaker,String source) { return channel+'\0'+speaker+'\0'+DictionaryMatching.aliasKey(source); }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static Map<String,Object> context(Map<String,Object> raw) {
        if(raw==null||raw.isEmpty()) return Map.of();
        var values=new TreeMap<String,Object>();
        for(String field:CONTEXT) {
            Object value=raw.get(field);
            if(value instanceof String text && text.length()<=16000) values.put(field,text);
            else if(value instanceof Boolean) values.put(field,value);
            else if(value instanceof Integer || value instanceof Long) values.put(field,((Number)value).longValue());
        }
        return Map.copyOf(values);
    }
    /** Null means no applicable review. A conflicting applicable review explicitly blocks fallback. */
    BundledDictionary.Lookup inspect(String channel,String speaker,String source,Map<String,Object> context) {
        var candidates=rules.get(key(channel,speaker,source)); if(candidates==null) return null;
        BundledDictionary.Match match=null;
        for(var rule:candidates) {
            if(!rule.requires.entrySet().stream().allMatch(e->Objects.equals(context.get(e.getKey()),e.getValue()))) continue;
            String target=rule.keep?source:rule.target;
            if(!BundledDictionary.quantitiesPreserved(source,target)||!BundledDictionary.symbols(DictionaryGlyphs.canonical(source)).equals(BundledDictionary.symbols(DictionaryGlyphs.canonical(target)))) continue;
            var next=DictionaryGlyphs.bind(source,new BundledDictionary.Match(target,rule.keep,rule.ids));
            if(next==null) continue;
            if(match!=null&&(!match.text().equals(next.text())||match.keepOriginal()!=next.keepOriginal()))
                return new BundledDictionary.Lookup(Optional.empty(),"conflicting_reviews","reviewed");
            if(match==null) match=next;
            else {var ids=new LinkedHashSet<>(match.sourceIds());ids.addAll(next.sourceIds());match=new BundledDictionary.Match(match.text(),match.keepOriginal(),List.copyOf(ids),match.bindings());}
        }
        return match==null?null:new BundledDictionary.Lookup(Optional.of(match),"matched","reviewed");
    }
    static ReviewedDictionary load(InputStream input,String buildId,Map<String,String> evidence) {
        if(input==null) return empty("reviewed_resource_missing");
        try(input; var reader=new JsonReader(new InputStreamReader(new Bounded(input),StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            reader.setStrictness(Strictness.STRICT);
            var root=object(read(reader,0),Set.of("schema_version","build_id","rules"));
            require(reader.peek()==JsonToken.END_DOCUMENT,"trailing_content");
            require(root.get("schema_version").equals(1L),"unsupported_schema");
            require(text(root.get("build_id")).equals(buildId),"reviewed_build_mismatch");
            var index=new HashMap<String,List<Rule>>();var seen=new HashSet<String>();int count=0,rejected=0;
            for(Object raw:array(root.get("rules"),2000)) {
                var row=object(raw,Set.of("id","approved","channel","speaker","source","source_sha256","variants_sha256","target","keep_original","requires","reason","source_ids"));
                String id=text(row.get("id")),channel=text(row.get("channel")),speaker=text(row.get("speaker"));
                String source=text(row.get("source")),target=text(row.get("target"));
                require(!id.isBlank()&&seen.add(id),"duplicate_review_id");
                require(CHANNELS.contains(channel)&&(channel.equals("npc")?!speaker.isBlank():speaker.isEmpty()),"invalid_review_scope");
                require(BundledDictionary.safeText(source)&&BundledDictionary.safeText(target)&&!source.isBlank()&&!target.isBlank(),"unsafe_review_text");
                boolean keep=bool(row.get("keep_original"));
                require(!keep||BundledDictionary.normalize(source).equals(BundledDictionary.normalize(target)),"invalid_preservation");
                require(BundledDictionary.quantitiesPreserved(source,target)&&BundledDictionary.symbols(source).equals(BundledDictionary.symbols(target)),"review_value_changed");
                var requires=map(row.get("requires"));require(CONTEXT.containsAll(requires.keySet()),"unsupported_review_context");
                for(var entry:requires.entrySet()) {
                    require(entry.getValue() instanceof String||entry.getValue() instanceof Boolean||entry.getValue() instanceof Long,"invalid_context_value");
                    require(channel.equals("npc")==Set.of("previousDialogue","previousSpeaker").contains(entry.getKey()),"invalid_context_scope");
                }
                var ids=new ArrayList<String>();for(Object value:array(row.get("source_ids"),150000)){String sourceId=text(value);require(!sourceId.isBlank(),"invalid_source_id");ids.add(sourceId);}
                require(!ids.isEmpty(),"missing_source_ids");
                String key=key(channel,speaker,source);
                if(!Boolean.TRUE.equals(row.get("approved"))||!hash(DictionaryMatching.aliasKey(source)).equals(text(row.get("source_sha256")))
                    ||!Objects.equals(evidence.get(key),text(row.get("variants_sha256")))) {rejected++;continue;}
                index.computeIfAbsent(key,ignored->new ArrayList<>()).add(new Rule(id,target,keep,List.copyOf(ids),Map.copyOf(requires)));count++;
            }
            index.replaceAll((k,v)->List.copyOf(v));return new ReviewedDictionary(index,new Stats(true,count,rejected,""));
        } catch(Exception error) {return empty(error instanceof Bad?error.getMessage():"reviewed_invalid_"+error.getClass().getSimpleName());}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object raw)throws Bad {require(raw instanceof Map,"object_required");return (Map<String,Object>)raw;}
    private static Map<String,Object> object(Object raw,Set<String> fields)throws Bad {var value=map(raw);require(value.keySet().equals(fields),"invalid_fields");return value;}
    @SuppressWarnings("unchecked") private static List<Object> array(Object raw,int limit)throws Bad {require(raw instanceof List,"array_required");var value=(List<Object>)raw;require(value.size()<=limit,"array_limit");return value;}
    private static String text(Object raw)throws Bad {require(raw instanceof String,"string_required");return (String)raw;}
    private static boolean bool(Object raw)throws Bad {require(raw instanceof Boolean,"boolean_required");return (Boolean)raw;}
    private static Object read(JsonReader reader,int depth)throws Exception {
        require(depth<=8,"depth_limit");
        return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();var map=new LinkedHashMap<String,Object>();while(reader.hasNext()){String key=reader.nextName();require(!map.containsKey(key),"duplicate_field");map.put(key,read(reader,depth+1));require(map.size()<=16,"field_limit");}reader.endObject();yield map;}
            case BEGIN_ARRAY->{reader.beginArray();var values=new ArrayList<Object>();while(reader.hasNext()){require(values.size()<150000,"array_limit");values.add(read(reader,depth+1));}reader.endArray();yield values;}
            case STRING->{String value=reader.nextString();require(value.length()<=16000,"text_limit");yield value;}
            case NUMBER->{String value=reader.nextString();require(value.matches("-?\\d{1,9}"),"integer_required");yield Long.parseLong(value);}
            case BOOLEAN->reader.nextBoolean();
            default->throw new Bad("invalid_json_value");
        };
    }
    private static void require(boolean value,String reason)throws Bad {if(!value)throw new Bad(reason);}
    private static final class Bad extends Exception {Bad(String reason){super(reason);}}
    private static final class Bounded extends FilterInputStream {
        int remaining=8*1024*1024;Bounded(InputStream stream){super(stream);}
        @Override public int read()throws IOException {int value=super.read();if(value>=0&&--remaining<0)throw new IOException("resource_limit");return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {int count=in.read(bytes,offset,Math.min(length,remaining+1));if(count>0&&(remaining-=count)<0)throw new IOException("resource_limit");return count;}
    }
}
