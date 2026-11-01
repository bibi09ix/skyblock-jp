package local.skyblockjp;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Bounded sidecar. Only declared holes are variables; literal numbers never become wildcards. */
final class DictionaryMatching {
    static final String RESOURCE="/assets/skyblock_jp/dictionary/candidate-matching.json";
    record Seed(String channel,String speaker,String source,BundledDictionary.Match match) {}
    record Stats(boolean loaded,int normalizedKeys,int ambiguousNormalizedKeys,int explicitAliases,int templates,String error) {}
    private record Key(String channel,String speaker,String source) {}
    private record Bucket(String channel,String speaker,String prefix) {}
    private record Alias(String source,BundledDictionary.Match match) {}
    private record Variable(String name,String type) {}
    private static final Pattern SLOT=Pattern.compile("\\{(v[0-7])\\}");
    private static final Pattern WORD=Pattern.compile("[a-z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final String DECIMAL="[+−-]?(?:(?:[0-9]{1,12}|[0-9]{1,3}(?:,[0-9]{3}){1,3})(?:\\.[0-9]{1,6})?|\\.[0-9]{1,6})(?:[kKmMbBtT])?";
    private static final String ROMAN="(?:I|II|III|IV|V|VI|VII|VIII|IX|X|XI|XII|XIII|XIV|XV|XVI|XVII|XVIII|XIX|XX)";
    private static final String REWARD_ROMAN="(?=[IVXLC])(?:C|(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3}))";
    private static final String REWARD_SOURCE="Level {v0} Rewards:",REWARD_TARGET="レベル{v0}の報酬:";
    private static final String FIRE_VEIL_SOURCE="Creates a veil of fire around you for 5s, dealing {v0} damage per second to mobs within.";
    private static final String FIRE_VEIL_TARGET="周囲に5秒間の炎の幕を作り、その中にいるモブに毎秒{v0}のダメージを与える。";
    private static final String UNSIGNED_DAMAGE="(?:[0-9]{1,12}|[0-9]{1,3}(?:,[0-9]{3}){1,3})(?:\\.[0-9]{1,6})?";
    private static final String PET_NAME="[A-Za-z][A-Za-z0-9' -]{0,63}";
    private static final Set<String> CHANNELS=Set.of("item","common","npc","ui","system");
    private final Map<Key,List<Alias>> normalized;
    private final Set<Key> ambiguous;
    private final Map<Bucket,List<Template>> templates;
    private final Stats stats;
    private DictionaryMatching(Map<Key,List<Alias>> normalized,Set<Key> ambiguous,Map<Bucket,List<Template>> templates,Stats stats) {
        this.normalized=Map.copyOf(normalized);this.ambiguous=Set.copyOf(ambiguous);this.templates=Map.copyOf(templates);this.stats=stats;
    }
    static DictionaryMatching empty(String reason) {return new DictionaryMatching(Map.of(),Set.of(),Map.of(),new Stats(false,0,0,0,0,reason));}
    Stats stats(){return stats;}

    static DictionaryMatching load(InputStream input,String buildId,List<Seed> seeds) {
        if(input==null)return empty("matching_resource_missing");
        try(input; var reader=new JsonReader(new InputStreamReader(new Bounded(input),StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            reader.setStrictness(Strictness.STRICT);
            var root=object(read(reader,0),Set.of("schema_version","build_id","normalization","aliases","templates"));
            require(reader.peek()==JsonToken.END_DOCUMENT,"trailing_content");
            require(root.get("schema_version").equals(1),"unsupported_schema");
            require(text(root.get("build_id")).equals(buildId),"matching_build_mismatch");
            require(Set.of("ascii_case_single_period", "ascii_case_single_period_stat_glyphs_v1").contains(text(root.get("normalization"))),"unsupported_normalization");
            Map<Key,List<Alias>> normalized=new HashMap<>(); Set<Key> ambiguous=new HashSet<>();
            for(var seed:seeds)addAlias(normalized,ambiguous,seed);
            int aliases=0;
            for(Object value:array(root.get("aliases"),1000)) {
                var row=object(value,Set.of("channel","speaker","source","target","keep_original","source_ids"));
                String channel=channel(row),speaker=speaker(row,channel),source=text(row.get("source")),target=text(row.get("target"));
                boolean keep=bool(row.get("keep_original"));var ids=ids(row.get("source_ids"));
                require(!source.isBlank()&&!target.isBlank()&&BundledDictionary.safeText(source)&&BundledDictionary.safeText(target),"unsafe_alias_text");
                require(!keep||source.equals(target),"invalid_preservation");
                require(BundledDictionary.quantitiesPreserved(source,target),"alias_number_mismatch");
                require(BundledDictionary.symbols(source).equals(BundledDictionary.symbols(target)),"alias_symbol_mismatch");
                addAlias(normalized,ambiguous,new Seed(channel,speaker,source,new BundledDictionary.Match(target,keep,ids)));aliases++;
            }
            Map<Bucket,List<Template>> templates=new HashMap<>();
            Map<String,Template> unique=new LinkedHashMap<>(); Set<String> badTemplates=new HashSet<>();
            for(Object value:array(root.get("templates"),5000)) {
                Template template=template(value);
                String key=template.channel+"|"+template.source;
                Template old=unique.putIfAbsent(key,template);
                if(old!=null&&(!old.target.equals(template.target)||!old.variables.equals(template.variables)))badTemplates.add(key);
            }
            int templateCount=0;
            for(var item:unique.entrySet())if(!badTemplates.contains(item.getKey())) {
                Template template=item.getValue();
                templates.computeIfAbsent(new Bucket(template.channel,"",prefix(template.source)),ignored->new ArrayList<>()).add(template);templateCount++;
            }
            // No unbounded fallback bucket or per-frame scan over the corpus.
            for(var list:templates.values())require(list.size()<=256,"template_bucket_limit");
            normalized.replaceAll((k,v)->List.copyOf(v));templates.replaceAll((k,v)->List.copyOf(v));
            return new DictionaryMatching(normalized,ambiguous,templates,new Stats(true,normalized.size(),ambiguous.size(),aliases,templateCount,""));
        }catch(Exception error){return empty(error instanceof Bad ? error.getMessage():"matching_invalid_"+error.getClass().getSimpleName());}
    }

    BundledDictionary.Lookup normalized(String channel,String source,String speaker) {
        Key key=new Key(channel,speaker,aliasKey(source));
        if(ambiguous.contains(key))return miss("ambiguous","normalized");
        List<Alias> candidates=normalized.get(key);if(candidates==null)return miss("not_found","normalized");
        BundledDictionary.Match match=null;
        for(var candidate:candidates) {
            if(!HexColorTokens.preserved(source,candidate.source)
                    ||!BundledDictionary.numbers(source).equals(BundledDictionary.numbers(candidate.source))
                    ||!BundledDictionary.symbols(DictionaryGlyphs.canonical(source)).equals(BundledDictionary.symbols(DictionaryGlyphs.canonical(candidate.source))))continue;
            var bound=DictionaryGlyphs.bind(source,candidate.match);
            if(bound==null)return miss("ambiguous_glyph_binding","normalized");
            if(match!=null&&!same(match,bound))return miss("ambiguous","normalized");
            match=merge(match,bound);
        }
        return match==null?miss("not_found","normalized"):new BundledDictionary.Lookup(Optional.of(match),"matched","normalized");
    }
    BundledDictionary.Lookup template(String channel,String source,String speaker) {
        // Lookup a bounded bucket by a literal word occurring in the query. Variables
        // at the beginning (numbers, Roman levels, pet names) cannot hide that anchor.
        Set<Template> candidates=new LinkedHashSet<>();
        WORD.matcher(source).results().map(m->m.group().toLowerCase(Locale.ROOT)).distinct().forEach(word->{
            var bucket=templates.get(new Bucket(channel,speaker,word));if(bucket!=null)candidates.addAll(bucket);
        });
        if(candidates.isEmpty())return miss("not_found","template");
        BundledDictionary.Match match=null;
        for(var candidate:candidates) {
            var next=candidate.apply(source);if(next==null)continue;
            if(match!=null&&!same(match,next))return miss("ambiguous","template");
            match=merge(match,next);
        }
        return match==null?miss("not_found","template"):new BundledDictionary.Lookup(Optional.of(match),"matched","template");
    }
    private static void addAlias(Map<Key,List<Alias>> index,Set<Key> ambiguous,Seed seed) {
        var key=new Key(seed.channel,seed.speaker,aliasKey(seed.source));
        if(seed.match==null){ambiguous.add(key);return;}
        List<Alias> existing=index.computeIfAbsent(key,ignored->new ArrayList<>());
        // Conflicting case/period variants stay unavailable, even when query spelling is absent.
        if(existing.stream().anyMatch(a->!same(a.match,seed.match)))ambiguous.add(key);
        existing.add(new Alias(seed.source,seed.match));
    }
    private static BundledDictionary.Match merge(BundledDictionary.Match first,BundledDictionary.Match second) {
        if(first==null)return second;Set<String> ids=new LinkedHashSet<>(first.sourceIds());ids.addAll(second.sourceIds());
        return new BundledDictionary.Match(first.text(),first.keepOriginal(),List.copyOf(ids),first.bindings());
    }
    private static boolean same(BundledDictionary.Match a,BundledDictionary.Match b){return DictionaryGlyphs.canonical(a.text()).equals(DictionaryGlyphs.canonical(b.text()))&&a.keepOriginal()==b.keepOriginal()&&a.bindings().equals(b.bindings());}
    private static BundledDictionary.Lookup miss(String reason,String mode){return new BundledDictionary.Lookup(Optional.empty(),reason,mode);}
    static String aliasKey(String source) {
        String normalized=DictionaryGlyphs.canonical(BundledDictionary.normalize(source));StringBuilder b=new StringBuilder(normalized.length());
        for(int i=0;i<normalized.length();i++){char c=normalized.charAt(i);b.append(c>='A'&&c<='Z'?(char)(c+32):c);}
        // Optional single full stop only. Questions, exclamations and ellipses remain distinct.
        if(b.length()>1&&b.charAt(b.length()-1)=='.'&&b.charAt(b.length()-2)!='.')b.setLength(b.length()-1);
        return b.toString();
    }
    private static String prefix(String source) {
        var word=WORD.matcher(SLOT.matcher(aliasKey(source)).replaceAll(" "));
        return word.find()?word.group():"";
    }
    private static Template template(Object value) throws Bad {
        var row=object(value,Set.of("channel","speaker","source","target","variables","source_ids"));
        String channel=channel(row);require(channel.equals("item")||channel.equals("common")||channel.equals("ui"),"unsupported_template_context");
        require(speaker(row,channel).isEmpty(),"template_speaker");
        String source=text(row.get("source")),target=text(row.get("target"));
        List<Variable> variables=new ArrayList<>();
        for(Object v:array(row.get("variables"),8)) {
            var field=object(v,Set.of("name","type"));String name=text(field.get("name")),type=text(field.get("type"));
            require(name.equals("v"+variables.size())&&(type.equals("number")||type.equals("roman")||channel.equals("ui")&&type.equals("pet_name")),"invalid_variable");
            variables.add(new Variable(name,type));
        }
        require(!variables.isEmpty(),"missing_variables");
        if(variables.stream().anyMatch(v->v.type.equals("pet_name")))require(channel.equals("ui")&&source.equals("Selected pet: {v0}")&&target.equals("選択中のペット: {v0}")&&variables.equals(List.of(new Variable("v0","pet_name"))),"unreviewed_ui_template");
        boolean rewardHeading=channel.equals("ui")&&source.equals(REWARD_SOURCE);
        if(rewardHeading)require(target.equals(REWARD_TARGET)&&variables.equals(List.of(new Variable("v0","roman"))),"unreviewed_ui_reward_template");
        boolean fireVeil=source.equals(FIRE_VEIL_SOURCE);
        if(fireVeil)require((channel.equals("item")||channel.equals("ui"))&&target.equals(FIRE_VEIL_TARGET)
            &&variables.equals(List.of(new Variable("v0","number"))),"unreviewed_fire_veil_template");
        String roman=rewardHeading?REWARD_ROMAN:ROMAN;
        var sourceSlots=slots(source);var targetSlots=slots(target);
        require(sourceSlots.equals(targetSlots)&&sourceSlots.size()==variables.size(),"variable_alignment");
        for(var v:variables)require(sourceSlots.containsKey(v.name),"unused_variable");
        require(sourceSlots.values().stream().mapToInt(Integer::intValue).sum()<=16,"variable_limit");
        String plainSource=SLOT.matcher(source).replaceAll(""),plainTarget=SLOT.matcher(target).replaceAll("");
        require(BundledDictionary.safeText(plainSource)&&BundledDictionary.safeText(plainTarget),"unsafe_template_text");
        require(plainSource.length()>=12&&Pattern.compile("[A-Za-z]{2,}.*[A-Za-z]{2,}").matcher(plainSource).find(),"insufficient_literal_context");
        require(BundledDictionary.quantitiesPreserved(plainSource,plainTarget),"template_number_mismatch");
        require(BundledDictionary.symbols(plainSource).equals(BundledDictionary.symbols(plainTarget)),"template_symbol_mismatch");
        String canonical=aliasKey(source);Matcher tokens=SLOT.matcher(canonical);StringBuilder regex=new StringBuilder("^");int previous=0;
        Map<String,List<String>> groups=new LinkedHashMap<>();int occurrence=0;
        while(tokens.find()) {
            regex.append(Pattern.quote(canonical.substring(previous,tokens.start())));
            String name=tokens.group(1);Variable variable=variables.get(Integer.parseInt(name.substring(1)));
            var names=groups.computeIfAbsent(name,ignored->new ArrayList<>());String group="o"+(occurrence++);
            regex.append("(?<").append(group).append('>');
            if(names.isEmpty())regex.append(variable.type.equals("roman")?roman:variable.type.equals("pet_name")?PET_NAME:fireVeil?UNSIGNED_DAMAGE:DECIMAL);
            else regex.append("\\k<").append(names.getFirst()).append('>');
            regex.append(')');names.add(group);
            previous=tokens.end();
        }
        regex.append(Pattern.quote(canonical.substring(previous))).append('$');
        // ASCII-case-insensitive pattern keeps captured live values in their original case.
        return new Template(channel,source,target,variables,Pattern.compile(regex.toString(),Pattern.CASE_INSENSITIVE),ids(row.get("source_ids")),groups,roman);
    }
    private record Template(String channel,String source,String target,List<Variable> variables,Pattern pattern,List<String> ids,Map<String,List<String>> groups,String roman) {
        BundledDictionary.Match apply(String live) {
            String normalized=BundledDictionary.normalize(live);
            if(normalized.endsWith(".")&&!normalized.endsWith(".."))normalized=normalized.substring(0,normalized.length()-1);
            Matcher matcher=pattern.matcher(DictionaryGlyphs.canonical(normalized));if(!matcher.matches())return null;
            for(var v:variables)if(v.type.equals("roman")&&!matcher.group(groups.get(v.name).getFirst()).matches(roman))return null;
            var text=new StringBuilder();var bindings=new ArrayList<BundledDictionary.Binding>();var uses=new HashMap<String,Integer>();
            var targetSlots=SLOT.matcher(target);int previous=0;
            while(targetSlots.find()) {
                text.append(target,previous,targetSlots.start());String name=targetSlots.group(1);
                String group=groups.get(name).get(uses.merge(name,1,Integer::sum)-1);
                String value=normalized.substring(matcher.start(group),matcher.end(group));int start=text.length();text.append(value);
                int sourceStart=matcher.start(group);String type=variables.get(Integer.parseInt(name.substring(1))).type;
                // A sign may be part of the variable or a literal immediately before it.
                // Both templates bind the same complete signed value, not conflicting spans.
                if(type.equals("number")&&sourceStart>0&&start>0&&"+−-".indexOf(normalized.charAt(sourceStart-1))>=0
                    &&normalized.charAt(sourceStart-1)==text.charAt(start-1)) {sourceStart--;start--;}
                bindings.add(new BundledDictionary.Binding(name,variables.get(Integer.parseInt(name.substring(1))).type,
                    sourceStart,matcher.end(group),start,text.length(),normalized.substring(sourceStart,matcher.end(group)),text.substring(start)));previous=targetSlots.end();
            }
            text.append(target,previous,target.length());
            var bound=DictionaryGlyphs.bind(normalized,new BundledDictionary.Match(text.toString(),false,ids,bindings));
            if(bound==null||!BundledDictionary.quantitiesPreserved(live,bound.text())||!BundledDictionary.symbols(live).equals(BundledDictionary.symbols(bound.text())))return null;
            return bound;
        }
    }
    private static Map<String,Integer> slots(String text) {
        Map<String,Integer> result=new HashMap<>();SLOT.matcher(text).results().forEach(m->result.merge(m.group(1),1,Integer::sum));return result;
    }
    private static String channel(Map<String,Object> row)throws Bad{String c=text(row.get("channel"));require(CHANNELS.contains(c),"invalid_channel");return c;}
    private static String speaker(Map<String,Object> row,String channel)throws Bad{String s=BundledDictionary.normalize(text(row.get("speaker")));require(BundledDictionary.safeText(s)&&(channel.equals("npc")?!s.isEmpty():s.isEmpty()),"invalid_speaker");return s;}
    private static List<String> ids(Object value)throws Bad{var ids=new ArrayList<String>();for(Object id:array(value,150000)){String s=text(id);require(!s.isBlank()&&s.length()<=512&&s.codePoints().noneMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||Character.getType(cp)==Character.SURROGATE),"invalid_source_id");ids.add(s);}require(!ids.isEmpty(),"missing_source_ids");return List.copyOf(ids);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value,Set<String> fields)throws Bad{require(value instanceof Map,"object_required");var map=(Map<String,Object>)value;require(map.keySet().equals(fields),"invalid_fields");return map;}
    @SuppressWarnings("unchecked") private static List<Object> array(Object value,int limit)throws Bad{require(value instanceof List,"array_required");var list=(List<Object>)value;require(list.size()<=limit,"array_limit");return list;}
    private static String text(Object value)throws Bad{require(value instanceof String,"string_required");String s=(String)value;require(!s.isBlank()&&s.length()<=16000||s.isEmpty(),"text_limit");return s;}
    private static boolean bool(Object value)throws Bad{require(value instanceof Boolean,"boolean_required");return (Boolean)value;}
    private static Object read(JsonReader reader,int depth)throws Exception {
        require(depth<=8,"depth_limit");
        return switch(reader.peek()) {
            case BEGIN_OBJECT->{reader.beginObject();Map<String,Object> map=new LinkedHashMap<>();while(reader.hasNext()){String field=reader.nextName();require(!map.containsKey(field),"duplicate_field");map.put(field,read(reader,depth+1));require(map.size()<=16,"field_limit");}reader.endObject();yield map;}
            case BEGIN_ARRAY->{reader.beginArray();List<Object> values=new ArrayList<>();while(reader.hasNext()){require(values.size()<150000,"array_limit");values.add(read(reader,depth+1));}reader.endArray();yield values;}
            case STRING->{String s=reader.nextString();require(s.length()<=16000,"text_limit");yield s;}
            case NUMBER->{String n=reader.nextString();require(n.equals("1"),"invalid_number");yield 1;}
            case BOOLEAN->reader.nextBoolean();
            default->throw new Bad("invalid_json_value");
        };
    }
    private static void require(boolean ok,String reason)throws Bad{if(!ok)throw new Bad(reason);}
    private static final class Bad extends Exception{Bad(String reason){super(reason);}}
    private static final class Bounded extends java.io.FilterInputStream {
        int remaining=8*1024*1024;Bounded(InputStream in){super(in);}
        @Override public int read()throws java.io.IOException{int b=super.read();if(b>=0&&--remaining<0)throw new java.io.IOException("resource_limit");return b;}
        @Override public int read(byte[] b,int off,int len)throws java.io.IOException{int n=super.read(b,off,Math.min(len,remaining+1));if(n>0&&(remaining-=n)<0)throw new java.io.IOException("resource_limit");return n;}
    }
}
