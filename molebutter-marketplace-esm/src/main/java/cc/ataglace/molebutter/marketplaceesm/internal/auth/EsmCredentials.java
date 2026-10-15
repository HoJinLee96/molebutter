package cc.ataglace.molebutter.marketplaceesm.internal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

/** Never serialize this object or include credentials in failures. */
final class EsmCredentials {
    private final String secret,master,issuer,gmarket,auction;
    EsmCredentials(String secret,String master,String issuer,String gmarket,String auction){
        this.secret=clean(secret);this.master=clean(master);this.issuer=clean(issuer);this.gmarket=clean(gmarket);this.auction=clean(auction);
    }
    private static String clean(String value){return value==null?"":value.trim();}
    private static boolean valid(String s){return !s.isBlank()&&s.length()<=4096&&!s.codePoints().anyMatch(c->c<=32||c>=127);}
    String seller(String market){String value=switch(market){case "GMARKET"->gmarket;case "AUCTION"->auction;default->throw new InputValidationFailure("ESM 사이트를 확인해 주세요.");};if(!valid(value)||value.contains(":")||value.contains(","))throw missing();return value;}
    String token(ObjectMapper json,Collection<String> markets){
        if(!valid(secret)||!valid(master)||!valid(issuer)||markets==null||markets.isEmpty())throw missing();
        var sites=new TreeSet<String>();for(String market:markets)sites.add(("GMARKET".equals(market)?"G:":"AUCTION".equals(market)?"A:":"INVALID:")+seller(market));
        try{
            String header=encode(json.writeValueAsBytes(Map.of("alg","HS256","typ","JWT","kid",master)));
            String payload=encode(json.writeValueAsBytes(Map.of("iss",issuer,"sub","sell","aud","sa.esmplus.com","ssi",String.join(",",sites))));
            String unsigned=header+"."+payload;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return unsigned+"."+encode(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
        }catch(InputValidationFailure e){throw e;}catch(Exception e){throw new IllegalStateException("ESM 인증 요청을 만들지 못했습니다.");}
    }
    String account(){
        if(!valid(master)||!valid(issuer))throw missing();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("ESM\n"+master+"\n"+issuer+"\n"+gmarket+"\n"+auction).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("ESM 계정을 확인하지 못했습니다.");}
    }
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static InputValidationFailure missing(){return new InputValidationFailure("ESM 연결 설정이 필요합니다. 마스터 ID, 발행자, 비밀키와 사이트 판매자 ID를 확인해 주세요.");}
}
