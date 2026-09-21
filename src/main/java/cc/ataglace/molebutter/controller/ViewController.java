package cc.ataglace.molebutter.controller;

import java.util.EnumSet;
import java.util.Set;

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

    /** 페이지가 구현된 섹션 — 홈 카드·헤더 메뉴에서 실링크로 노출된다. 챕터가 진행되며 하나씩 추가한다. */
    private static final Set<MenuSection> READY_SECTIONS = EnumSet.of(
            MenuSection.USER_MANAGE, MenuSection.AUTH_LOGS);

    private final UserRepository userRepository;

    /**
     * 로그인 후 임시 랜딩. 섹션 페이지들이 만들어지면 역할별 첫 섹션으로 보내는 방식으로 바뀔 수 있다.
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
     * 공통 레이아웃(헤더) 모델을 채우고 현재 사용자를 돌려준다.
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
        return user;
    }
}
