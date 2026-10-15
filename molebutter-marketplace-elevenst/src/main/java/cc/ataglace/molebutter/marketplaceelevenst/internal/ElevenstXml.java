package cc.ataglace.molebutter.marketplaceelevenst.internal;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
/** No raw XML or untrusted element names cross the editor boundary. */
final class ElevenstXml {
 static final Charset CHARSET=Charset.forName("EUC-KR");
 static Document parse(byte[] bytes){try{
  var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
  var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});return b.parse(new ByteArrayInputStream(bytes));
 }catch(Exception e){throw new cc.ataglace.molebutter.marketplace.api.MarketplaceFailure(cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.RESPONSE,"ELEVENST");}}
 static String name(Node n){String s=n.getNodeName();return s.contains(":")?s.substring(s.indexOf(':')+1):s;}
 static List<Element> children(Node n,String name){var out=new ArrayList<Element>();for(var c=n.getFirstChild();c!=null;c=c.getNextSibling())if(c instanceof Element e&&(name==null||name(e).equals(name)))out.add(e);return out;}
 static String value(Node n,String name){return children(n,name).stream().findFirst().map(Node::getTextContent).orElse("");}
 static List<Element> descendants(Node n,String name){var out=new ArrayList<Element>();for(var e:children(n,null)){if(name(e).equals(name))out.add(e);out.addAll(descendants(e,name));}return out;}
 static String element(String key,Object value){if(!key.matches("[A-Za-z][A-Za-z0-9]*"))throw new IllegalArgumentException();return "<"+key+">"+escape(Objects.toString(value,""))+"</"+key+">";}
 static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
 static String document(String root,String body){return "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><"+root+">"+body+"</"+root+">";}
 static byte[] encode(String body){try{var b=CHARSET.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(body));byte[] bytes=new byte[b.remaining()];b.get(bytes);return bytes;}catch(CharacterCodingException e){throw new InputValidationFailure("11번가에서 처리할 수 없는 문자(이모지 등)를 제거해 주세요.");}}
}
