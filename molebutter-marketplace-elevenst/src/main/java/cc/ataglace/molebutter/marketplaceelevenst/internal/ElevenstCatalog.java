package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.util.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;
@Component
final class ElevenstCatalog implements ElevenstProducts {
 record Stock(String remoteId,String localId,String sellerCode,String weight,String name,String extra,String quantity) {}
 record Snapshot(PartnerEditor.Observation observation,List<Stock> stocks,Map<String,String> values,String status,boolean supported,boolean wholeReadable) {}
 final ElevenstClient client;private final BusinessAccess access;
 @org.springframework.beans.factory.annotation.Value("${marketplace.elevenst.brands-file:}") private String brandsFile="";
 private final ElevenstReferenceData references=new ElevenstReferenceData();
 public BrandPage brands(Long actor,String name,int page){access.productActor(actor,true);return references.brands(brandsFile,name,page);}
 public List<Choice> origins(Long actor){access.productActor(actor,true);return references.origins();}
 public List<Choice> deliveryCompanies(Long actor){access.productActor(actor,true);return references.deliveryCompanies();}
 ElevenstCatalog(ElevenstClient client,BusinessAccess access){this.client=client;this.access=access;}
 public PartnerEditor.Schema schema(Long actor){access.productActor(actor,true);return ElevenstRules.SCHEMA;}
 public PartnerEditor.ProductPage list(Long actor,Query q){access.productActor(actor,true);if(q.page()<0||q.page()>100000||!Set.of(10,20,50,100).contains(q.size()))throw invalid("페이지를 확인해 주세요.");
  var body=new StringBuilder();if(q.productId()!=null&&!q.productId().isBlank())body.append(ElevenstXml.element("prdNo",ElevenstClient.id(q.productId())));if(q.name()!=null&&!q.name().isBlank()){if(q.name().length()>100)throw invalid("상품명은 100자 이하로 검색해 주세요.");body.append(ElevenstXml.element("prdNm",q.name()));}if(q.status()!=null&&!q.status().isBlank()){if(!Set.of("101","102","103","104","105","106","107","108").contains(q.status()))throw invalid("판매 상태를 확인해 주세요.");body.append(ElevenstXml.element("selStatCd",q.status()));}
  if(q.from()!=null&&!q.from().isBlank()||q.to()!=null&&!q.to().isBlank()){if(!Set.of("1","2","3","4").contains(Objects.toString(q.dateType(),""))||q.from()==null||q.to()==null||!q.from().matches("[0-9]{8}")||!q.to().matches("[0-9]{8}"))throw invalid("조회일자 유형과 YYYYMMDD 기간을 함께 입력해 주세요.");body.append(ElevenstXml.element("schDateType",q.dateType())).append(ElevenstXml.element("schBgnDt",q.from())).append(ElevenstXml.element("schEndDt",q.to()));}
  body.append(ElevenstXml.element("limit",q.size())).append(ElevenstXml.element("start",q.page()*q.size()+1)).append(ElevenstXml.element("end",(q.page()+1)*q.size()));
  var root=client.read("POST","/rest/prodmarketservice/prodmarket",ElevenstXml.document("SearchProduct",body.toString()),true);if(!ElevenstXml.name(root).equals("products"))throw response();
  var items=ElevenstXml.children(root,"product").stream().map(r->new PartnerEditor.Product(ElevenstXml.value(r,"prdNo"),null,ElevenstXml.value(r,"prdNm"),ElevenstXml.value(r,"selStatNm"),ElevenstXml.value(r,"selPrc"),"")).toList();return new PartnerEditor.ProductPage(items,q.page(),items.size()==q.size());
 }
 public PartnerEditor.Observation get(Long actor,String product){access.productActor(actor,true);return snapshot(product).observation();}
 Snapshot snapshot(String product){return client.session(()->{
  String id=ElevenstClient.id(product);var root=client.read("GET","/rest/prodmarketservice/prodmarket/"+id,null,true);if(!ElevenstXml.name(root).equals("Product")||!id.equals(ElevenstXml.value(root,"prdNo")))throw response();
  var values=new LinkedHashMap<String,String>();for(var e:ElevenstXml.children(root,null))if(ElevenstXml.children(e,null).isEmpty())values.put(ElevenstXml.name(e),e.getTextContent());
  var fields=new LinkedHashMap<String,Object>();for(var f:ElevenstRules.SCHEMA.fields()){String v=values.get(f.key());if(v!=null&&(f.choices().isEmpty()||f.choices().contains(v)))fields.put(f.key(),v);}
  if(!fields.containsKey("cuponcheck")){if(values.containsKey("dscAmtPercnt")&&values.containsKey("cupnDscMthdCd"))fields.put("cuponcheck","Y");else if(!values.containsKey("dscAmtPercnt")&&!values.containsKey("cupnDscMthdCd"))fields.put("cuponcheck","N");}
  var stockRoot=client.read("GET","/rest/prodmarketservice/prodmarket/stck/"+id,null,true);if(!ElevenstXml.name(stockRoot).equals("ProductStocks")||!id.equals(ElevenstXml.value(stockRoot,"prdNo")))throw response();
  var stocks=new ArrayList<Stock>();var options=new ArrayList<PartnerEditor.Option>();var identities=new ArrayList<PartnerEditor.Identity>();var seen=new HashSet<String>();
  for(var row:ElevenstXml.children(stockRoot,"ProductStock")){String remote=ElevenstClient.id(ElevenstXml.value(row,"prdStckNo"));if(!seen.add(remote)||!id.equals(ElevenstXml.value(row,"prdNo")))throw response();String local=id("ELEVENST:"+id+":"+remote),name=ElevenstXml.value(row,"mixDtlOptNm");if(name.isBlank())name="단일 상품";String code=ElevenstXml.value(row,"sellerStockCd"),extra=ElevenstXml.value(row,"addPrc"),quantity=ElevenstXml.value(row,"stckQty"),weight=ElevenstXml.value(row,"optWght"),price="";
   try{price=Long.toString(Math.addExact(ElevenstRules.discounted(new PartnerEditor.Input(fields,List.of(),List.of(),"","",List.of(),List.of())),Long.parseLong(extra)));}catch(RuntimeException ignored){}
   stocks.add(new Stock(remote,local,code,weight,name,extra,quantity));options.add(new PartnerEditor.Option(local,name,code,price,quantity,List.of(),!ElevenstXml.value(row,"prdStckStatCd").equals("02")));identities.add(new PartnerEditor.Identity(local,remote));
  }
  var images=new ArrayList<PartnerEditor.Image>();for(int n=1;n<=4;n++){String url=values.getOrDefault("prdImage0"+n,"");if(url.startsWith("https://"))images.add(new PartnerEditor.Image(id("ELEVENST:"+id+":image:"+n),null,url,n==1,n-1));}
  String html=values.get("htmlDetail");if(html==null){var detail=client.read("GET","/rest/prodservices/getProductDetailCont/"+id,null,true);if(!Set.of("ProductDetailCont","Product").contains(ElevenstXml.name(detail)))throw response();html=ElevenstXml.value(detail,"prdDescContClob");if(html.isEmpty()&&ElevenstXml.name(detail).equals("ProductDetailCont"))html=ElevenstXml.value(detail,"htmlDetail");}
  String noticeType="";var notices=new ArrayList<MarketplaceDrafts.Attribute>();for(var notice:ElevenstXml.children(root,"ProductNotification")){noticeType=ElevenstXml.value(notice,"type");for(var row:ElevenstXml.children(notice,"item"))notices.add(new MarketplaceDrafts.Attribute(ElevenstXml.value(row,"code"),ElevenstXml.value(row,"name")));}
  var certs=new ArrayList<PartnerEditor.Certification>();for(var g:ElevenstXml.children(root,"ProductCertGroup")){String group=ElevenstXml.value(g,"crtfGrpTypCd");fields.put("cert"+group+".target",ElevenstXml.value(g,"crtfGrpObjClfCd"));if(!ElevenstXml.value(g,"crtfGrpExptTypCd").isBlank())fields.put("cert"+group+".exemption",ElevenstXml.value(g,"crtfGrpExptTypCd"));for(var c:ElevenstXml.children(g,"ProductCert"))certs.add(new PartnerEditor.Certification(ElevenstXml.value(c,"certTypeCd"),ElevenstXml.value(c,"certKey"),group));}
  boolean supported="01".equals(values.get("selMthdCd"))&&"01".equals(values.get("prdStatCd"))&&"02".equals(values.get("dlvClf"))&&ElevenstXml.descendants(stockRoot,"productComponent").isEmpty()&&!values.getOrDefault("useOptCalc","N").equals("Y")&&ElevenstXml.descendants(root,"ProductCustOption").isEmpty()&&ElevenstXml.descendants(root,"ProductComponent").isEmpty()&&"01".equals(values.get("prdTypCd"))&&values.getOrDefault("abrdBuyPlace","").isBlank();
  var safeNames=new HashSet<String>(fields.keySet());safeNames.addAll(Set.of("prdNo","selStatCd","selStatNm","stdPrdYn","htmlDetail","prdImage01","prdImage02","prdImage03","prdImage04","ProductNotification","ProductCertGroup"));boolean wholeReadable=ElevenstXml.children(root,null).stream().allMatch(v->safeNames.contains(ElevenstXml.name(v)));
  var readonly=new ArrayList<String>();for(var f:ElevenstRules.SCHEMA.fields())if(!fields.containsKey(f.key()))readonly.add(f.key());
  var input=new PartnerEditor.Input(Map.copyOf(fields),List.copyOf(options),List.copyOf(images),Objects.toString(html,""),noticeType,List.copyOf(notices),List.copyOf(certs));
  return new Snapshot(new PartnerEditor.Observation(input,id,Map.of(),List.copyOf(identities),List.copyOf(readonly),supported?"조회되지 않은 항목은 미확인입니다. 전체 수정에는 보존 가능한 최신값이 필요합니다.":"해외·예약·중고·특수 옵션 상품은 현재 수정 미지원입니다."),List.copyOf(stocks),Map.copyOf(values),values.getOrDefault("selStatCd",""),supported,wholeReadable);
 });}
 public List<Choice> categories(Long actor){access.productActor(actor,true);var root=client.read("GET","/rest/cateservice/category",null,false);if(!ElevenstXml.name(root).equals("categorys"))throw response();var rows=ElevenstXml.children(root,"category");var parents=new HashSet<String>();for(var row:rows)parents.add(ElevenstXml.value(row,"parentDispNo"));return rows.stream().map(r->new Choice(ElevenstXml.value(r,"dispNo"),ElevenstXml.value(r,"dispNm"),ElevenstXml.value(r,"parentDispNo"),!parents.contains(ElevenstXml.value(r,"dispNo")))).toList();}
 public List<Address> addresses(Long actor,String kind){access.productActor(actor,true);if(!Set.of("outbound","return").contains(kind))throw invalid("주소록 종류를 확인해 주세요.");var root=client.read("GET","/rest/areaservice/"+(kind.equals("outbound")?"outboundarea":"inboundarea"),null,true);if(!ElevenstXml.name(root).equals("inOutAddresss"))throw response();if(!Set.of("","SUCCESS").contains(ElevenstXml.value(root,"result_message")))throw response();return ElevenstXml.children(root,"inOutAddress").stream().map(r->new Address(ElevenstXml.value(r,"addrSeq"),ElevenstXml.value(r,"addrNm"),ElevenstXml.value(r,"addr"),ElevenstXml.value(r,"addrDtl"),ElevenstXml.value(r,"zipCode"),ElevenstXml.value(r,"prtblTlphnNo"))).toList();}
 public List<Choice> shippingTemplates(Long actor){access.productActor(actor,true);var root=client.read("GET","/rest/prodservices/sendCloseList",null,true);if(!ElevenstXml.name(root).equals("productInformationTemplateList"))throw response();return ElevenstXml.children(root,"templateBOList").stream().map(r->new Choice(ElevenstXml.value(r,"prdInfoTmpltNo"),ElevenstXml.value(r,"prdInfoTmpltNm"),null,true)).toList();}
 static MarketplaceFailure response(){return new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"ELEVENST");}
}
