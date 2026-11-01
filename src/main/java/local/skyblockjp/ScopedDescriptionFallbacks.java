package local.skyblockjp;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Exact, reviewed presentation bridges. This never searches arbitrary scopes. */
public final class ScopedDescriptionFallbacks {
    public static final String RESOURCE = "/assets/skyblock_jp/dictionary/candidate-scoped-descriptions.json";
    public record Stats(boolean loaded, String buildId, int rules, String error) {}
    private record Rule(String id, String from, String to, String source, String target, List<String> sourceIds,
                        Map<String,Object> requires) {}
    private final String buildId;
    private final Map<String,List<Rule>> rules;
    private final Stats stats;
    private static final Set<String> CONTEXT = Set.of("tooltipTitle", "tooltipKind", "paragraphRole", "location", "slotMatches");
    private static final class Holder {
        static final ScopedDescriptionFallbacks VALUE = load(ScopedDescriptionFallbacks.class.getResourceAsStream(RESOURCE));
    }
    private ScopedDescriptionFallbacks(String buildId, Map<String,List<Rule>> rules, Stats stats) {
        this.buildId=buildId;this.rules=Map.copyOf(rules);this.stats=stats;
    }
    private static ScopedDescriptionFallbacks failed(String reason) {
        return new ScopedDescriptionFallbacks("",Map.of(),new Stats(false,"",0,reason));
    }
    public static Stats stats() { return Holder.VALUE.stats; }
    Stats loadedStats() { return stats; }
    /** Call only after the requested scope's ordinary lookup returned not_found. */
    public static BundledDictionary.Lookup inspect(BundledDictionary dictionary,String channel,String source,Map<String,Object> context) {
        return Holder.VALUE.lookup(dictionary,channel,source,context);
    }
    BundledDictionary.Lookup lookup(BundledDictionary dictionary,String channel,String source,Map<String,Object> context) {
        if (!"ui".equals(channel) || source==null || dictionary==null) return miss("not_found");
        String normalized=BundledDictionary.normalize(source);
        var possible=rules.get(channel+'\0'+normalized);
        if(possible==null) return miss("not_found");
        if(!stats.loaded() || !dictionary.stats().loaded() || !buildId.equals(dictionary.stats().buildId())) return miss("scoped_dictionary_build_mismatch");
        var existing=dictionary.inspect(channel,normalized,"",context);
        if(existing.match().isPresent() || !existing.reason().equals("not_found")) return miss("scoped_description_existing_resolution");
        Map<String,Object> observed=context==null?Map.of():context;
        var applicable=possible.stream().filter(rule->rule.requires().entrySet().stream().allMatch(e->Objects.equals(observed.get(e.getKey()),e.getValue()))).toList();
        if(applicable.isEmpty()) return miss("scoped_description_context_mismatch");
        if(applicable.size()!=1) return miss("scoped_description_ambiguous_context");
        Rule rule=applicable.getFirst();
        // One declared original key is revalidated. The ordinary requested scope
        // was already tried by the caller; no common/other-scope scan is added.
        var origin=dictionary.inspect(rule.from(),normalized,"");
        if(origin.match().isEmpty() || !origin.mode().equals("exact")) return miss("scoped_description_origin_unavailable");
        var match=origin.match().get();
        if(match.keepOriginal() || !match.text().equals(rule.target()) || !match.sourceIds().containsAll(rule.sourceIds())) return miss("scoped_description_origin_changed");
        // Retain the source/target-bound reviewed alignment of the original body.
        // Its live native styles are still applied and checked by StyledParagraph.
        return new BundledDictionary.Lookup(Optional.of(match),"matched","scoped_observed");
    }
    private static BundledDictionary.Lookup miss(String reason) { return new BundledDictionary.Lookup(Optional.empty(),reason,"scoped_observed"); }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static ScopedDescriptionFallbacks load(InputStream input) {
        if(input==null)return failed("resource_missing");
        try(input) {
            byte[] bytes=input.readNBytes(1024*1024+1);require(bytes.length<=1024*1024,"resource_limit");
            JsonElement raw;
            try(var reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)))) {
                reader.setStrictness(Strictness.STRICT);raw=read(reader,0);require(reader.peek()==JsonToken.END_DOCUMENT,"trailing_content");
            }
            JsonObject doc=object(raw,Set.of("schema_version","build_id","rules"));
            require(doc.get("schema_version").getAsInt()==1,"schema_mismatch");
            String build=text(doc,"build_id");require(!build.isBlank()&&build.length()<=128,"invalid_build");
            JsonArray entries=doc.getAsJsonArray("rules");require(entries.size()<=256,"rule_limit");
            var index=new HashMap<String,List<Rule>>();var ids=new HashSet<String>();var contexts=new HashSet<String>();var targets=new HashMap<String,String>();int count=0;
            for(JsonElement value:entries) {
                JsonObject row=object(value,Set.of("id","from_channel","to_channel","source","target","source_sha256","target_sha256","source_ids","semantic_role","requires"));
                String id=text(row,"id"),from=text(row,"from_channel"),to=text(row,"to_channel"),source=text(row,"source"),target=text(row,"target"),role=text(row,"semantic_role");
                require(id.startsWith("observed-applicability/")&&id.length()<=512&&ids.add(id),"duplicate_or_invalid_id");
                require(from.equals("item")&&to.equals("ui")&&Set.of("DESCRIPTION","DESCRIPTION_LABEL").contains(role),"unsupported_scope_or_role");
                require(BundledDictionary.safeText(source)&&BundledDictionary.safeText(target)&&!source.isBlank()&&!target.isBlank()&&BundledDictionary.normalize(source).equals(source),"unsafe_text");
                require(hash(source).equals(text(row,"source_sha256"))&&hash(target).equals(text(row,"target_sha256")),"text_hash_mismatch");
                require(BundledDictionary.quantitiesPreserved(source,target)&&BundledDictionary.symbols(source).equals(BundledDictionary.symbols(target)),"value_changed");
                JsonObject required=object(row.get("requires"),CONTEXT);var requires=new TreeMap<String,Object>();
                for(String field:CONTEXT) {
                    JsonPrimitive p=required.getAsJsonPrimitive(field);
                    require(p!=null,"invalid_context");
                    if(field.equals("slotMatches")) {require(p.isBoolean()&&p.getAsBoolean(),"unmatched_slot");requires.put(field,true);}
                    else {require(p.isString(),"invalid_context_type");requires.put(field,p.getAsString());}
                }
                require(requires.get("tooltipKind").equals("UI")&&requires.get("location").equals("CONTAINER")&&requires.get("paragraphRole").equals(role),"invalid_context_role");
                String title=(String)requires.get("tooltipTitle");require(!title.isBlank()&&title.length()<=120&&BundledDictionary.normalize(title).equals(title),"invalid_context_title");
                if(role.equals("DESCRIPTION_LABEL"))require(source.matches("[A-Z][A-Za-z -]{2,50}:"),"invalid_section_label");
                var sourceIds=new ArrayList<String>();var uniqueIds=new HashSet<String>();
                for(JsonElement v:row.getAsJsonArray("source_ids")) {String s=v.getAsString();require(s.startsWith("observed/item/")&&s.length()<=512&&uniqueIds.add(s),"invalid_source_id");sourceIds.add(s);}
                require(!sourceIds.isEmpty()&&sourceIds.size()<=8,"source_id_limit");
                String key=to+'\0'+source;
                require(contexts.add(key+'\0'+new Gson().toJson(requires)),"duplicate_context");
                String previous=targets.putIfAbsent(key,target);require(previous==null||previous.equals(target),"conflicting_target");
                index.computeIfAbsent(key,k->new ArrayList<>()).add(new Rule(id,from,to,source,target,List.copyOf(sourceIds),Map.copyOf(requires)));count++;
            }
            index.replaceAll((k,v)->List.copyOf(v));
            return new ScopedDescriptionFallbacks(build,index,new Stats(true,build,count,""));
        } catch(Exception error) {return failed(error instanceof Invalid?error.getMessage():"invalid_"+error.getClass().getSimpleName());}
    }
    private static JsonObject object(JsonElement raw,Set<String> fields)throws Invalid {
        require(raw!=null&&raw.isJsonObject(),"object_required");JsonObject value=raw.getAsJsonObject();require(value.keySet().equals(fields),"invalid_fields");return value;
    }
    private static String text(JsonObject row,String key)throws Invalid {
        JsonElement value=row.get(key);require(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString(),"string_required");String text=value.getAsString();require(text.length()<=16000,"text_limit");return text;
    }
    private static JsonElement read(JsonReader reader,int depth)throws Exception {
        require(depth<=8,"depth_limit");return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();var out=new JsonObject();while(reader.hasNext()){String key=reader.nextName();require(!out.has(key)&&out.size()<32,"duplicate_or_excess_fields");out.add(key,read(reader,depth+1));}reader.endObject();yield out;}
            case BEGIN_ARRAY->{reader.beginArray();var out=new JsonArray();while(reader.hasNext()){require(out.size()<1024,"array_limit");out.add(read(reader,depth+1));}reader.endArray();yield out;}
            case STRING->{String value=reader.nextString();require(value.length()<=16000,"text_limit");yield new JsonPrimitive(value);}
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());
            case NUMBER->{String value=reader.nextString();require(value.matches("[0-9]{1,9}"),"integer_required");yield new JsonPrimitive(Integer.parseInt(value));}
            default->throw new Invalid("invalid_json_value");
        };
    }
    private static void require(boolean condition,String message)throws Invalid {if(!condition)throw new Invalid(message);}
    private static final class Invalid extends Exception {Invalid(String message){super(message);}}
}
