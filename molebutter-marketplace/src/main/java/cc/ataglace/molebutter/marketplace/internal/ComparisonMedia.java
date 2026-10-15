package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import java.io.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.media.api.ImageInspection;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;

/** Network reads happen only in prepare(), outside editor transactions. Decisions use stored evidence. */
@Service
final class ComparisonMedia {
    record Shape(Object structure,List<ImageInspection.Fingerprint> files) {}
    record Evidence(Map<String,ImageInspection.Fingerprint> files,Shape source,Shape observed,List<String> sourceIds,String origin) {}
    record Decision(String state,String token) {}
    private final JdbcTemplate db;private final ObjectMapper json;private final ImageInspection images;
    ComparisonMedia(JdbcTemplate db,ObjectMapper json,ImageInspection images){this.db=db;this.json=json;this.images=images;}
    void prepare(Long actor,Document reference,String market,EditingStore.Binding binding,MarketplaceProductAdapter adapter,Document observed,Document saved,Long execution,Document transmitted){
        if(!"COUPANG".equals(market)||binding.mapping()==null||observed==null||saved==null)return;
        var sent=transmitted==null?Map.<ConflictKey,Change>of():mediaFields(adapter,transmitted);var app=mediaFields(adapter,saved);var now=mediaFields(adapter,observed);var inputs=new LinkedHashMap<String,ImageInspection.Input>();
        for(var values:List.of(app,now))for(var field:values.values())try{references(field,inputs);}catch(RuntimeException ignored){}
        var fingerprints=new HashMap<String,ImageInspection.Fingerprint>();
        for(var value:images.fingerprints(actor,new ArrayList<>(inputs.values())))fingerprints.put(value.key(),value);
        for(var e:app.entrySet()){var current=now.get(e.getKey());if(current==null)continue;
            var old=read(reference.id(),market,binding,e.getKey());Shape source=null,remote=null;try{source=shape(e.getValue(),fingerprints);remote=shape(current,fingerprints);}catch(RuntimeException ignored){}
            var decision=decide(source,remote,old);boolean matched=decision.state().equals("SAME");
            Long sourceExecution=execution!=null&&sent.containsKey(e.getKey())&&sameReferences(e.getValue(),sent.get(e.getKey()))?execution:null;
            var fieldInputs=new LinkedHashMap<String,ImageInspection.Input>();try{references(e.getValue(),fieldInputs);references(current,fieldInputs);}catch(RuntimeException ignored){}var fieldFiles=new HashMap<String,ImageInspection.Fingerprint>();for(var key:fieldInputs.keySet())if(fingerprints.containsKey(key))fieldFiles.put(key,fingerprints.get(key));
            var value=new Evidence(Map.copyOf(fieldFiles),matched?source:old==null?null:old.source(),matched?remote:old==null?null:old.observed(),ids(e.getValue()),adapter.comparisonSource(saved,e.getValue()));
            // A late read must not replace evidence for a new revision/product/connection generation.
            db.update("INSERT INTO marketplace_media_comparison(draft_id,market,account_key,listing_version,external_product_id,option_id,field_path,evidence_json,execution_id,updated_at) SELECT d.id,?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP(6) FROM marketplace_draft d JOIN marketplace_listing_mapping m ON m.draft_id=d.id AND m.market=? AND m.account_key=? AND m.external_product_id=? LEFT JOIN marketplace_listing_state s ON s.draft_id=m.draft_id AND s.market=m.market AND s.account_key=m.account_key WHERE d.id=? AND d.revision=? AND COALESCE(s.listing_version,1)=? ON DUPLICATE KEY UPDATE evidence_json=VALUES(evidence_json),execution_id=VALUES(execution_id),updated_at=CURRENT_TIMESTAMP(6)",market,binding.account(),binding.listingVersion(),binding.mapping().sellerProductId(),e.getKey().optionId(),e.getKey().path(),json.writeValueAsString(value),sourceExecution,market,binding.account(),binding.mapping().sellerProductId(),Long.parseLong(reference.id()),reference.revision(),binding.listingVersion());
        }
    }
    Map<ConflictKey,Decision> decisions(String draft,String market,EditingStore.Binding binding,MarketplaceProductAdapter adapter,Document observed,Document saved){
        if(!"COUPANG".equals(market)||saved==null||observed==null||binding.mapping()==null)return Map.of();
        var out=new HashMap<ConflictKey,Decision>();var now=mediaFields(adapter,observed);
        for(var e:mediaFields(adapter,saved).entrySet()){var current=now.get(e.getKey());if(current==null)continue;var old=read(draft,market,binding,e.getKey());try{var files=old==null?Map.<String,ImageInspection.Fingerprint>of():old.files();out.put(e.getKey(),decide(shape(e.getValue(),files),shape(current,files),old));}catch(RuntimeException unsupported){out.put(e.getKey(),new Decision("UNVERIFIED",TargetComparisons.hash(List.of(e.getValue(),current))));}}
        return Map.copyOf(out);
    }
    private Evidence read(String draft,String market,EditingStore.Binding binding,ConflictKey key){
        var rows=db.queryForList("SELECT evidence_json FROM marketplace_media_comparison WHERE draft_id=? AND market=? AND account_key=? AND listing_version=? AND external_product_id=? AND option_id=? AND field_path=?",String.class,Long.parseLong(draft),market,binding.account(),binding.listingVersion(),binding.mapping().sellerProductId(),key.optionId(),key.path());return rows.isEmpty()?null:json.readValue(rows.getFirst(),Evidence.class);
    }
    private static Map<ConflictKey,Change> mediaFields(MarketplaceProductAdapter adapter,Document d){var out=new LinkedHashMap<ConflictKey,Change>();for(var c:adapter.comparisonFields(d))if(c.optionId()!=null&&Set.of("media.images","media.contents").contains(c.path()))out.put(TargetComparisons.key(c),c);return out;}
    static Decision decide(Shape source,Shape remote,Evidence baseline){
        String token=TargetComparisons.hash(Arrays.asList(source,remote));if(source==null||remote==null)return new Decision("UNVERIFIED",token);
        if(!TargetComparisons.equal(source.structure(),remote.structure())||source.files().size()!=remote.files().size())return new Decision("DIFFERENT",token);
        boolean same=true;for(int n=0;n<source.files().size();n++){var a=source.files().get(n);var b=remote.files().get(n);if(a==null||b==null||a.sha256()==null||b.sha256()==null)return new Decision("UNVERIFIED",token);same&=a.sha256().equals(b.sha256())||a.pixels()!=null&&a.pixels().equals(b.pixels());}
        if(same)return new Decision("SAME",token);
        if(baseline!=null&&baseline.source()!=null&&baseline.observed()!=null){
            boolean sourceSame=sameFingerprint(source,baseline.source()),remoteSame=sameFingerprint(remote,baseline.observed());
            if(sourceSame!=remoteSame)return new Decision("DIFFERENT",token);
        }return new Decision("UNVERIFIED",token);
    }
    private static boolean sameFingerprint(Shape a,Shape b){if(!TargetComparisons.equal(a.structure(),b.structure())||a.files().size()!=b.files().size())return false;for(int n=0;n<a.files().size();n++){var x=a.files().get(n);var y=b.files().get(n);if(x==null||y==null||x.sha256()==null||y.sha256()==null||!x.sha256().equals(y.sha256())&&!(x.pixels()!=null&&x.pixels().equals(y.pixels())))return false;}return true;}
    private boolean sameReferences(Change a,Change b){var x=json.valueToTree(a.value()).deepCopy();var y=json.valueToTree(b.value()).deepCopy();for(var list:List.of(x,y))for(var row:list)if(row.isObject())for(String key:List.of("id","sourceId","optionId"))row.asObject().remove(key);return TargetComparisons.equal(x,y);}
    private List<String> ids(Change field){var out=new ArrayList<String>();for(var row:json.valueToTree(field.value()))out.add(row.path("sourceId").isString()?row.path("sourceId").asString():row.path("id").asString());return List.copyOf(out);}
    private void references(Change field,Map<String,ImageInspection.Input> inputs){shape(field,new HashMap<>(),inputs);}
    Shape shape(Change field,Map<String,ImageInspection.Fingerprint> files){return shape(field,files,new LinkedHashMap<>());}
    private Shape shape(Change field,Map<String,ImageInspection.Fingerprint> files,Map<String,ImageInspection.Input> refs){
        var structure=new ArrayList<Object>();var identities=new ArrayList<ImageInspection.Fingerprint>();
        for(var row:json.valueToTree(field.value())){
            if(field.path().equals("media.images")){String role=row.path("representative").asBoolean()?"REPRESENTATION":row.path("type").isString()?row.path("type").asString():"DETAIL";structure.add(List.of(role,row.path("order").asInt()));add(field,row.path("url").asString(),text(row,"assetId"),role,refs,files,identities);}
            else {String html="IMAGE".equals(row.path("type").asString())?"<img src=\""+row.path("value").asString().replace("&","&amp;").replace("\"","&quot;")+"\">":row.path("value").asString();if("IMAGE".equals(row.path("type").asString())){structure.add(List.of("IMAGE"));add(field,row.path("value").asString(),text(row,"assetId"),"DESCRIPTION",refs,files,identities);}else{var tokens=new ArrayList<Object>();
                    if(java.util.regex.Pattern.compile("(?i)\\bsrcset\\s*=|<picture\\b|<svg\\b|background(?:-image)?\\s*[:=]").matcher(html).find())throw new IllegalArgumentException();
                    try{new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback(){
                        private boolean wrapper(HTML.Tag tag){return tag==HTML.Tag.HTML||tag==HTML.Tag.HEAD||tag==HTML.Tag.BODY;}
                        private void tag(String kind,HTML.Tag tag,MutableAttributeSet attrs){if(wrapper(tag))return;var values=new TreeMap<String,String>();for(var it=attrs.getAttributeNames();it.hasMoreElements();){var key=it.nextElement();if(key.toString().equals("_implied_"))continue;String value=Objects.toString(attrs.getAttribute(key),"");if(tag==HTML.Tag.IMG&&key==HTML.Attribute.SRC){add(field,value,asset(value),"DESCRIPTION",refs,files,identities);value="IMAGE:"+(identities.size()-1);}values.put(key.toString(),value);}tokens.add(List.of(kind,tag.toString(),values));}
                        public void handleStartTag(HTML.Tag t,MutableAttributeSet a,int p){tag("START",t,a);}public void handleSimpleTag(HTML.Tag t,MutableAttributeSet a,int p){tag("SIMPLE",t,a);}public void handleEndTag(HTML.Tag t,int p){if(!wrapper(t))tokens.add(List.of("END",t.toString()));}public void handleText(char[] data,int p){tokens.add(List.of("TEXT",new String(data)));}
                    },true);}catch(IOException impossible){throw new IllegalArgumentException(impossible);}structure.add(tokens);
                }}
        }return new Shape(List.copyOf(structure),Collections.unmodifiableList(identities));
    }
    private static String text(JsonNode row,String name){return row.path(name).isString()?row.path(name).asString():null;}
    private static String asset(String value){var match=cc.ataglace.molebutter.media.api.EditedImages.ASSET.matcher(value);return match.matches()?match.group(1):null;}
    private static void add(Change field,String url,String asset,String role,Map<String,ImageInspection.Input> refs,Map<String,ImageInspection.Fingerprint> files,List<ImageInspection.Fingerprint> identities){var input=new ImageInspection.Input("COUPANG",role,field.optionId(),"comparison",url,asset,true);String key=ImageInspection.fingerprintKey(input);refs.putIfAbsent(key,input);identities.add(files.get(key));}
}
