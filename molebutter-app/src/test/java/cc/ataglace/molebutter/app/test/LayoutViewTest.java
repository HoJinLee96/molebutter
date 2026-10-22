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
        var home=render("home","/",role);assertThat(home).contains("내 근태", "/js/form-actions.js").doesNotContain("버전 기록","대시보드");
        if(role==UserRole.VIEWER){assertThat(home).doesNotContain("/settings","/products","/user-manage","/product-images");return;}
        var images=render("product-images","/product-images",role);
        assertThat(home).contains("/product-images");
        assertThat(images).contains("상품 정보 도구","id=\"lookupForm\"","id=\"storageProductCodeInput\"","/js/product-images/app.js");
        assertThat(images).doesNotContain("id=\"uploadProductCodeInput\"");
        if(role==UserRole.ADMIN)assertThat(images).contains("id=\"registerCoupangProduct\"");else assertThat(images).doesNotContain("id=\"registerCoupangProduct\"");
        Files.createDirectories(Path.of("target/ui-fixtures"));
        Files.writeString(Path.of("target/ui-fixtures/product-images-"+role.name()+".html"),images);
        if(role==UserRole.ADMIN) {
            for(String page:List.of("signin","find-id","signup","find-password")) {
                var auth=render(page,"/"+page,role);
                assertThat(auth).contains("/js/form-actions.js").doesNotContain("id=\"app-sidebar\"");
                Files.writeString(Path.of("target/ui-fixtures/"+page+"-ADMIN.html"),auth);
            }
            var coupang=render("coupang","/coupang/products",role);
            assertThat(coupang).contains("쿠팡 상품","coupang-query","/js/coupang.js");
            Files.createDirectories(Path.of("target/ui-fixtures"));
            Files.writeString(Path.of("target/ui-fixtures/coupang-ADMIN.html"),coupang);
            var orders=render("coupang-orders","/coupang/orders",role);
            assertThat(orders).contains("판매 주문","orders-collect-form","orders-detail","/js/coupang-orders.js");
            Files.writeString(Path.of("target/ui-fixtures/coupang-orders-ADMIN.html"),orders);
            var editor=render("coupang-editor","/coupang/products/new",role);
            assertThat(editor).contains("coupang-editor-form","editor-validate","/js/coupang-editor.js","/css/coupang-product-editor.css","임시 저장","쿠팡에 반영").doesNotContain("내부 상품","common-editor-form","기준값");
            Files.writeString(Path.of("target/ui-fixtures/coupang-editor-ADMIN.html"),editor);
        } else assertThat(home).doesNotContain("href=\"/coupang\"");
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
        model.put("userName","화면 테스트");model.put("menuItems",role.getSections());model.put("settingsAdmin",role==UserRole.ADMIN);
        model.put("inventoryAdmin",role==UserRole.ADMIN);model.put("productAdmin",role==UserRole.ADMIN);model.put("productPage",path.substring(1));
        return engine.process(template,new WebContext(exchange,Locale.KOREAN,model));
    }
}
