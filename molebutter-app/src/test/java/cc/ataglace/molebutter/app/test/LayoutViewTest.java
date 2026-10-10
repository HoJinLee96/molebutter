package cc.ataglace.molebutter.app.test;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import cc.ataglace.molebutter.app.internal.ViewController;
import cc.ataglace.molebutter.identity.api.UserRole;

class LayoutViewTest {
    @ParameterizedTest @EnumSource(UserRole.class)
    void rendersGroupedNavigationAndReadOnlySettings(UserRole role)throws Exception {
        var groups=ViewController.navigationGroups(role);
        assertThat(groups).allMatch(g->!g.items().isEmpty());
        var home=render("home","/",role);assertThat(home).contains("내 근태").doesNotContain("버전 기록","대시보드");
        if(role==UserRole.VIEWER){assertThat(home).doesNotContain("/settings","/products","/user-manage","/product-images");return;}
        var images=render("product-images","/product-images",role);
        assertThat(home).contains("/product-images");
        assertThat(images).contains("상품 이미지 도구","id=\"lookupForm\"","/js/product-images/app.js");
        Files.createDirectories(Path.of("target/ui-fixtures"));
        Files.writeString(Path.of("target/ui-fixtures/product-images-"+role.name()+".html"),images);
        if(role==UserRole.ADMIN) {
            var market=render("marketplaces","/marketplaces",role);
            assertThat(market).contains("판매 마켓","marketplace-query","/js/marketplaces.js","11번가 · 준비 중","G마켓 · 준비 중","window.MarketplaceChannels");
            Files.createDirectories(Path.of("target/ui-fixtures"));
            Files.writeString(Path.of("target/ui-fixtures/marketplaces-ADMIN.html"),market);
            var orders=render("marketplace-orders","/marketplace-orders",role);
            assertThat(orders).contains("판매 주문","orders-collect-form","orders-detail","/js/marketplace-orders.js");
            Files.writeString(Path.of("target/ui-fixtures/marketplace-orders-ADMIN.html"),orders);
            var editor=render("marketplace-editor","/marketplaces/coupang/products/new",role);
            assertThat(editor).contains("marketplace-editor-form","editor-validate","상품정보제공고시","/css/coupang-product-editor.css").doesNotContain("상품 저장");
            Files.writeString(Path.of("target/ui-fixtures/marketplace-editor-ADMIN.html"),editor);
            var naver=render("naver-product-editor","/marketplaces/naver/products/new",role);
            assertThat(naver).contains("naver-form","naver-save","naver-save-dialog","/js/naver-product-editor.js","/css/naver-product-editor.css");
            Files.writeString(Path.of("target/ui-fixtures/naver-product-editor-ADMIN.html"),naver);
            var common=render("common-marketplace-editor","/marketplaces/products/new",role);
            assertThat(common).contains("공통","임시 저장","/js/common-marketplace-editor.js").doesNotContain("외부 등록 성공");
            Files.writeString(Path.of("target/ui-fixtures/common-marketplace-editor-ADMIN.html"),common);
        } else assertThat(home).doesNotContain("/marketplaces");
        var html=render("settings","/settings",role);
        assertThat(html).contains("id=\"app-sidebar\"","aria-current=\"page\"","data-settings-panel=\"brands\"","/js/layout.js","/js/settings.js");
        assertThat(html).doesNotContain("th:each=","th:if=","th:href=");
        if(role==UserRole.PRODUCT)assertThat(html).doesNotContain("id=\"brand-create-form\"","id=\"schedule-settings-form\"","/auth-logs");
        else assertThat(html).contains("id=\"brand-create-form\"","id=\"schedule-settings-form\"","/auth-logs");
        var inventory=render("inventory","/inventory",role);
        assertThat(inventory).contains("data-inventory-admin=", "/js/inventory.js", "입출고 이력");
        if(role==UserRole.PRODUCT)assertThat(inventory).doesNotContain("name=\"unitPrice\"","name=\"paymentMethod\"","id=\"purchase-create\"");
        else assertThat(inventory).contains("id=\"purchase-view-content\"","매입 주문 상세","id=\"purchase-create\"");
        assertThat(inventory).doesNotContain("id=\"item-dialog\"","name=\"location\"");
        // 실제 Thymeleaf 결과를 격리된 브라우저 검증 서버에서 사용한다.
        var directory=Path.of("target/ui-fixtures");Files.createDirectories(directory);
        Files.writeString(directory.resolve("inventory-"+role.name()+".html"),inventory);
        Files.writeString(directory.resolve("settings-"+role.name()+".html"),html);
        Files.writeString(directory.resolve("home-"+role.name()+".html"),home);
        for(String page:List.of("attendance","attendance-manage"))
            Files.writeString(directory.resolve(page+"-"+role.name()+".html"),render(page,"/"+page,role));
        for(String page:List.of("products","product-refresh"))
            Files.writeString(directory.resolve(page+"-"+role.name()+".html"),render("products","/"+page,role));
    }
    private String render(String template,String path,UserRole role) {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var servlet=new MockServletContext();var request=new MockHttpServletRequest(servlet,"GET",path);
        var exchange=JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,new MockHttpServletResponse());
        var model=new HashMap<String,Object>();model.put("navigationGroups",ViewController.navigationGroups(role));model.put("currentPath",path);
        model.put("marketplaceChannels",cc.ataglace.molebutter.marketplace.api.MarketplaceChannels.all());
        model.put("userName","화면 테스트");model.put("menuItems",role.getSections());model.put("settingsAdmin",role==UserRole.ADMIN);
        model.put("inventoryAdmin",role==UserRole.ADMIN);model.put("productAdmin",role==UserRole.ADMIN);model.put("productPage",path.substring(1));
        return engine.process(template,new WebContext(exchange,Locale.KOREAN,model));
    }
}
