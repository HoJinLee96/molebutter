package cc.ataglace.molebutter.app.internal;


import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.app.internal.ApiResponse;
import cc.ataglace.molebutter.operations.api.NotificationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;

    @GetMapping
    public ApiResponse<NotificationService.Inbox> list(@AuthenticationPrincipal UserPrincipal u,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notifications.list(u.userId(), cursor, size));
    }

    @GetMapping("/summary")
    public ApiResponse<NotificationService.Summary> summary(@AuthenticationPrincipal UserPrincipal u) {
        return ApiResponse.success(notifications.summary(u.userId()));
    }

    @GetMapping("/{id}/target")
    public ApiResponse<NotificationService.Target> target(@AuthenticationPrincipal UserPrincipal u,
            @PathVariable long id) {
        return ApiResponse.success(notifications.target(u.userId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> dismiss(@AuthenticationPrincipal UserPrincipal u, @PathVariable long id) {
        notifications.dismiss(u.userId(), id);
        return ApiResponse.success();
    }
}
