package cc.ataglace.molebutter.marketplaceelevenst.internal;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@TestConfiguration(proxyBeanMethods=false)
public class ProviderElevenstFixture {
    public static class Reads {public final List<String> calls=new CopyOnWriteArrayList<>();public boolean allowWrites;String created;String code;}
    @Bean Reads elevenstReads(){return new Reads();}
    @Bean @Primary ElevenstClient syntheticElevenstClient(Reads reads){return new ElevenstClient("synthetic-only",(method,path,body,auth)->{
        reads.calls.add(method+" "+path);String xml;
        if(method.equals("POST")&&path.equals("/rest/prodservices/product")&&reads.allowWrites){String request=new String(body,ElevenstXml.CHARSET);reads.created=request.replace("</Product>","<prdNo>5002</prdNo><selStatCd>103</selStatCd></Product>");var root=ElevenstXml.parse(body).getDocumentElement();reads.code=ElevenstXml.value(ElevenstXml.children(root,"ProductOption").getFirst(),"colSellerStockCd");xml="<ClientMessage><productNo>5002</productNo><resultCode>200</resultCode></ClientMessage>";}
        else if(path.equals("/rest/prodmarketservice/prodmarket/5002")&&reads.created!=null)xml=reads.created.replaceFirst("<\\?xml[^>]+>","");
        else if(path.equals("/rest/prodmarketservice/prodmarket/stck/5002")&&reads.created!=null)xml="<ProductStocks><prdNo>5002</prdNo><ProductStock><prdNo>5002</prdNo><prdStckNo>7002</prdStckNo><sellerStockCd>"+reads.code+"</sellerStockCd><mixDtlOptNm>레드</mixDtlOptNm><addPrc>0</addPrc><stckQty>9</stckQty><optWght>0</optWght></ProductStock></ProductStocks>";
        else if(path.equals("/rest/prodmarketservice/prodmarket/5001"))xml="<Product><prdNo>5001</prdNo><prdNm>11번가 실제 조회값</prdNm><selMthdCd>01</selMthdCd><prdStatCd>01</prdStatCd><prdTypCd>01</prdTypCd><dlvClf>02</dlvClf><selPrc>128000</selPrc><cuponcheck>Y</cuponcheck><dscAmtPercnt>41000</dscAmtPercnt><cupnDscMthdCd>01</cupnDscMthdCd><selStatCd>103</selStatCd><htmlDetail>11번가 설명</htmlDetail></Product>";
        else if(path.equals("/rest/prodmarketservice/prodmarket/stck/5001"))xml="<ProductStocks><prdNo>5001</prdNo><ProductStock><prdNo>5001</prdNo><prdStckNo>6001</prdStckNo><sellerStockCd>EXTERNAL</sellerStockCd><mixDtlOptNm>레드</mixDtlOptNm><addPrc>0</addPrc><stckQty>9</stckQty><optWght>0</optWght></ProductStock></ProductStocks>";
        else if(path.equals("/rest/prodmarketservice/prodmarket")&&method.equals("POST"))xml="<products><product><prdNo>5001</prdNo><prdNm>모의 조회</prdNm></product></products>";
        else if(path.equals("/rest/cateservice/category"))xml="<categorys><category><dispNo>20</dispNo><dispNm>지갑</dispNm><parentDispNo>0</parentDispNo></category></categorys>";
        else if(path.startsWith("/rest/areaservice/"))xml="<inOutAddresss><inOutAddress><addrSeq>7</addrSeq><addrNm>합성</addrNm></inOutAddress><result_message>SUCCESS</result_message></inOutAddresss>";
        else if(path.equals("/rest/prodservices/sendCloseList"))xml="<productInformationTemplateList><templateBOList><prdInfoTmpltNo>99</prdInfoTmpltNo><prdInfoTmpltNm>일반 발송</prdInfoTmpltNm></templateBOList></productInformationTemplateList>";
        else throw new AssertionError("Unexpected 11st mock request: "+method+" "+path);
        return new ElevenstClient.Response(200,ElevenstXml.encode(ElevenstXml.document("Response","").replace("<Response></Response>",xml)));
    },0);}
}
