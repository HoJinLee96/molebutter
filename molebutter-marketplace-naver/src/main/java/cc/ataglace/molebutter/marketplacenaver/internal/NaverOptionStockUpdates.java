package cc.ataglace.molebutter.marketplacenaver.internal;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.*;

/** Only explicitly changed stock rows go to option-stock; never copy the complete option inventory into PUT. */
final class NaverOptionStockUpdates {
    private final ObjectMapper json;
    NaverOptionStockUpdates(ObjectMapper json){this.json=json;}
    record Target(String localId,String remoteId,List<String> values,String sellerCode,Long beforeStock,Long stock,
                  Long beforePrice,Long price,boolean priceChanged,Boolean beforeUsable,Boolean usable,boolean usableChanged){}
    static boolean isStep(Step step){return step.type()==Type.STOCK&&step.path().matches("/v1/products/origin-products/[1-9][0-9]{0,18}/option-stock");}
    static void requireGeneralUpdateSafe(JsonNode source){
        if(!NaverEditPatch.editor(source).input().optionMode().equals("NONE"))throw restricted();
    }
    Step prepare(NaverEditor.Input observed,NaverEditor.Input current,NaverEditor.Input desired,Mapping mapping,List<MarketplaceEditing.Change> changes){
        if(!observed.optionMode().equals("COMBINATION")||!current.optionMode().equals("COMBINATION")||!desired.optionMode().equals("COMBINATION")
            ||changes.stream().anyMatch(c->!c.path().equals(NaverEditPatch.PREFIX+"options"))
            ||!observed.optionNames().equals(desired.optionNames())||!observed.optionNames().equals(current.optionNames()))throw restricted();
        var before=options(observed);var wanted=options(desired);if(!before.keySet().equals(wanted.keySet()))throw restricted();
        var targets=new ArrayList<Target>();
        for(var old:observed.options()){
            var next=wanted.get(old.id());
            if(!Objects.equals(old.values(),next.values())||!Objects.equals(old.sellerManagerCode(),next.sellerManagerCode()))throw restricted();
            boolean stock=!Objects.equals(old.stockQuantity(),next.stockQuantity()),price=!Objects.equals(old.price(),next.price()),usable=!Objects.equals(old.usable(),next.usable());
            if(!stock){if(price||usable)throw restricted();continue;}
            String remote=remote(mapping,old.id());if(remote==null)throw invalid("옵션 번호를 다시 조회해 주세요.");
            targets.add(new Target(old.id(),remote,old.values(),old.sellerManagerCode(),old.stockQuantity(),next.stockQuantity(),old.price(),next.price(),price,old.usable(),next.usable(),usable));
        }
        if(targets.isEmpty())throw restricted();
        var body=body(current,mapping,targets);
        return new Step(UUID.randomUUID().toString(),Type.STOCK,null,"PUT",path(mapping),"",json.writeValueAsString(body),json.writeValueAsString(targets),"{\"optionStock\":true}");
    }
    ObjectNode body(NaverEditor.Input current,Mapping mapping,Step step){
        if(!step.path().equals(path(mapping)))throw invalid("옵션 재고 대상 상품이 변경되었습니다.");
        return body(current,mapping,targets(step));
    }
    boolean reflected(NaverEditor.Input current,Mapping mapping,Step step){
        if(!step.path().equals(path(mapping)))return false;
        var rows=options(current);for(var target:targets(step)){
            var now=identity(rows,mapping,target);
            if(!Objects.equals(now.stockQuantity(),target.stock())||target.priceChanged()&&!Objects.equals(now.price(),target.price())||target.usableChanged()&&!Objects.equals(now.usable(),target.usable()))return false;
        }return true;
    }
    private ObjectNode body(NaverEditor.Input current,Mapping mapping,List<Target> targets){
        if(!current.optionMode().equals("COMBINATION"))throw restricted();
        String status=Objects.toString(current.fields().get("originProduct.statusType"),"");
        if(!Set.of("SALE","SUSPENSION","OUTOFSTOCK").contains(status))throw invalid("현재 판매 상태에서는 옵션 재고를 변경할 수 없습니다. 스마트스토어센터에서 상태를 확인해 주세요.");
        var currentRows=options(current);var body=json.createObjectNode();var info=body.putObject("optionInfo");info.put("useStockManagement",true);var rows=info.putArray("optionCombinations");var replacements=new HashMap<String,NaverEditor.Option>();
        for(var target:targets){
            var now=identity(currentRows,mapping,target);
            unchangedOrDesired(now.stockQuantity(),target.beforeStock(),target.stock());
            if(target.priceChanged())unchangedOrDesired(now.price(),target.beforePrice(),target.price());
            if(target.usableChanged())unchangedOrDesired(now.usable(),target.beforeUsable(),target.usable());
            Long price=target.priceChanged()?target.price():now.price();Boolean usable=target.usableChanged()?target.usable():now.usable();
            number(target.stock(),0,99999999);number(price,-999999990L,999999990L);
            Long sale=NaverEditPatch.number(json.valueToTree(current.fields().get("originProduct.salePrice")),null);
            if(sale==null||sale<0||sale>999999990||sale+price<0||sale+price>999999990)throw invalid("판매가와 옵션 추가 금액의 합계를 확인해 주세요.");
            if(usable==null)throw invalid("옵션 사용 여부를 다시 조회해 주세요.");
            rows.addObject().put("id",Long.parseLong(target.remoteId())).put("stockQuantity",target.stock()).put("price",price).put("usable",usable);
            replacements.put(now.id(),new NaverEditor.Option(now.id(),now.values(),price,target.stock(),now.sellerManagerCode(),usable));
        }
        long total=0;for(var row:current.options()){var next=replacements.getOrDefault(row.id(),row);number(next.stockQuantity(),0,99999999);if(Boolean.TRUE.equals(next.usable()))total=Math.addExact(total,next.stockQuantity());}if(total>99999999)throw invalid("판매 가능한 옵션의 전체 재고가 허용 범위를 초과했습니다.");
        return body;
    }
    private NaverEditor.Option identity(Map<String,NaverEditor.Option> rows,Mapping mapping,Target target){
        var now=rows.get(target.localId());if(now==null||!Objects.equals(remote(mapping,target.localId()),target.remoteId())||!now.values().equals(target.values())||!Objects.equals(now.sellerManagerCode(),target.sellerCode()))throw invalid("선택한 옵션 연결이 변경되었습니다. 다시 조회해 주세요.");return now;
    }
    private List<Target> targets(Step step){var result=new ArrayList<Target>();for(var node:json.readTree(step.baselineJson()))result.add(json.treeToValue(node,Target.class));if(result.isEmpty())throw invalid("선택한 옵션 재고를 확인해 주세요.");return List.copyOf(result);}
    private static Map<String,NaverEditor.Option> options(NaverEditor.Input input){var result=new LinkedHashMap<String,NaverEditor.Option>();for(var row:input.options())if(result.put(row.id(),row)!=null)throw invalid("옵션 연결이 중복되었습니다.");return result;}
    private static String remote(Mapping mapping,String local){if(mapping!=null&&mapping.options()!=null)for(var link:mapping.options())if(link.optionId().equals(local)){String id=link.sellerProductItemId();if(id!=null&&id.matches("[1-9][0-9]{0,18}"))return id;}return null;}
    private static String path(Mapping mapping){return "/v1/products/origin-products/"+mapping.sellerProductId()+"/option-stock";}
    private static void unchangedOrDesired(Object now,Object before,Object desired){if(!Objects.equals(now,before)&&!Objects.equals(now,desired))throw invalid("선택한 옵션 값이 외부에서 변경되었습니다. 다시 조회해 주세요.");}
    private static void number(Long value,long min,long max){if(value==null||value<min||value>max)throw invalid("옵션 재고와 추가 금액의 허용 범위를 확인해 주세요.");}
    private static InputValidationFailure restricted(){return invalid("기존 옵션 상품은 선택한 옵션 재고 변경만 지원합니다. 옵션가·사용 여부는 재고를 함께 변경할 때 적용할 수 있습니다. 상품명·옵션 구조 등 다른 수정은 미선택 재고 보존이 확인될 때까지 스마트스토어센터를 이용해 주세요.");}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
