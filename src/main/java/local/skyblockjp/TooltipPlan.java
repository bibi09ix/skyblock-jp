package local.skyblockjp;

import java.util.*;
import net.minecraft.network.chat.Component;

/** A source-range plan shared by rendering, requests and diagnostics. */
final class TooltipPlan {
    record Result(LoreParser.Analysis analysis,List<LoreParser.Block> blocks,List<StyledParagraph> paragraphs,
                  List<String> channels,List<Map<String,Object>> contexts,List<Translator.DictionaryDecision> decisions) {}
    private TooltipPlan() {}
    static Result prepare(List<Component> original,LoreParser.Analysis parsed,TooltipContext.Decision classification,
                          Translator translator,boolean manuallyRequested) {
        var source=original.stream().map(Component::getString).toList();
        var replacements=new LinkedHashMap<Integer,List<LoreParser.Paragraph>>();
        var styled=new HashMap<LoreParser.Block,StyledParagraph>();
        java.util.function.Function<LoreParser.Block,StyledParagraph> style=b->styled.computeIfAbsent(b,
            ignored->new StyledParagraph(original.subList(b.start(),b.end())));
        for(int index=0;index<parsed.paragraphs().size();index++) {
            var whole=parsed.paragraphs().get(index);
            var paragraph=style.apply(whole.block());
            var context=context(original,classification,whole.role());
            // Boundary selection is intrinsic. A forced API refresh must not change a
            // partition halfway through its pending batch by overriding dictionary output.
            var ordinary=translator.inspectDictionary(paragraph,whole.channel(),"",false,context);
            var parts=ordinary.reason().equals("not_found")
                ?LoreParser.independentRecipeRows(source,classification,whole,s->translator.exactJapanese(whole.channel(),s))
                :List.<LoreParser.Paragraph>of();
            boolean safe=!parts.isEmpty();
            for(var part:parts) {
                var child=style.apply(part.block());
                var decision=translator.inspectDictionary(child,part.channel(),"",false,context(original,classification,part.role()));
                if(!decision.accepted()||decision.reason().equals("KEEP_ORIGINAL")) {safe=false;break;}
                try {if(!containsJapanese(child.decode(decision.encoded()).getString())){safe=false;break;}}
                catch(IllegalArgumentException invalid){safe=false;break;}
            }
            if(safe)replacements.put(index,parts);
        }
        var expanded=LoreParser.replaceParagraphs(parsed,replacements);
        var chosen=expanded.paragraphs();
        var blocks=new ArrayList<LoreParser.Block>();var paragraphs=new ArrayList<StyledParagraph>();
        var channels=new ArrayList<String>();var contexts=new ArrayList<Map<String,Object>>();
        var decisions=new ArrayList<Translator.DictionaryDecision>();
        for(int n=0;n<chosen.size();n++) {
            var p=chosen.get(n);var block=p.block();
            var context=context(original,classification,p.role());
            var paragraph=style.apply(block);
            blocks.add(block);paragraphs.add(paragraph);channels.add(p.channel());contexts.add(context);
            decisions.add(translator.inspectDictionary(paragraph,p.channel(),"",manuallyRequested,context));
        }
        return new Result(expanded,List.copyOf(blocks),List.copyOf(paragraphs),
            List.copyOf(channels),List.copyOf(contexts),List.copyOf(decisions));
    }
    static Map<String,Object> context(List<Component> original,TooltipContext.Decision classification,String role) {
        var out=new LinkedHashMap<String,Object>(classification.evidence());
        out.putIfAbsent("tooltipKind",classification.kind().name());
        out.putIfAbsent("tooltipTitle",original.isEmpty()?"":LoreParser.clean(original.getFirst().getString()));
        out.put("paragraphRole",role);return Map.copyOf(out);
    }
    private static boolean containsJapanese(String s) { return s.codePoints().anyMatch(c->c>=0x3040&&c<=0x30ff||c>=0x3400&&c<=0x9fff); }
}
