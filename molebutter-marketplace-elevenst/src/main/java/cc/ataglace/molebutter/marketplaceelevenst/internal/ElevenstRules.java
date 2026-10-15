package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.util.*;
import java.math.*;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;
final class ElevenstRules {
 static final Map<String,String> KEYS=Map.of("name","prdNm","code","sellerPrdCd","brand","brand","manufacturer","company","model","modelNm","afterService","asDetail");
 static final Map<String,Object> DEFAULTS=Map.of("selMthdCd","01","prdTypCd","01","prdStatCd","01","dlvClf","02","dlvCnAreaCd","01","dlvWyCd","01","dlvCstInstBasiCd","01","minorSelCnYn","Y","rmaterialTypCd","04");
 static final Set<String> MONEY=Set.of("selPrc","dscAmtPercnt","dlvCst1","PrdFrDlvBasiAmt","rtngdDlvCst","exchDlvCst");
 static final PartnerEditor.Schema SCHEMA=schema();
 private static PartnerEditor.Schema schema(){
  var fields=new ArrayList<PartnerEditor.Field>();
  String defs="prdNm|상품명|기본 정보|true;dispCtgrNo|카테고리|기본 정보|true;brand|브랜드명|기본 정보|false;apiPrdAttrBrandCd|브랜드 코드|기본 정보|false;sellerPrdCd|판매자 상품코드|기본 정보|false;company|제조사·수입사|기본 정보|false;modelNm|모델명|기본 정보|false;selMthdCd|판매 방식|기본 정보|true|01;prdTypCd|상품 유형|기본 정보|true|01;prdStatCd|상품 상태|기본 정보|true|01;minorSelCnYn|미성년자 구매|기본 정보|true|Y,N;rmaterialTypCd|원재료 유형|상품 정보|true|04,05;orgnTypCd|원산지 구분|상품 정보|true|01,02,03;orgnTypDtlsCd|원산지 지역 코드|상품 정보|false;orgnNmVal|원산지명|상품 정보|false;suplDtyfrPrdClfCd|부가세 (01 과세, 02 면세, 03 영세)|상품 정보|true|01,02,03;selPrc|할인 전 판매가|가격·판매|true;cuponcheck|즉시할인 사용|가격·판매|false|Y,N;dscAmtPercnt|즉시할인 금액·비율|가격·판매|false;cupnDscMthdCd|할인 단위 (01 원, 02 %)|가격·판매|false|01,02;cupnUseLmtDyYn|할인 종료일 사용|가격·판매|false|Y,N;cupnIssEndDy|할인 종료일|가격·판매|false;selPrdClfCd|판매기간 코드|가격·판매|true|0:100,3:101,5:102,7:103,15:104,30:105,60:106,90:107,120:108,1y:109,3y:110;aplBgnDy|판매 시작일|가격·판매|false;aplEndDy|판매 종료일|가격·판매|false;dlvClf|배송 주체|배송|true|02;dlvCnAreaCd|배송 가능 지역|배송|true|01,02;dlvWyCd|배송 방법|배송|true|01;dlvEtprsCd|택배사 코드|배송|true;dlvSendCloseTmpltNo|발송마감 템플릿|배송|true;dlvCstInstBasiCd|배송비 (01 무료, 02 고정, 03 조건부)|배송|true|01,02,03;dlvCst1|배송비|배송|false;PrdFrDlvBasiAmt|무료배송 기준|배송|false;dlvCstPayTypCd|배송비 결제 (03 선결제, 02 착불)|배송|false|02,03;addrSeqOut|출고지|배송|true;addrSeqIn|반품지|반품·교환|true;rtngdDlvCst|반품 배송비|반품·교환|true;exchDlvCst|교환 배송비 (왕복)|반품·교환|true;rtngdDlvCd|초기 무료배송 부과 (01 왕복, 02 편도)|반품·교환|false|01,02;asDetail|A/S 안내|반품·교환|true;rtngExchDetail|반품·교환 안내|반품·교환|true";
  for(String def:defs.split(";")){var p=def.split("\\|",-1);fields.add(new PartnerEditor.Field(p[0],p[1],p[2],MONEY.contains(p[0])?"number":p.length>4?"select":"text",Boolean.parseBoolean(p[3]),p.length>4?List.of(p[4].split(",")):List.of()));}
  for(String group:List.of("01","02","03","04")){fields.add(new PartnerEditor.Field("cert"+group+".target","인증 그룹 "+group+" 대상 구분","인증", "select",true,group.equals("04")?List.of("04","05"):group.equals("01")?List.of("01","02","03"):List.of("01","03")));fields.add(new PartnerEditor.Field("cert"+group+".exemption","인증 그룹 "+group+" 면제 유형","인증","select",false,List.of("02","03")));}
  return new PartnerEditor.Schema(List.copyOf(fields),500,4,"국내 고정가 새 상품과 일반 선택 옵션만 지원합니다. 조회되지 않은 값은 미확인입니다. 브랜드명만 입력하면 브랜드 검색·필터 노출이 제한될 수 있습니다.");
 }
 static PartnerEditor.Input normalize(PartnerEditor.Input i){return PartnerFormSupport.normalize(i,SCHEMA);}
 static String field(PartnerEditor.Input i,String key){return Objects.toString(i.fields().get(key),"");}
 static long number(String v,String label){return PartnerFormSupport.number(v,0,999999999,label);}
 static long discounted(PartnerEditor.Input i){long base=number(field(i,"selPrc"),"판매가");if(!field(i,"cuponcheck").equals("Y"))return base;var discount=new BigDecimal(number(field(i,"dscAmtPercnt"),"할인"));var unit=field(i,"cupnDscMthdCd");if(!Set.of("01","02").contains(unit))throw invalid("할인 단위를 선택해 주세요.");try{long amount=(unit.equals("01")?discount:BigDecimal.valueOf(base).multiply(discount).divide(BigDecimal.valueOf(100))).longValueExact();if(amount>base)throw new ArithmeticException();return base-amount;}catch(ArithmeticException e){throw invalid("할인 금액과 원 단위를 확인해 주세요.");}}
 static void validatePeriod(PartnerEditor.Input i){
  for(String k:List.of("aplBgnDy","aplEndDy","cupnIssEndDy"))if(!field(i,k).isBlank())try{java.time.LocalDate.parse(field(i,k),java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(java.time.DateTimeException bad){throw invalid(k+"은 YYYY/MM/DD 형식으로 입력해 주세요.");}
  if(!field(i,"aplBgnDy").isBlank()&&!field(i,"aplEndDy").isBlank()){var start=java.time.LocalDate.parse(field(i,"aplBgnDy"),java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd"));var end=java.time.LocalDate.parse(field(i,"aplEndDy"),java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd"));if(end.isAfter(start.plusYears(3)))throw invalid("11번가 판매기간은 최대 3년 범위에서 직접 설정해 주세요. 공통 종료일을 임의로 줄이지 않습니다.");}
  if(field(i,"selPrdClfCd").equals("0:100")&&(field(i,"aplBgnDy").isBlank()||field(i,"aplEndDy").isBlank()))throw invalid("직접 입력 판매기간의 시작일·종료일을 입력해 주세요.");
  if(!field(i,"aplBgnDy").isBlank()&&!field(i,"aplEndDy").isBlank()&&field(i,"aplBgnDy").compareTo(field(i,"aplEndDy"))>0)throw invalid("판매 종료일은 시작일 이후로 입력해 주세요.");
 }
 static void validate(PartnerEditor.Input i,boolean create){validate(i,create,create);}
 static void validate(PartnerEditor.Input i,boolean create,boolean periodChanged){
  normalize(i);
  if(create&&i.options().isEmpty())throw invalid("판매 옵션을 한 개 이상 입력해 주세요.");
  for(String k:List.of("asDetail","rtngExchDetail"))if(ElevenstXml.encode(field(i,k)).length>4000)throw invalid(k+"은 EUC-KR 4,000바이트 이하로 입력해 주세요.");
  if(periodChanged)validatePeriod(i);
  for(var f:SCHEMA.fields())if(create&&f.required()&&field(i,f.key()).isBlank())throw invalid(f.label()+"을 입력해 주세요.");
  if(create&&field(i,"brand").isBlank()&&field(i,"apiPrdAttrBrandCd").isBlank())throw invalid("브랜드명 또는 브랜드 코드를 입력해 주세요.");
  if(create&&field(i,"orgnTypCd").equals("03")&&field(i,"orgnNmVal").isBlank())throw invalid("원산지명을 입력해 주세요.");
  if(create&&Set.of("01","02").contains(field(i,"orgnTypCd"))&&field(i,"orgnTypDtlsCd").isBlank())throw invalid("원산지 지역 코드를 입력해 주세요.");
  if(field(i,"prdNm").codePointCount(0,field(i,"prdNm").length())>100)throw invalid("상품명은 100자 이하로 입력해 주세요.");
  for(String k:MONEY)if(!field(i,k).isBlank())number(field(i,k),k);
  if(!field(i,"selPrc").isBlank()&&number(field(i,"selPrc"),"판매가")%10!=0)throw invalid("판매가는 10원 단위로 입력해 주세요.");
  for(String k:List.of("rtngdDlvCst","exchDlvCst","dlvCst1"))if(!field(i,k).isBlank()&&number(field(i,k),k)%10!=0)throw invalid("배송비는 10원 단위로 입력해 주세요.");
  if(field(i,"cuponcheck").equals("Y")){long discounted=discounted(i);if(discounted<10||discounted%10!=0)throw invalid("할인 후 기본가는 10원 이상의 10원 단위로 입력해 주세요.");}
  if(field(i,"cupnUseLmtDyYn").equals("Y")&&field(i,"cupnIssEndDy").isBlank())throw invalid("할인 종료일을 입력해 주세요.");
  if(create&&Set.of("02","03").contains(field(i,"dlvCstInstBasiCd")))number(field(i,"dlvCst1"),"배송비");if(create&&field(i,"dlvCstInstBasiCd").equals("03"))number(field(i,"PrdFrDlvBasiAmt"),"무료배송 기준");
  if(create&&i.description().isBlank())throw invalid("상세설명을 입력해 주세요.");
  if(create&&(i.images().stream().filter(PartnerEditor.Image::representative).count()!=1||i.images().size()>4))throw invalid("대표 1장과 추가 최대 3장을 선택해 주세요.");
  if(create&&(i.noticeType().isBlank()||i.notices().isEmpty()))throw invalid("상품고시 유형 코드와 항목을 입력해 주세요.");
  for(String g:List.of("01","02","03","04")){var target=field(i,"cert"+g+".target");if(target.equals("02")&&field(i,"cert"+g+".exemption").isBlank())throw invalid("인증 면제유형을 선택해 주세요.");if(Set.of("01","04").contains(target)&&i.certifications().stream().noneMatch(c->c.agency().equals(g)&&!c.type().isBlank()&&!c.number().isBlank()))throw invalid("인증 그룹 "+g+"의 인증유형·번호를 입력해 주세요.");}
  var names=new HashSet<String>();boolean zero=false;long base=field(i,"selPrc").isBlank()?0:discounted(i);
  for(var o:i.options()){number(o.price(),"옵션 판매가");number(o.quantity(),"재고");if(!names.add(o.name()))throw invalid("옵션명이 중복되었습니다.");if(create){long extra=number(o.price(),"옵션 판매가")-base;if(extra*2< -number(field(i,"selPrc"),"판매가")||extra>number(field(i,"selPrc"),"판매가"))throw invalid("옵션 추가금은 기본 판매가 대비 -50%~+100% 범위로 입력해 주세요.");zero|=extra==0;if(number(o.quantity(),"재고")==0&&!Boolean.FALSE.equals(o.usable()))throw invalid("재고가 0인 신규 옵션은 사용을 해제해 주세요.");}if(ElevenstXml.encode(o.name()).length>50)throw invalid("옵션명은 EUC-KR 50바이트 이하로 입력해 주세요.");if(o.name().isBlank())throw invalid("옵션명을 입력해 주세요.");if(o.name().matches(".*[,;&\"%<>#†].*"))throw invalid("11번가 옵션명에서 특수문자를 제거해 주세요.");if(o.name().contains("'")||o.name().contains("|")||o.name().contains("[")||o.name().contains("]"))throw invalid("옵션명에서 특수문자를 제거해 주세요.");}
  if(create&&i.options().size()==1&&i.options().getFirst().name().equals("단일 상품")&&number(i.options().getFirst().quantity(),"재고")==0)throw invalid("옵션 없는 신규 상품 재고는 1개 이상이어야 합니다.");
  if(create&&!zero)throw invalid("추가금 0원 옵션이 한 개 이상 필요합니다.");
 }
}
