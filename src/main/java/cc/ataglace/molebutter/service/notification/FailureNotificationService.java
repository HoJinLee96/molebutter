package cc.ataglace.molebutter.service.notification;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import cc.ataglace.molebutter.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class FailureNotificationService {
    private final NotificationService notifications;
    public static String requestKey(HttpServletRequest request) {
        String value=(String)request.getAttribute("notificationRequestKey");if(value!=null)return value;
        String supplied=request.getHeader("X-Operation-Id");
        try{value=UUID.fromString(supplied).toString();}catch(Exception e){value=UUID.randomUUID().toString();}
        request.setAttribute("notificationRequestKey",value);return value;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request,String message) {
        if(!Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod()))return;
        var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof UserPrincipal user))return;
        String path=request.getRequestURI(),target,title;Long targetId=null;
        if(path.equals("/api/products/code-preview"))return;
        if(path.startsWith("/api/products")){target="PRODUCTS";title="상품 처리 실패";
            if(path.matches("/api/products/[0-9]+(?:/.*)?")){target="PRODUCT";targetId=Long.valueOf(path.split("/")[3]);}}
        else if(path.startsWith("/api/product-refresh")){target="REFRESH";title="최신화 요청 실패";if(path.matches("/api/product-refresh/[0-9]+/.*"))targetId=Long.valueOf(path.split("/")[3]);else target="PRODUCTS";}
        else if(path.startsWith("/api/settings/")){target="SETTINGS";title="설정 저장 실패";}
        else if(path.startsWith("/api/attendance-manage/corrections/")||path.startsWith("/api/attendance/corrections/")){target="CORRECTION";title="근태 정정 처리 실패";try{targetId=Long.valueOf(path.split("/")[4]);}catch(Exception e){target="ATTENDANCE";}}
        else if(path.startsWith("/api/attendance")){target="ATTENDANCE";title="근태 처리 실패";}
        else if(path.startsWith("/api/admin/users/")){target="USERS";title="직원 정보 처리 실패";}
        else return; // Never generate notifications for notification/auth endpoints.
        notifications.publish("failure:"+user.userId()+":"+requestKey(request),"OPERATION_FAILED","ERROR",title,message,target,targetId,List.of(user.userId()));
    }
}
