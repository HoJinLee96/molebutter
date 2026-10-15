package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.util.*;
import java.util.function.Function;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;
final class ElevenstWire {
 static String create(PartnerEditor.Input i,Function<PartnerEditor.Image,String> publicUrl,Function<String,String> html){
  StringBuilder b=new StringBuilder();for(var f:ElevenstRules.SCHEMA.fields())if(!f.key().contains(".")&&!ElevenstRules.field(i,f.key()).isBlank())b.append(ElevenstXml.element(f.key(),ElevenstRules.field(i,f.key())));
  var images=i.images().stream().sorted(Comparator.comparing((PartnerEditor.Image v)->!v.representative()).thenComparingInt(PartnerEditor.Image::order)).toList();for(int n=0;n<images.size();n++)b.append(ElevenstXml.element("prdImage0"+(n+1),publicUrl.apply(images.get(n))));b.append(ElevenstXml.element("htmlDetail",html.apply(i.description())));
  long discounted=ElevenstRules.discounted(i);if(i.options().size()==1&&i.options().getFirst().name().equals("단일 상품")){b.append(ElevenstXml.element("prdSelQty",i.options().getFirst().quantity()));}
  else{b.append(ElevenstXml.element("optSelectYn","Y")).append(ElevenstXml.element("txtColCnt","1"));for(var o:i.options()){
   long extra=ElevenstRules.number(o.price(),"옵션 가격")-discounted;if(extra*2< -ElevenstRules.number(ElevenstRules.field(i,"selPrc"),"판매가")||extra>ElevenstRules.number(ElevenstRules.field(i,"selPrc"),"판매가"))throw invalid("옵션 추가금 허용 범위를 확인해 주세요.");
   String code="MB"+o.id().replace("-","");b.append("<ProductOption>").append(ElevenstXml.element("useYn",Boolean.FALSE.equals(o.usable())?"N":"Y")).append(ElevenstXml.element("colOptPrice",extra)).append(ElevenstXml.element("colValue0",o.name())).append(ElevenstXml.element("colCount",o.quantity())).append(ElevenstXml.element("colSellerStockCd",code)).append("</ProductOption>");
  }}
  b.append("<ProductNotification>").append(ElevenstXml.element("type",i.noticeType()));for(var n:i.notices())b.append("<item>").append(ElevenstXml.element("code",n.name())).append(ElevenstXml.element("name",n.value())).append("</item>");b.append("</ProductNotification>");
  for(String g:List.of("01","02","03","04")){b.append("<ProductCertGroup>").append(ElevenstXml.element("crtfGrpTypCd",g)).append(ElevenstXml.element("crtfGrpObjClfCd",ElevenstRules.field(i,"cert"+g+".target")));if(!ElevenstRules.field(i,"cert"+g+".exemption").isBlank())b.append(ElevenstXml.element("crtfGrpExptTypCd",ElevenstRules.field(i,"cert"+g+".exemption")));for(var cert:i.certifications())if(cert.agency().equals(g))b.append("<ProductCert>").append(ElevenstXml.element("certTypeCd",cert.type())).append(ElevenstXml.element("certKey",cert.number())).append("</ProductCert>");b.append("</ProductCertGroup>");}
  return ElevenstXml.document("Product",b.toString());
 }
 static String values(String root,Map<String,?> fields){var b=new StringBuilder();fields.forEach((k,v)->b.append(ElevenstXml.element(k,v)));return ElevenstXml.document(root,b.toString());}
}
