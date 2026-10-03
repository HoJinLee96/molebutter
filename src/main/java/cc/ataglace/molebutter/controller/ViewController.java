package cc.ataglace.molebutter.controller;

import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import cc.ataglace.molebutter.domain.MenuSection;
import cc.ataglace.molebutter.domain.User;
import cc.ataglace.molebutter.domain.UserAuthEventType;
import cc.ataglace.molebutter.dto.auth.MeResponse;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.security.UserPrincipal;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ViewController {

    /** 페이지가 구현된 섹션 — 홈 카드·좌측 메뉴에서 실링크로 노출된다. 챕터가 진행되며 하나씩 추가한다. */
    private static final Set<MenuSection> READY_SECTIONS = EnumSet.of(
            MenuSection.PRODUCTS, MenuSection.PRODUCT_REFRESH, MenuSection.INVENTORY, MenuSection.USER_MANAGE, MenuSection.AUTH_LOGS, MenuSection.ATTENDANCE, MenuSection.ATTENDANCE_MANAGE, MenuSection.SETTINGS);

    public record MenuGroup(String label, List<MenuSection> items) {}
    private static final List<MenuGroup> GROUPS = List.of(
        new MenuGroup("상품 업무",List.of(MenuSection.PRODUCTS,MenuSection.PRODUCT_REFRESH,MenuSection.INVENTORY)),
        new MenuGroup("근태",List.of(MenuSection.ATTENDANCE,MenuSection.ATTENDANCE_MANAGE)),
        new MenuGroup("운영 관리",List.of(MenuSection.USER_MANAGE,MenuSection.AUTH_LOGS)),
        new MenuGroup("설정",List.of(MenuSection.SETTINGS)));

    public static List<MenuGroup> navigationGroups(cc.ataglace.molebutter.domain.UserRole role) {
        return GROUPS.stream().map(g->new MenuGroup(g.label(),g.items().stream()
            .filter(role.getSections()::contains).filter(READY_SECTIONS::contains).toList()))
            .filter(g->!g.items().isEmpty()).toList();
    }

    private final UserRepository userRepository;

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        if(addLayoutModel(principal,model)==null) return "redirect:/signin";
        model.addAttribute("settingsAdmin",principal.userRole()==cc.ataglace.molebutter.domain.UserRole.ADMIN);
        return "settings";
    }

    @GetMapping({"/products", "/product-refresh"})
    public String products(@AuthenticationPrincipal UserPrincipal principal, Model model, jakarta.servlet.http.HttpServletRequest request) {
        if (addLayoutModel(principal, model) == null) return "redirect:/signin";
        model.addAttribute("productPage", request.getRequestURI().substring(1));
        model.addAttribute("productAdmin", principal.userRole() == cc.ataglace.molebutter.domain.UserRole.ADMIN);
        return "products";
    }

    @GetMapping("/inventory")
    public String inventory(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        if(addLayoutModel(principal,model)==null)return "redirect:/signin";
        model.addAttribute("inventoryAdmin",principal.userRole()==cc.ataglace.molebutter.domain.UserRole.ADMIN);
        return "inventory";
    }

    @GetMapping("/attendance")
    public String attendance(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        if (addLayoutModel(principal, model) == null) return "redirect:/signin";
        return "attendance";
    }

    @GetMapping("/attendance-manage")
    public String attendanceManage(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        if (addLayoutModel(principal, model) == null) return "redirect:/signin";
        return "attendance-manage";
    }

    /**
     * 로그인 후 홈으로 이동하는 기존 흐름을 유지한다.
     * 미인증이면 SecurityConfig의 entry point가 오기 전 안전망으로 /signin으로 보낸다.
     */
    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        User user = addLayoutModel(principal, model);
        if (user == null) {
            return "redirect:/signin";
        }
        return "home";
    }

    /** 마이페이지: 내 정보 + 비밀번호 변경(전 계정 공통 — 섹션 권한과 무관). */
    @GetMapping("/my-page")
    public String myPage(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        User user = addLayoutModel(principal, model);
        if (user == null) {
            return "redirect:/signin";
        }
        model.addAttribute("me", MeResponse.from(user));
        return "my-page";
    }

    /** 직원 관리 — SecurityConfig의 PERM_USER_MANAGE 섹션 규칙이 인가를 담당한다. */
    @GetMapping("/user-manage")
    public String userManage(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        User user = addLayoutModel(principal, model);
        if (user == null) {
            return "redirect:/signin";
        }
        return "user-manage";
    }

    /** 인증 로그 — PERM_AUTH_LOGS 섹션 규칙이 인가를 담당한다. */
    @GetMapping("/auth-logs")
    public String authLogs(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        User user = addLayoutModel(principal, model);
        if (user == null) {
            return "redirect:/signin";
        }
        model.addAttribute("eventTypes", UserAuthEventType.values());
        return "auth-logs";
    }

    @GetMapping("/signin")
    public String signin(@AuthenticationPrincipal UserPrincipal principal) {
        return principal != null ? "redirect:/" : "signin";
    }

    @GetMapping("/signup")
    public String signup(@AuthenticationPrincipal UserPrincipal principal) {
        return principal != null ? "redirect:/" : "signup";
    }

    @GetMapping("/find-id")
    public String findId(@AuthenticationPrincipal UserPrincipal principal) {
        return principal != null ? "redirect:/" : "find-id";
    }

    @GetMapping("/find-password")
    public String findPassword(@AuthenticationPrincipal UserPrincipal principal) {
        return principal != null ? "redirect:/" : "find-password";
    }

    /**
     * 공통 레이아웃(사이드바) 모델을 채우고 현재 사용자를 돌려준다.
     * principal이 없거나 계정이 사라진 경우 null — 호출부는 /signin으로 리다이렉트한다.
     */
    private User addLayoutModel(UserPrincipal principal, Model model) {
        if (principal == null) {
            return null;
        }
        User user = userRepository.findById(principal.userId()).orElse(null);
        if (user == null) {
            return null;
        }
        model.addAttribute("userName", user.getName());
        model.addAttribute("menuItems", principal.userRole().getSections());
        model.addAttribute("readySections", READY_SECTIONS);
        model.addAttribute("navigationGroups", navigationGroups(principal.userRole()));
        var request=(ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        model.addAttribute("currentPath",request==null?"/":request.getRequest().getRequestURI());
        return user;
    }
}
